// StockApiService.kt - Network layer for stock data aggregation (quotes + fundamentals)
package com.example.stockzilla

import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

data class IndustryPeer(
    val symbol: String,
    val companyName: String?,
    val sector: String?,
    val industry: String?,
    val price: Double?,
    val marketCap: Double?
)

class StockRepository(
    private val apiKey: String,
    private val finnhubApiKey: String? = null
) {
    private val gson = GsonBuilder()
        .create()

    private val finnhubRetrofit = Retrofit.Builder()
        .baseUrl("https://finnhub.io/api/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val finnhubApi = finnhubRetrofit.create(FinnhubApi::class.java)
    private val quoteDataSource: QuoteDataSource = FinnhubQuoteDataSource(finnhubApi, finnhubApiKey)

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

        // Fallback: just treat query as symbol if Finnhub search fails
        return@withContext Result.success(normalizedSymbol)
    }

    private suspend fun resolveSymbolViaFinnhub(query: String, exactSymbol: String?): String? {
        return try {
            if (!exactSymbol.isNullOrBlank()) {
                val profile = finnhubApi.getProfile(exactSymbol, finnhubApiKey!!)
                if (profile != null && profile.ticker != null) return exactSymbol
            }
            val search = finnhubApi.search(query, finnhubApiKey!!)
            val first = search.result?.firstOrNull()?.symbol?.takeIf { it.isNotBlank() }?.uppercase()
            first
        } catch (_: Exception) {
            null
        }
    }

    // Placeholder: industry peers via EDGAR/other source could go here in future.
    suspend fun getIndustryPeers(symbol: String?, industry: String, limit: Int = 50): Result<List<IndustryPeer>> =
        withContext(Dispatchers.IO) {
            Result.failure(Exception("Industry peers via EDGAR are not implemented yet."))
        }

    suspend fun getLatestQuotePrice(symbol: String): Result<Double?> = withContext(Dispatchers.IO) {
        val quoteResult = quoteDataSource.getQuote(symbol)
        quoteResult.map { it.current }.also { return@withContext it }
    }

    suspend fun getStockData(symbol: String): Result<StockData> = withContext(Dispatchers.IO) {
        val finnhubData = if (!finnhubApiKey.isNullOrBlank()) {
            getStockDataFromFinnhub(symbol).getOrNull()
        } else {
            null
        }

        if (finnhubData == null) {
            Result.failure(Exception("No data found for symbol $symbol"))
        } else {
            Result.success(finnhubData)
        }
    }

    private suspend fun getStockDataFromFinnhub(symbol: String): Result<StockData> {
        val token = finnhubApiKey ?: return Result.failure(Exception("No Finnhub key"))
        return try {
            val quote = finnhubApi.getQuote(symbol, token)
            val profile = finnhubApi.getProfile(symbol, token)
            val financialReports = runCatching {
                finnhubApi.getFinancialsReported(symbol = symbol, token = token).data.orEmpty()
            }.getOrElse { emptyList() }

            val reportsByYear = financialReports.sortedByDescending { it.year ?: Int.MIN_VALUE }
            val latestReport = reportsByYear.firstOrNull()?.report
            val previousReport = reportsByYear.getOrNull(1)?.report

            val latestIncome = latestReport?.incomeStatement.orEmpty()
            val latestBalance = latestReport?.balanceSheet.orEmpty()
            val latestCashFlow = latestReport?.cashFlow.orEmpty()
            val previousIncome = previousReport?.incomeStatement.orEmpty()

            val price = quote.current
            val marketCap = profile?.marketCapitalization
            val name = profile?.name
            val industry = profile?.industry
            val revenue = firstConceptValue(
                latestIncome,
                listOf("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "TotalRevenue")
            )
            val netIncome = firstConceptValue(latestIncome, listOf("NetIncomeLoss", "ProfitLoss"))
            val eps = firstConceptValue(
                latestIncome,
                listOf("EarningsPerShareBasic", "BasicEarningsLossPerShare", "EarningsPerShareDiluted")
            )
            val ebitda = firstConceptValue(
                latestIncome,
                listOf("EarningsBeforeInterestTaxesDepreciationAndAmortization", "OperatingIncomeLoss")
            )
            val totalAssets = firstConceptValue(latestBalance, listOf("Assets", "TotalAssets"))
            val totalLiabilities = firstConceptValue(latestBalance, listOf("Liabilities", "TotalLiabilities"))
            val totalEquity = firstConceptValue(
                latestBalance,
                listOf("StockholdersEquity", "Equity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")
            )
            val totalDebt = sumConceptValues(
                latestBalance,
                listOf(
                    "LongTermDebtAndCapitalLeaseObligations",
                    "LongTermDebt",
                    "LongTermBorrowings",
                    "DebtCurrent",
                    "ShortTermBorrowings"
                )
            )
            val totalCurrentAssets = firstConceptValue(latestBalance, listOf("AssetsCurrent"))
            val totalCurrentLiabilities = firstConceptValue(latestBalance, listOf("LiabilitiesCurrent"))
            val retainedEarnings = firstConceptValue(latestBalance, listOf("RetainedEarningsAccumulatedDeficit"))
            val operatingCashFlow = firstConceptValue(
                latestCashFlow,
                listOf("NetCashProvidedByUsedInOperatingActivities", "CashProvidedByUsedInOperatingActivitiesContinuingOperations")
            )
            val capex = firstConceptValue(latestCashFlow, listOf("PaymentsToAcquirePropertyPlantAndEquipment"))
            val freeCashFlow = firstConceptValue(latestCashFlow, listOf("FreeCashFlow"))
                ?: if (operatingCashFlow != null && capex != null) operatingCashFlow + capex else null
            val peRatio = if (price != null && eps != null && kotlin.math.abs(eps) > 1e-9) price / eps else null
            val psRatio = calculatePSRatio(revenue, marketCap)
            val roe = calculateROE(netIncome, totalEquity)
            val debtToEquity = calculateDebtToEquity(totalDebt, totalEquity)
            val pbRatio = calculatePriceToBook(marketCap, totalEquity)
            val outstandingShares = profile?.shareOutstanding ?: run {
                if (marketCap != null && price != null && price > 0) marketCap / price else null
            }
            val workingCapital = if (totalCurrentAssets != null && totalCurrentLiabilities != null) {
                totalCurrentAssets - totalCurrentLiabilities
            } else {
                null
            }

            val revenueHistory = reportsByYear.take(5).map {
                firstConceptValue(
                    it.report?.incomeStatement.orEmpty(),
                    listOf("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "TotalRevenue")
                )
            }
            val netIncomeHistory = reportsByYear.take(5).map {
                firstConceptValue(it.report?.incomeStatement.orEmpty(), listOf("NetIncomeLoss", "ProfitLoss"))
            }
            val ebitdaHistory = reportsByYear.take(5).map {
                firstConceptValue(
                    it.report?.incomeStatement.orEmpty(),
                    listOf("EarningsBeforeInterestTaxesDepreciationAndAmortization", "OperatingIncomeLoss")
                )
            }
            val averageRevenueGrowth = calculateAverageGrowth(revenueHistory)
            val latestRevenueGrowth = calculateLatestGrowth(revenueHistory)
            val averageNetIncomeGrowth = calculateAverageGrowth(netIncomeHistory)
            val freeCashFlowMargin = if (freeCashFlow != null && revenue != null && kotlin.math.abs(revenue) > 1e-9) {
                freeCashFlow / revenue
            } else {
                null
            }
            val latestEbitdaMargin = if (ebitda != null && revenue != null && kotlin.math.abs(revenue) > 1e-9) {
                ebitda / revenue
            } else {
                null
            }
            val previousRevenue = firstConceptValue(
                previousIncome,
                listOf("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "TotalRevenue")
            )
            val previousEbitda = firstConceptValue(
                previousIncome,
                listOf("EarningsBeforeInterestTaxesDepreciationAndAmortization", "OperatingIncomeLoss")
            )
            val previousEbitdaMargin = if (previousEbitda != null && previousRevenue != null && kotlin.math.abs(previousRevenue) > 1e-9) {
                previousEbitda / previousRevenue
            } else {
                null
            }
            val ebitdaMarginGrowth = if (latestEbitdaMargin != null && previousEbitdaMargin != null) {
                latestEbitdaMargin - previousEbitdaMargin
            } else {
                null
            }

            if (price == null && name == null && revenue == null && netIncome == null && totalAssets == null) {
                Result.failure(Exception("No data found for $symbol"))
            } else {
                val data = StockData(
                    symbol = symbol,
                    companyName = name,
                    price = price,
                    marketCap = marketCap,
                    revenue = revenue,
                    netIncome = netIncome,
                    eps = eps,
                    peRatio = peRatio,
                    psRatio = psRatio,
                    roe = roe,
                    debtToEquity = debtToEquity,
                    freeCashFlow = freeCashFlow,
                    pbRatio = pbRatio,
                    ebitda = ebitda,
                    outstandingShares = outstandingShares,
                    totalAssets = totalAssets,
                    totalLiabilities = totalLiabilities,
                    totalCurrentAssets = totalCurrentAssets,
                    totalCurrentLiabilities = totalCurrentLiabilities,
                    retainedEarnings = retainedEarnings,
                    netCashProvidedByOperatingActivities = operatingCashFlow,
                    workingCapital = workingCapital,
                    sector = null,
                    industry = industry,
                    revenueGrowth = latestRevenueGrowth,
                    averageRevenueGrowth = averageRevenueGrowth,
                    averageNetIncomeGrowth = averageNetIncomeGrowth,
                    freeCashFlowMargin = freeCashFlowMargin,
                    ebitdaMarginGrowth = ebitdaMarginGrowth,
                    revenueHistory = revenueHistory,
                    netIncomeHistory = netIncomeHistory,
                    ebitdaHistory = ebitdaHistory
                )
                Result.success(data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun firstConceptValue(
        items: List<FinnhubFinancialLineItem>,
        concepts: List<String>
    ): Double? {
        for (concept in concepts) {
            val value = items.firstOrNull { it.concept.equals(concept, ignoreCase = true) }?.value
            if (value != null && value.isFinite()) return value
        }
        return null
    }

    private fun sumConceptValues(
        items: List<FinnhubFinancialLineItem>,
        concepts: List<String>
    ): Double? {
        var sum = 0.0
        var count = 0
        for (concept in concepts) {
            val value = items.firstOrNull { it.concept.equals(concept, ignoreCase = true) }?.value
            if (value != null && value.isFinite()) {
                sum += value
                count++
            }
        }
        return if (count > 0) sum else null
    }

    private fun mergeStockData(primary: StockData, backup: StockData): StockData {
        return primary.copy(
            companyName = primary.companyName ?: backup.companyName,
            price = primary.price ?: backup.price,
            marketCap = primary.marketCap ?: backup.marketCap,
            revenue = primary.revenue ?: backup.revenue,
            netIncome = primary.netIncome ?: backup.netIncome,
            eps = primary.eps ?: backup.eps,
            peRatio = primary.peRatio ?: backup.peRatio,
            psRatio = primary.psRatio ?: backup.psRatio,
            roe = primary.roe ?: backup.roe,
            debtToEquity = primary.debtToEquity ?: backup.debtToEquity,
            freeCashFlow = primary.freeCashFlow ?: backup.freeCashFlow,
            pbRatio = primary.pbRatio ?: backup.pbRatio,
            ebitda = primary.ebitda ?: backup.ebitda,
            outstandingShares = primary.outstandingShares ?: backup.outstandingShares,
            totalAssets = primary.totalAssets ?: backup.totalAssets,
            totalLiabilities = primary.totalLiabilities ?: backup.totalLiabilities,
            sector = primary.sector ?: backup.sector,
            industry = primary.industry ?: backup.industry,
            revenueGrowth = primary.revenueGrowth ?: backup.revenueGrowth,
            averageRevenueGrowth = primary.averageRevenueGrowth ?: backup.averageRevenueGrowth,
            averageNetIncomeGrowth = primary.averageNetIncomeGrowth ?: backup.averageNetIncomeGrowth,
            totalCurrentAssets = primary.totalCurrentAssets ?: backup.totalCurrentAssets,
            totalCurrentLiabilities = primary.totalCurrentLiabilities ?: backup.totalCurrentLiabilities,
            retainedEarnings = primary.retainedEarnings ?: backup.retainedEarnings,
            netCashProvidedByOperatingActivities = primary.netCashProvidedByOperatingActivities
                ?: backup.netCashProvidedByOperatingActivities,
            freeCashFlowMargin = primary.freeCashFlowMargin ?: backup.freeCashFlowMargin,
            ebitdaMarginGrowth = primary.ebitdaMarginGrowth ?: backup.ebitdaMarginGrowth,
            workingCapital = primary.workingCapital ?: backup.workingCapital,
            revenueHistory = if (primary.revenueHistory.any { it != null }) primary.revenueHistory else backup.revenueHistory,
            netIncomeHistory = if (primary.netIncomeHistory.any { it != null }) primary.netIncomeHistory else backup.netIncomeHistory,
            ebitdaHistory = if (primary.ebitdaHistory.any { it != null }) primary.ebitdaHistory else backup.ebitdaHistory
        )
    }



    private fun calculateAverageGrowth(values: List<Double?>): Double? {
        if (values.size < 2) return null

        val growthRates = mutableListOf<Double>()
        for (i in 1 until values.size) {
            val previous = values[i]
            val current = values[i - 1]
            if (previous != null && current != null && kotlin.math.abs(previous) > 1e-9) {
                growthRates.add((current - previous) / kotlin.math.abs(previous))
            }
        }

        return if (growthRates.isNotEmpty()) growthRates.average() else null
    }

    private fun calculateLatestGrowth(values: List<Double?>): Double? {
        if (values.size < 2) return null
        val current = values[0]
        val previous = values[1]
        if (current != null && previous != null && kotlin.math.abs(previous) > 1e-9) {
            return (current - previous) / kotlin.math.abs(previous)
        }
        return null
    }

    private fun calculatePSRatio(revenue: Double?, marketCap: Double?): Double? {
        return when {
            marketCap != null && revenue != null && revenue > 0 -> marketCap / revenue
            else -> null
        }
    }

    private fun calculateROE(netIncome: Double?, equity: Double?): Double? {
        return if (netIncome != null && equity != null && equity > 0) {
            netIncome / equity
        } else null
    }

    private fun calculateDebtToEquity(debt: Double?, equity: Double?): Double? {
        return if (debt != null && equity != null && equity > 0) {
            debt / equity
        } else null
    }
    private fun calculatePriceToBook(marketCap: Double?, equity: Double?): Double? {
        return if (marketCap != null && equity != null && equity > 0) {
            marketCap / equity
        } else null
    }

    private fun calculateSharesOutstanding(
        directValue: Long?,
        marketCap: Double?,
        price: Double?
    ): Double? {
        return when {
            directValue != null && directValue > 0 -> directValue.toDouble()
            marketCap != null && price != null && price > 0 -> marketCap / price
            else -> null
        }
    }
}