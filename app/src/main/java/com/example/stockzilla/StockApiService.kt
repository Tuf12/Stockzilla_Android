package com.example.stockzilla

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
 * Database-first repository: fundamentals come from analyzed_stocks (Room),
 * EDGAR is called only when no local data exists or a new filing is detected.
 * Finnhub is used only for live price.
 */
class StockRepository(
    private val apiKey: String,
    private val finnhubApiKey: String? = null,
    private val analyzedStockDao: AnalyzedStockDao? = null
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
     * 1. Check analyzed_stocks DB for existing data
     * 2. If found and not stale, load from DB and merge with live price
     * 3. If not found or stale, fetch from SEC EDGAR, save to DB, merge with price
     */
    suspend fun getStockData(symbol: String): Result<StockData> = withContext(Dispatchers.IO) {
        val dao = analyzedStockDao

        // Step 1: Check analyzed_stocks for existing data
        val existing = dao?.getStock(symbol)

        if (existing != null) {
            Log.d(TAG, "Found $symbol in analyzed_stocks, checking freshness")

            val needsRefresh = shouldRefreshFromEdgar(existing)
            if (!needsRefresh) {
                // Load from DB and merge with latest price
                val stockData = existing.toStockData()
                val withPrice = mergeWithLivePrice(stockData)
                return@withContext Result.success(withPrice)
            }
            Log.d(TAG, "$symbol has a newer filing, refreshing from EDGAR")
        }

        // Step 2: Fetch from EDGAR
        val edgarResult = edgarService.loadFundamentalsForTicker(symbol)
        edgarResult.fold(
            onSuccess = { edgarData ->
                val withPrice = mergeWithLivePrice(edgarData)
                return@withContext Result.success(withPrice)
            },
            onFailure = { error ->
                // Fall back to existing DB data if EDGAR fails but we had a row
                if (existing != null) {
                    Log.w(TAG, "EDGAR fetch failed for $symbol, using existing DB data", error)
                    val stockData = existing.toStockData()
                    val withPrice = mergeWithLivePrice(stockData)
                    return@withContext Result.success(withPrice)
                }

                // Try Finnhub as last resort for basic data
                if (!finnhubApiKey.isNullOrBlank()) {
                    val finnhubResult = getBasicDataFromFinnhub(symbol)
                    if (finnhubResult != null) {
                        return@withContext Result.success(finnhubResult)
                    }
                }

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
        healthScore: HealthScore?,
        lastFilingDate: String?
    ) {
        val dao = analyzedStockDao ?: return
        val entity = AnalyzedStockEntity.fromStockData(stockData, sicCode, healthScore, lastFilingDate)
        dao.upsertStock(entity)
        Log.d(TAG, "Saved ${stockData.symbol} to analyzed_stocks")
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
     * Get latest filing date from submissions API.
     */
    suspend fun getLatestFilingDate(symbol: String): String? {
        val cik = edgarService.resolveCikForTicker(symbol) ?: return null
        val submissions = edgarService.getSubmissionsInfo(cik) ?: return null
        return submissions.latestFilingDate
    }

    // --------------- Price ---------------

    suspend fun getLatestQuotePrice(symbol: String): Result<Double?> = withContext(Dispatchers.IO) {
        quoteDataSource.getQuote(symbol).map { it.current }
    }

    // --------------- Industry Peers (from analyzed_stocks) ---------------

    suspend fun getIndustryPeers(symbol: String?, industry: String, limit: Int = 50): Result<List<IndustryPeer>> =
        withContext(Dispatchers.IO) {
            val dao = analyzedStockDao
                ?: return@withContext Result.failure(Exception("Database not available"))

            try {
                val existing = symbol?.let { dao.getStock(it) }
                val sicCode = existing?.sicCode

                val peers = if (!sicCode.isNullOrBlank()) {
                    dao.getSimilarStocks(sicCode, symbol ?: "")
                } else {
                    dao.getPeersBySector(industry)
                }

                val result = peers.take(limit).map { entity ->
                    IndustryPeer(
                        symbol = entity.symbol,
                        companyName = entity.companyName,
                        sector = entity.sector,
                        industry = entity.industry,
                        price = entity.price,
                        marketCap = entity.marketCap
                    )
                }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // --------------- Private Helpers ---------------

    private suspend fun shouldRefreshFromEdgar(existing: AnalyzedStockEntity): Boolean {
        val lastFiling = existing.lastFilingDate ?: return true
        return try {
            val cik = edgarService.resolveCikForTicker(existing.symbol)
            if (cik != null) {
                val submissions = edgarService.getSubmissionsInfo(cik)
                val latestFiling = submissions?.latestFilingDate
                latestFiling != null && latestFiling > lastFiling
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check filing freshness for ${existing.symbol}", e)
            false
        }
    }

    private suspend fun mergeWithLivePrice(stockData: StockData): StockData {
        val priceResult = try {
            if (!finnhubApiKey.isNullOrBlank()) {
                getLatestQuotePrice(stockData.symbol).getOrNull()
            } else null
        } catch (_: Exception) { null }

        val price = priceResult ?: stockData.price
        if (price == null) return stockData

        val shares = stockData.outstandingShares
        val marketCap = if (shares != null && shares > 0) price * shares else stockData.marketCap
        val eps = stockData.eps
            ?: if (stockData.netIncome != null && shares != null && shares > 0) stockData.netIncome / shares else null
        val peRatio = if (eps != null && abs(eps) > 1e-9) price / eps else null
        val revenuePerShare = if (stockData.revenue != null && shares != null && shares > 0) stockData.revenue / shares else null
        val psRatio = if (revenuePerShare != null && revenuePerShare > 0) price / revenuePerShare else null
        val equity = if (stockData.totalAssets != null && stockData.totalLiabilities != null) {
            stockData.totalAssets - stockData.totalLiabilities
        } else null
        val pbRatio = if (equity != null && equity > 0 && shares != null && shares > 0) {
            price / (equity / shares)
        } else null

        return stockData.copy(
            price = price,
            marketCap = marketCap,
            eps = eps,
            peRatio = peRatio,
            psRatio = psRatio,
            pbRatio = pbRatio
        )
    }

    /**
     * Minimal Finnhub fallback: just price + profile for company name/industry.
     * No fundamentals from Finnhub.
     */
    private suspend fun getBasicDataFromFinnhub(symbol: String): StockData? {
        val token = finnhubApiKey ?: return null
        return try {
            val quote = finnhubApi.getQuote(symbol, token)
            val profile = finnhubApi.getProfile(symbol, token)
            if (quote.current == null && profile?.name == null) return null

            StockData(
                symbol = symbol,
                companyName = profile?.name,
                price = quote.current,
                marketCap = profile?.marketCapitalization,
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
                outstandingShares = profile?.shareOutstanding,
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
