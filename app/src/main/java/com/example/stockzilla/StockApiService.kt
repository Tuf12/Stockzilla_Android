// StockApiService.kt - Network layer for FMP API integration
package com.example.stockzilla

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.annotations.SerializedName

data class FMPProfile(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("companyName") val companyName: String?,
    @SerializedName("mktCap") val marketCap: Double?,
    @SerializedName("sector") val sector: String?,
    @SerializedName("industry") val industry: String?,
    @SerializedName("description") val description: String?
)

data class FMPQuote(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("price") val price: Double?,
    @SerializedName("volume") val volume: Long?,
    @SerializedName("pe") val peRatio: Double?,
    @SerializedName("sharesOutstanding") val sharesOutstanding: Long?
)

data class FMPIncomeStatement(
    @SerializedName("revenue") val revenue: Double?,
    @SerializedName("netIncome") val netIncome: Double?,
    @SerializedName("eps") val eps: Double?,
    @SerializedName("ebitda") val ebitda: Double?,
    @SerializedName("calendarYear") val year: String?
)

data class FMPBalanceSheet(
    @SerializedName("totalAssets") val totalAssets: Double?,
    @SerializedName("totalLiabilities") val totalLiabilities: Double?,
    @SerializedName("totalEquity") val totalEquity: Double?,
    @SerializedName("totalDebt") val totalDebt: Double?,
    @SerializedName("totalCurrentAssets") val totalCurrentAssets: Double?,
    @SerializedName("totalCurrentLiabilities") val totalCurrentLiabilities: Double?,
    @SerializedName("retainedEarnings") val retainedEarnings: Double?,
    @SerializedName("workingCapital") val workingCapital: Double?
)

data class FMPCashFlow(
    @SerializedName("freeCashFlow") val freeCashFlow: Double?,
    @SerializedName("netCashProvidedByOperatingActivities") val operatingCashFlow: Double?
)

interface FMPApiService {
    @GET("profile/{symbol}")
    suspend fun getProfile(
        @Path("symbol") symbol: String,
        @Query("apikey") apiKey: String
    ): List<FMPProfile>

    @GET("quote/{symbol}")
    suspend fun getQuote(
        @Path("symbol") symbol: String,
        @Query("apikey") apiKey: String
    ): List<FMPQuote>

    @GET("income-statement/{symbol}")
    suspend fun getIncomeStatement(
        @Path("symbol") symbol: String,
        @Query("period") period: String = "annual",
        @Query("limit") limit: Int = 5,
        @Query("apikey") apiKey: String
    ): List<FMPIncomeStatement>

    @GET("balance-sheet-statement/{symbol}")
    suspend fun getBalanceSheet(
        @Path("symbol") symbol: String,
        @Query("period") period: String = "annual",
        @Query("limit") limit: Int = 5,
        @Query("apikey") apiKey: String
    ): List<FMPBalanceSheet>

    @GET("cash-flow-statement/{symbol}")
    suspend fun getCashFlow(
        @Path("symbol") symbol: String,
        @Query("period") period: String = "annual",
        @Query("limit") limit: Int = 5,
        @Query("apikey") apiKey: String
    ): List<FMPCashFlow>
}

class StockRepository(private val apiKey: String) {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://financialmodelingprep.com/api/v3/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(FMPApiService::class.java)

