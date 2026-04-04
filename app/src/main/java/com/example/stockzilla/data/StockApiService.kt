package com.example.stockzilla.data

import android.util.Log
import com.example.stockzilla.analyst.EidosAnalystQuarterlyMerge
import com.example.stockzilla.analyst.EidosAnalystStockDataMerge
import com.example.stockzilla.feature.DiagnosticsLogger
import com.example.stockzilla.scoring.HealthScore
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.stock.FinnhubQuoteDataSource
import com.example.stockzilla.stock.QuoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import kotlin.math.abs

private const val TAG = "StockRepository"

data class IndustryPeer(
    val symbol: String,
    val companyName: String?,
    val sector: String?,
    val industry: String?,
    val price: Double?,
    val marketCap: Double?
)

/**
 * Database-first repository: fundamentals come from separated raw/derived Room tables.
 * EDGAR is called only when no local data exists or a new filing is detected.
 * Finnhub is used only for live price.
 */
class StockRepository(
    private val apiKey: String,
    private val finnhubApiKey: String? = null,
    private val rawFactsDao: EdgarRawFactsDao? = null,
    private val derivedMetricsDao: FinancialDerivedMetricsDao? = null,
    private val scoreSnapshotDao: ScoreSnapshotDao? = null,
    private val symbolTagOverrideDao: SymbolTagOverrideDao? = null,
    private val quarterlyFinancialFactDao: QuarterlyFinancialFactDao? = null,
    private val eidosAnalystConfirmedFactDao: EidosAnalystConfirmedFactDao? = null
) {
    private val finnhubRetrofit = Retrofit.Builder()
        .baseUrl("https://finnhub.io/api/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val finnhubApi = finnhubRetrofit.create(FinnhubApi::class.java)
    private val quoteDataSource: QuoteDataSource = FinnhubQuoteDataSource(finnhubApi, finnhubApiKey)
    private val edgarService = SecEdgarService.getInstance()

    // --------------- Symbol Resolution ---------------

    suspend fun resolveSymbol(query: String, limit: Int = 5): Result<String> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.failure(Exception("Please enter a stock symbol or company name."))
        }

        val normalizedSymbol = trimmedQuery.uppercase()

        if (!finnhubApiKey.isNullOrBlank()) {
            val finnhubResult = resolveSymbolViaFinnhub(trimmedQuery, normalizedSymbol)
            if (finnhubResult != null) {
                return@withContext Result.success(finnhubResult)
            }
        }

        return@withContext Result.success(normalizedSymbol)
    }

    private suspend fun resolveSymbolViaFinnhub(query: String, exactSymbol: String?): String? {
        return try {
            if (!exactSymbol.isNullOrBlank()) {
                val profile = finnhubApi.getProfile(exactSymbol, finnhubApiKey!!)
                if (profile != null && profile.ticker != null) return exactSymbol
            }
            val search = finnhubApi.search(query, finnhubApiKey!!)
            search.result?.firstOrNull()?.symbol?.takeIf { it.isNotBlank() }?.uppercase()
        } catch (_: Exception) {
            null
        }
    }

    // --------------- Main Data Flow (Database-First) ---------------

    /**
     * 1. Unless [forceFromEdgar] is true, check separated raw/derived DB for existing data
     * 2. If found and not stale, load from DB and merge with live price
     * 3. If not found, stale, or forceFromEdgar, fetch from SEC EDGAR, save to DB, merge with price
     */
    suspend fun getStockData(symbol: String, forceFromEdgar: Boolean = false): Result<StockData> = withContext(Dispatchers.IO) {
        val separatedRaw = rawFactsDao?.getBySymbol(symbol)
        val separatedDerived = derivedMetricsDao?.getBySymbol(symbol)
        val separatedStock = if (separatedRaw != null) separatedRaw.toStockData(separatedDerived) else null
        val existingStockData = separatedStock
        val existingLastFilingDate = separatedRaw?.lastFilingDate

        if (!forceFromEdgar && existingStockData != null) {
            Log.d(TAG, "Found $symbol in separated raw/derived tables, checking freshness")
            DiagnosticsLogger.log(symbol, "DATA_CHECK", "Found in separated raw/derived tables, checking freshness")

            val missingGrowth = separatedDerived?.let { hasNoGrowthData(it) } ?: true
            val needsRefresh = shouldRefreshFromEdgar(symbol, existingLastFilingDate) || missingGrowth
            if (!needsRefresh) {
                DiagnosticsLogger.log(symbol, "DATA_SOURCE_DB", "Using cached data (no refresh needed)")
                applyAnalystQuarterlyFactPatches(symbol)
                val merged = applyAnalystConfirmedFacts(symbol, existingStockData, existingLastFilingDate)
                val withPrice = mergeWithLivePrice(merged)
                return@withContext Result.success(withPrice)
            }
            if (missingGrowth) {
                Log.d(TAG, "$symbol has no growth data in DB, refreshing from EDGAR")
                DiagnosticsLogger.log(symbol, "EDGAR_REFRESH", "No growth data, fetching from EDGAR")
            } else {
                Log.d(TAG, "$symbol has a newer filing, refreshing from EDGAR")
                DiagnosticsLogger.log(symbol, "EDGAR_REFRESH", "New filing detected, fetching from EDGAR")
            }
        }
        if (forceFromEdgar) {
            DiagnosticsLogger.log(symbol, "EDGAR_REFRESH", "User requested refresh, fetching latest from SEC EDGAR")
        }

        val tagOverrides: List<SymbolTagOverrideEntity> =
            symbolTagOverrideDao?.getAllForSymbol(symbol).orEmpty()

        // Fetch from EDGAR
        val edgarResult = edgarService.loadFundamentalsForTicker(symbol, tagOverrides)
        edgarResult.fold(
            onSuccess = { edgarData ->
                try {
                    val quarterlyRows = edgarService.loadQuarterlyFactsForTicker(symbol, tagOverrides).getOrNull().orEmpty()
                    quarterlyFinancialFactDao?.deleteBySymbol(symbol)
                    if (quarterlyRows.isNotEmpty()) {
                        quarterlyFinancialFactDao?.upsertAll(quarterlyRows)
                    }
                    applyAnalystQuarterlyFactPatches(symbol)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist quarterly facts for $symbol", e)
                    DiagnosticsLogger.log(symbol, "QUARTERLY_SAVE_FAIL", e.message ?: "unknown")
                }
                DiagnosticsLogger.log(symbol, "DATA_SOURCE_EDGAR", "Saved to separated raw/derived tables")
                val lastFiling = try {
                    getLatestFilingDate(symbol)
                } catch (_: Exception) {
                    existingLastFilingDate
                }
                val merged = applyAnalystConfirmedFacts(symbol, edgarData, lastFiling ?: existingLastFilingDate)
                val withPrice = mergeWithLivePrice(merged)
                return@withContext Result.success(withPrice)
            },
            onFailure = { error ->
                if (existingStockData != null) {
                    Log.w(TAG, "EDGAR fetch failed for $symbol, using existing DB data", error)
                    DiagnosticsLogger.log(symbol, "EDGAR_FAIL_FALLBACK_DB", error.message ?: "unknown", "using cached row")
                    applyAnalystQuarterlyFactPatches(symbol)
                    val merged = applyAnalystConfirmedFacts(symbol, existingStockData, existingLastFilingDate)
                    val withPrice = mergeWithLivePrice(merged)
                    return@withContext Result.success(withPrice)
                }

                if (!finnhubApiKey.isNullOrBlank()) {
                    val finnhubResult = getBasicDataFromFinnhub(symbol)
                    if (finnhubResult != null) {
                        DiagnosticsLogger.log(symbol, "FALLBACK_FINNHUB", "No EDGAR/DB data, using Finnhub price+profile only")
                        return@withContext Result.success(finnhubResult)
                    }
                }

                DiagnosticsLogger.log(symbol, "DATA_FAIL", "No data from EDGAR, DB, or Finnhub", error.message)
                return@withContext Result.failure(error)
            }
        )
    }

    /**
     * Save analyzed data to the database after scoring.
     */
    suspend fun saveAnalyzedStock(
        stockData: StockData,
        sicCode: String?,
        naicsCode: String?,
        lastFilingDate: String?
    ) {
        val now = System.currentTimeMillis()

        rawFactsDao?.upsert(
            EdgarRawFactsEntity.fromStockData(
                stockData = stockData,
                sicCode = sicCode,
                naicsCode = naicsCode,
                lastFilingDate = lastFilingDate
            ).copy(analyzedAt = now, lastUpdated = now)
        )

        derivedMetricsDao?.upsert(
            FinancialDerivedMetricsEntity.fromStockData(stockData).copy(
                analyzedAt = now,
                lastUpdated = now
            )
        )

        Log.d(TAG, "Saved ${stockData.symbol} to raw/derived persistence domains")
    }

    /**
     * Re-merges analyst-confirmed facts into stored raw/derived for [symbol] (no EDGAR round-trip).
     * Used after proposal Accept so fundamentals and scoring reflect accepted values immediately.
     */
    suspend fun rePersistFundamentalsAfterAnalystAccept(symbol: String) = withContext(Dispatchers.IO) {
        val sym = symbol.trim().uppercase(Locale.US)
        val raw = rawFactsDao?.getBySymbol(sym) ?: return@withContext
        val derived = derivedMetricsDao?.getBySymbol(sym)
        var sd = raw.toStockData(derived)
        sd = applyAnalystConfirmedFacts(sym, sd, raw.lastFilingDate)
        sd = mergeWithLivePrice(sd)
        sd = sd.withGrowthFromHistory()
        saveAnalyzedStock(sd, raw.sicCode, raw.naicsCode, raw.lastFilingDate)
        applyAnalystQuarterlyFactPatches(sym)
    }

    suspend fun saveScoreSnapshot(
        symbol: String,
        healthScore: HealthScore,
        modelVersion: String = "v3-pillar-separated-linear-tiered"
    ) {
        scoreSnapshotDao?.insert(ScoreSnapshotEntity.fromHealthScore(symbol, healthScore, modelVersion))
    }

    /**
     * Get the SIC code for a ticker (cached after first resolution).
     */
    suspend fun getSicCode(symbol: String): String? {
        val cik = edgarService.resolveCikForTicker(symbol) ?: return null
        val submissions = edgarService.getSubmissionsInfo(cik) ?: return null
        return submissions.sic
    }

    /**
     * Get the NAICS code for a ticker from EDGAR submissions.
     */
    suspend fun getNaicsCode(symbol: String): String? {
        val cik = edgarService.resolveCikForTicker(symbol) ?: return null
        val submissions = edgarService.getSubmissionsInfo(cik) ?: return null
        return submissions.naics
    }

    /**
     * Get latest filing date from submissions API.
     */
    suspend fun getLatestFilingDate(symbol: String): String? {
        val cik = edgarService.resolveCikForTicker(symbol) ?: return null
        val submissions = edgarService.getSubmissionsInfo(cik) ?: return null
        return submissions.latestFilingDate
    }

    // --------------- Price ---------------

    // --------------- Industry Peers (separated DB + Finnhub peers for discovery) ---------------

    /**
     * Returns industry peers: first from separated DB (so we show previously analyzed with full data),
     * then from Finnhub stock/peers so the user can discover new stocks. Tapping a peer opens analysis.
     */
    suspend fun getIndustryPeers(symbol: String?, industry: String, limit: Int = 50): Result<List<IndustryPeer>> =
        withContext(Dispatchers.IO) {
            val rawDao = rawFactsDao
                ?: return@withContext Result.failure(Exception("Raw facts database not available"))

            try {
                val owner = symbol?.let { rawDao.getBySymbol(it) }
                val naicsCode = owner?.naicsCode
                val sicCode = owner?.sicCode

                val dbPeers = when {
                    !naicsCode.isNullOrBlank() -> rawDao.getPeerProfilesByNaics(naicsCode, symbol ?: "")
                    !sicCode.isNullOrBlank() -> rawDao.getPeerProfilesBySic(sicCode, symbol ?: "")
                    else -> rawDao.getPeerProfilesBySector(industry)
                }

                val fromDb = dbPeers.take(limit).map { entity ->
                    IndustryPeer(
                        symbol = entity.symbol,
                        companyName = entity.companyName,
                        sector = entity.sector,
                        industry = entity.industry,
                        price = entity.price,
                        marketCap = entity.marketCap
                    )
                }
                val existingSymbols = fromDb.map { it.symbol.uppercase() }.toSet()
                val currentSymbolUpper = symbol?.uppercase()

                // Add peers from Finnhub so user can discover new stocks not yet persisted locally.
                val fromFinnhub = if (!symbol.isNullOrBlank() && !finnhubApiKey.isNullOrBlank()) {
                    try {
                        val peerSymbols = finnhubApi.getStockPeers(symbol, finnhubApiKey!!)
                        peerSymbols
                            ?.filter { it.isNotBlank() }
                            ?.map { it.trim().uppercase() }
                            ?.distinct()
                            ?.filter { it != currentSymbolUpper && it !in existingSymbols }
                            ?.map { peerSymbol ->
                                IndustryPeer(
                                    symbol = peerSymbol,
                                    companyName = null,
                                    sector = null,
                                    industry = null,
                                    price = null,
                                    marketCap = null
                                )
                            }
                            ?: emptyList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Finnhub peers failed for $symbol", e)
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val combined = fromDb + fromFinnhub
                Result.success(combined.take(limit))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Search symbols/companies via Finnhub (for "Add stock" flow). */
    suspend fun searchSymbols(query: String): Result<List<FinnhubSearchResult>> = withContext(Dispatchers.IO) {
        if (finnhubApiKey.isNullOrBlank()) return@withContext Result.failure(Exception("API key required"))
        return@withContext try {
            val response = finnhubApi.search(query.trim(), finnhubApiKey!!)
            val list = response.result?.filter { it.symbol?.isNotBlank() == true }.orEmpty()
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Search failed", e)
            Result.failure(e)
        }
    }

    // --------------- Quotes ---------------

    suspend fun getLatestQuotePrice(symbol: String): Result<Double?> = withContext(Dispatchers.IO) {
        quoteDataSource.getQuote(symbol).map { it.current }
    }

    // --------------- Private Helpers ---------------
    private suspend fun shouldRefreshFromEdgar(symbol: String, lastFiling: String?): Boolean {
        val existingLastFiling = lastFiling ?: return true
        return try {
            val cik = edgarService.resolveCikForTicker(symbol)
            if (cik != null) {
                val submissions = edgarService.getSubmissionsInfo(cik)
                val latestFiling = submissions?.latestFilingDate
                latestFiling != null && latestFiling > existingLastFiling
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check filing freshness for $symbol", e)
            false
        }
    }

    private fun hasNoGrowthData(derived: FinancialDerivedMetricsEntity): Boolean {
        return derived.revenueGrowth == null &&
                derived.averageRevenueGrowth == null &&
                derived.averageNetIncomeGrowth == null &&
                derived.netIncomeGrowth == null &&
                derived.fcfGrowth == null
    }

    private suspend fun applyAnalystConfirmedFacts(
        symbol: String,
        stockData: StockData,
        lastFilingDate: String?
    ): StockData {
        val dao = eidosAnalystConfirmedFactDao ?: return stockData
        val facts = dao.getAllForSymbol(symbol.trim().uppercase(Locale.US))
        if (facts.isEmpty()) return stockData
        return EidosAnalystStockDataMerge.mergeConfirmedFactsIntoStockData(stockData, facts, lastFilingDate)
    }

    private suspend fun applyAnalystQuarterlyFactPatches(symbol: String) {
        val qDao = quarterlyFinancialFactDao ?: return
        val eidos = eidosAnalystConfirmedFactDao ?: return
        val sym = symbol.trim().uppercase(Locale.US)
        val facts = eidos.getAllForSymbol(sym)
        if (facts.isEmpty()) return
        val existing = qDao.getBySymbol(sym)
        val patches = EidosAnalystQuarterlyMerge.buildPatchEntities(sym, facts, existing)
        if (patches.isNotEmpty()) qDao.upsertAll(patches)
    }

    private suspend fun mergeWithLivePrice(stockData: StockData): StockData {
        val token = finnhubApiKey
        val priceResult = try {
            if (!token.isNullOrBlank()) {
                getLatestQuotePrice(stockData.symbol).getOrNull()
            } else null
        } catch (_: Exception) { null }

        val price = priceResult ?: stockData.price
        if (price == null) return stockData

        // Merge EDGAR and Finnhub views of shares outstanding.
        // Finnhub's `shareOutstanding` is documented as being in millions of shares.
        var shares = stockData.outstandingShares
        if (!token.isNullOrBlank()) {
            val finnhubShares = try {
                val profile = finnhubApi.getProfile(stockData.symbol, token)
                profile?.shareOutstanding?.let { it * 1_000_000.0 }
            } catch (_: Exception) {
                null
            }

            if (finnhubShares != null && finnhubShares > 0) {
                shares = when {
                    shares == null -> finnhubShares
                    // If Finnhub's view is materially larger (e.g. ADR ratios, stale EDGAR),
                    // prefer it to avoid under-reporting shares and over-stating per-share metrics.
                    shares > 0 && finnhubShares / shares > 1.5 -> finnhubShares
                    else -> shares
                }
            }
        }

        val marketCap = if (shares != null && shares > 0) price * shares else null

        val effectiveNetIncome = stockData.netIncomeDisplay
        val effectiveRevenue = stockData.revenueDisplay

        val eps = stockData.epsDisplay
            ?: if (effectiveNetIncome != null && shares != null && shares > 0) effectiveNetIncome / shares else null
        val peRatio = if (eps != null && abs(eps) > 1e-9) price / eps else null
        val revenuePerShare = if (effectiveRevenue != null && shares != null && shares > 0) effectiveRevenue / shares else null
        val psRatio = if (revenuePerShare != null && revenuePerShare > 0) price / revenuePerShare else null
        val equity = if (stockData.totalAssets != null && stockData.totalLiabilities != null) {
            stockData.totalAssets - stockData.totalLiabilities
        } else null
        val pbRatio = if (equity != null && equity > 0 && shares != null && shares > 0) {
            price / (equity / shares)
        } else null

        return stockData.copy(
            price = price,
            outstandingShares = shares ?: stockData.outstandingShares,
            marketCap = marketCap,
            eps = eps,
            peRatio = peRatio,
            psRatio = psRatio,
            pbRatio = pbRatio
        )
    }

    /**
     * Minimal Finnhub fallback: price + company name/industry + shares/marketCap.
     * No balance sheet or income statement fundamentals from Finnhub.
     */
    private suspend fun getBasicDataFromFinnhub(symbol: String): StockData? {
        val token = finnhubApiKey ?: return null
        return try {
            val quote = finnhubApi.getQuote(symbol, token)
            val profile = finnhubApi.getProfile(symbol, token)
            if (quote.current == null && profile?.name == null) return null

            val shares = profile?.shareOutstanding?.let { it * 1_000_000.0 }
            val price = quote.current
            val marketCap = if (price != null && shares != null && shares > 0) price * shares else null

            StockData(
                symbol = symbol,
                companyName = profile?.name,
                price = price,
                marketCap = marketCap,
                revenue = null,
                netIncome = null,
                eps = null,
                peRatio = null,
                psRatio = null,
                roe = null,
                debtToEquity = null,
                freeCashFlow = null,
                pbRatio = null,
                ebitda = null,
                outstandingShares = shares,
                totalAssets = null,
                totalLiabilities = null,
                sector = null,
                industry = profile?.industry
            )
        } catch (_: Exception) {
            null
        }
    }
}
