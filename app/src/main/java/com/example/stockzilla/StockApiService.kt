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
    @SerializedName("pe") val peRatio: Double?
)

data class FMPIncomeStatement(
    @SerializedName("revenue") val revenue: Double?,
    @SerializedName("netIncome") val netIncome: Double?,
    @SerializedName("eps") val eps: Double?,
    @SerializedName("calendarYear") val year: String?
)

data class FMPBalanceSheet(
    @SerializedName("totalAssets") val totalAssets: Double?,
    @SerializedName("totalLiabilities") val totalLiabilities: Double?,
    @SerializedName("totalEquity") val totalEquity: Double?,
    @SerializedName("totalDebt") val totalDebt: Double?
)

data class FMPCashFlow(
    @SerializedName("freeCashFlow") val freeCashFlow: Double?
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
            val income = apiService.getIncomeStatement(symbol, apiKey = apiKey).firstOrNull()
            val balance = apiService.getBalanceSheet(symbol, apiKey = apiKey).firstOrNull()
            val cashFlow = apiService.getCashFlow(symbol, apiKey = apiKey).firstOrNull()

            if (profile == null && quote == null) {
                return@withContext Result.failure(Exception("No data found for symbol $symbol"))
            }

            // Calculate derived metrics
            val psRatio = calculatePSRatio(quote?.price, income?.revenue, profile?.marketCap)
            val roe = calculateROE(income?.netIncome, balance?.totalEquity)
            val debtToEquity = calculateDebtToEquity(balance?.totalDebt, balance?.totalEquity)

            val stockData = StockData(
                symbol = symbol,
                companyName = profile?.companyName,
                price = quote?.price,
                marketCap = profile?.marketCap,
                revenue = income?.revenue,
                netIncome = income?.netIncome,
                eps = income?.eps,
                peRatio = quote?.peRatio,
                psRatio = psRatio,
                roe = roe,
                debtToEquity = debtToEquity,
                freeCashFlow = cashFlow?.freeCashFlow,
                sector = profile?.sector,
                industry = profile?.industry
            )

            Result.success(stockData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun calculatePSRatio(price: Double?, revenue: Double?, marketCap: Double?): Double? {
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
}