    suspend fun getStockData(symbol: String): Result<StockData> = withContext(Dispatchers.IO) {
        try {
            // Fetch all data concurrently
            val profile = apiService.getProfile(symbol, apiKey).firstOrNull()
            val quote = apiService.getQuote(symbol, apiKey).firstOrNull()
            val incomeStatements = apiService.getIncomeStatement(symbol, apiKey = apiKey)
            val balanceSheets = apiService.getBalanceSheet(symbol, apiKey = apiKey)
            val cashFlows = apiService.getCashFlow(symbol, apiKey = apiKey)

            val latestIncome = incomeStatements.firstOrNull()
            val latestBalance = balanceSheets.firstOrNull()
            val latestCashFlow = cashFlows.firstOrNull()
            val previousIncome = incomeStatements.getOrNull(1)

            val averageRevenueGrowth = calculateAverageGrowth(incomeStatements.map { it.revenue })
            val latestRevenueGrowth = calculateLatestGrowth(incomeStatements.map { it.revenue })
            val averageNetIncomeGrowth = calculateAverageGrowth(incomeStatements.map { it.netIncome })


            if (profile == null && quote == null) {
                return@withContext Result.failure(Exception("No data found for symbol $symbol"))
            }

            // Calculate derived metrics
            val psRatio = calculatePSRatio(latestIncome?.revenue, profile?.marketCap)
            val roe = calculateROE(latestIncome?.netIncome, latestBalance?.totalEquity)
            val debtToEquity = calculateDebtToEquity(latestBalance?.totalDebt, latestBalance?.totalEquity)
            val priceToBook = calculatePriceToBook(profile?.marketCap, latestBalance?.totalEquity)
            val outstandingShares = calculateSharesOutstanding(
                directValue = quote?.sharesOutstanding,
                marketCap = profile?.marketCap,
                price = quote?.price
            )

            val freeCashFlowMargin = latestCashFlow?.freeCashFlow?.let { fcf ->
                val revenue = latestIncome?.revenue
                if (revenue != null && kotlin.math.abs(revenue) > 1e-9) fcf / revenue else null
            }

            val latestEbitdaMargin = latestIncome?.let { income ->
                val revenue = income.revenue
                val ebitda = income.ebitda
                if (revenue != null && ebitda != null && kotlin.math.abs(revenue) > 1e-9) {
                    ebitda / revenue
                } else {
                    null
                }
            }

            val previousEbitdaMargin = previousIncome?.let { income ->
                val revenue = income.revenue
                val ebitda = income.ebitda
                if (revenue != null && ebitda != null && kotlin.math.abs(revenue) > 1e-9) {
                    ebitda / revenue
                } else {
                    null
                }
            }

            val ebitdaMarginGrowth = if (latestEbitdaMargin != null && previousEbitdaMargin != null) {
                latestEbitdaMargin - previousEbitdaMargin
            } else {
                null
            }

            val operatingCashFlow = latestCashFlow?.operatingCashFlow
            val workingCapital = latestBalance?.workingCapital ?: run {
                val currentAssets = latestBalance?.totalCurrentAssets
                val currentLiabilities = latestBalance?.totalCurrentLiabilities
                if (currentAssets != null && currentLiabilities != null) {
                    currentAssets - currentLiabilities
                } else {
                    null
                }
            }

            val revenueHistory = incomeStatements.map { it.revenue }
            val netIncomeHistory = incomeStatements.map { it.netIncome }
            val ebitdaHistory = incomeStatements.map { it.ebitda }

            val stockData = StockData(
                symbol = symbol,
                companyName = profile?.companyName,
                price = quote?.price,
                marketCap = profile?.marketCap,
                revenue = latestIncome?.revenue,
                netIncome = latestIncome?.netIncome,
                eps = latestIncome?.eps,
                peRatio = quote?.peRatio,
                psRatio = psRatio,
                roe = roe,
                debtToEquity = debtToEquity,
                freeCashFlow = latestCashFlow?.freeCashFlow,
                pbRatio = priceToBook,
                ebitda = latestIncome?.ebitda,
                outstandingShares = outstandingShares,
                totalAssets = latestBalance?.totalAssets,
                totalLiabilities = latestBalance?.totalLiabilities,
                currentAssets = latestBalance?.totalCurrentAssets,
                currentLiabilities = latestBalance?.totalCurrentLiabilities,
                retainedEarnings = latestBalance?.retainedEarnings,
                sector = profile?.sector,
                industry = profile?.industry,
                revenueGrowth = latestRevenueGrowth,
                averageRevenueGrowth = averageRevenueGrowth,
                averageNetIncomeGrowth = averageNetIncomeGrowth,
                operatingCashFlow = operatingCashFlow,
                freeCashFlowMargin = freeCashFlowMargin,
                ebitdaMarginGrowth = ebitdaMarginGrowth,
                workingCapital = workingCapital,
                revenueHistory = revenueHistory,
                netIncomeHistory = netIncomeHistory,
                ebitdaHistory = ebitdaHistory
            )

            Result.success(stockData)
        } catch (e: Exception) {
            Result.failure(e)
        }
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