package com.example.stockzilla.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.stockzilla.scoring.HealthScore
import com.example.stockzilla.scoring.StockData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class DoubleListConverter {
    private val gson = Gson()
    private val type = object : TypeToken<List<Double?>>() {}.type

    @TypeConverter
    fun fromDoubleList(list: List<Double?>?): String? {
        return list?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toDoubleList(json: String?): List<Double?>? {
        return json?.let { gson.fromJson(it, type) }
    }
}

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val symbol: String,
    val companyName: String?,
    val price: Double?,
    val marketCap: Double?,
    val revenue: Double?,
    val netIncome: Double?,
    val eps: Double?,
    val peRatio: Double?,
    val psRatio: Double?,
    val roe: Double?,
    val debtToEquity: Double?,
    val freeCashFlow: Double?,
    val pbRatio: Double?,
    val ebitda: Double?,
    val outstandingShares: Double?,
    val totalAssets: Double?,
    val totalLiabilities: Double?,
    val totalCurrentAssets: Double?,
    val totalCurrentLiabilities: Double?,
    val retainedEarnings: Double?,
    val workingCapital: Double?,
    val netCashProvidedByOperatingActivities: Double?,
    val sector: String?,
    val industry: String?,
    val healthScore: Int?,
    val addedDate: Long,
    val lastUpdated: Long,
    val notes: String?
)

@Entity(tableName = "stock_cache")
data class StockCacheEntity(
    @PrimaryKey val symbol: String,
    val stockDataJson: String,
    val cachedAt: Long,
    val expiresAt: Long
)

private fun computeMarketCapTier(marketCap: Double?): String? {
    if (marketCap == null) return null
    return when {
        marketCap < 300_000_000 -> "micro"
        marketCap < 2_000_000_000 -> "small"
        marketCap < 10_000_000_000 -> "mid"
        marketCap < 200_000_000_000 -> "large"
        else -> "mega"
    }
}

@Entity(
    tableName = "edgar_raw_facts",
    indices = [
        Index(value = ["sicCode"]),
        Index(value = ["naicsCode"]),
        Index(value = ["sector"])
    ]
)
data class EdgarRawFactsEntity(
    @PrimaryKey val symbol: String,
    val companyName: String?,
    val cik: String?,
    val sicCode: String?,
    val naicsCode: String?,
    val sector: String?,
    val industry: String?,
    val revenue: Double?,
    val netIncome: Double?,
    val eps: Double?,
    val ebitda: Double?,
    val costOfGoodsSold: Double?,
    val grossProfit: Double?,
    val freeCashFlow: Double?,
    val operatingCashFlow: Double?,
    val outstandingShares: Double?,
    val totalAssets: Double?,
    val totalLiabilities: Double?,
    val totalCurrentAssets: Double?,
    val totalCurrentLiabilities: Double?,
    val totalAssetsHistoryJson: List<Double?>?,
    val totalCurrentAssetsHistoryJson: List<Double?>?,
    val totalCurrentLiabilitiesHistoryJson: List<Double?>?,
    val longTermDebtHistoryJson: List<Double?>?,
    val retainedEarnings: Double?,
    val workingCapital: Double?,
    val revenueTtm: Double?,
    val netIncomeTtm: Double?,
    val epsTtm: Double?,
    val ebitdaTtm: Double?,
    val costOfGoodsSoldTtm: Double?,
    val freeCashFlowTtm: Double?,
    val operatingCashFlowTtm: Double?,
    val capex: Double?,
    val capexTtm: Double?,
    val depreciationAmortization: Double?,
    val depreciationAmortizationTtm: Double?,
    val totalDebt: Double?,
    val cashAndEquivalents: Double?,
    val accountsReceivable: Double?,
    val operatingIncome: Double?,
    val operatingIncomeTtm: Double?,
    val operatingIncomeHistoryJson: List<Double?>?,
    val revenueHistoryJson: List<Double?>?,
    val netIncomeHistoryJson: List<Double?>?,
    val ebitdaHistoryJson: List<Double?>?,
    val costOfGoodsSoldHistoryJson: List<Double?>?,
    val grossProfitHistoryJson: List<Double?>?,
    val operatingCashFlowHistoryJson: List<Double?>?,
    val capexHistoryJson: List<Double?>?,
    val depreciationAmortizationHistoryJson: List<Double?>?,
    val freeCashFlowHistoryJson: List<Double?>?,
    val sharesOutstandingHistoryJson: List<Double?>?,
    val totalDebtHistoryJson: List<Double?>?,
    val lastFilingDate: String?,
    val analyzedAt: Long,
    val lastUpdated: Long
) {
    companion object {
        fun fromStockData(
            stockData: StockData,
            sicCode: String?,
            naicsCode: String?,
            lastFilingDate: String?
        ): EdgarRawFactsEntity {
            val now = System.currentTimeMillis()
            return EdgarRawFactsEntity(
                symbol = stockData.symbol,
                companyName = stockData.companyName,
                cik = stockData.cik,
                sicCode = sicCode ?: stockData.sicCode,
                naicsCode = naicsCode,
                sector = stockData.sector,
                industry = stockData.industry,
                revenue = stockData.revenue,
                netIncome = stockData.netIncome,
                eps = stockData.eps,
                ebitda = stockData.ebitda,
                costOfGoodsSold = stockData.costOfGoodsSold,
                grossProfit = stockData.grossProfit,
                freeCashFlow = stockData.freeCashFlow,
                operatingCashFlow = stockData.netCashProvidedByOperatingActivities,
                outstandingShares = stockData.outstandingShares,
                totalAssets = stockData.totalAssets,
                totalLiabilities = stockData.totalLiabilities,
                totalCurrentAssets = stockData.totalCurrentAssets,
                totalCurrentLiabilities = stockData.totalCurrentLiabilities,
                totalAssetsHistoryJson = stockData.totalAssetsHistory.takeIf { it.isNotEmpty() },
                totalCurrentAssetsHistoryJson = stockData.totalCurrentAssetsHistory.takeIf { it.isNotEmpty() },
                totalCurrentLiabilitiesHistoryJson = stockData.totalCurrentLiabilitiesHistory.takeIf { it.isNotEmpty() },
                longTermDebtHistoryJson = stockData.longTermDebtHistory.takeIf { it.isNotEmpty() },
                retainedEarnings = stockData.retainedEarnings,
                workingCapital = stockData.workingCapital,
                revenueTtm = stockData.revenueTtm,
                netIncomeTtm = stockData.netIncomeTtm,
                epsTtm = stockData.epsTtm,
                ebitdaTtm = stockData.ebitdaTtm,
                costOfGoodsSoldTtm = stockData.costOfGoodsSoldTtm,
                freeCashFlowTtm = stockData.freeCashFlowTtm,
                operatingCashFlowTtm = stockData.operatingCashFlowTtm,
                capex = stockData.capex,
                capexTtm = stockData.capexTtm,
                depreciationAmortization = stockData.depreciationAmortization,
                depreciationAmortizationTtm = stockData.depreciationAmortizationTtm,
                totalDebt = stockData.totalDebt,
                cashAndEquivalents = stockData.cashAndEquivalents,
                accountsReceivable = stockData.accountsReceivable,
                operatingIncome = stockData.operatingIncome,
                operatingIncomeTtm = stockData.operatingIncomeTtm,
                operatingIncomeHistoryJson = stockData.operatingIncomeHistory?.takeIf { it.isNotEmpty() },
                revenueHistoryJson = stockData.revenueHistory.takeIf { it.isNotEmpty() },
                netIncomeHistoryJson = stockData.netIncomeHistory.takeIf { it.isNotEmpty() },
                ebitdaHistoryJson = stockData.ebitdaHistory.takeIf { it.isNotEmpty() },
                costOfGoodsSoldHistoryJson = stockData.costOfGoodsSoldHistory.takeIf { it.isNotEmpty() },
                grossProfitHistoryJson = stockData.grossProfitHistory.takeIf { it.isNotEmpty() },
                operatingCashFlowHistoryJson = stockData.operatingCashFlowHistory?.takeIf { it.isNotEmpty() },
                capexHistoryJson = stockData.capexHistory?.takeIf { it.isNotEmpty() },
                depreciationAmortizationHistoryJson = stockData.depreciationAmortizationHistory?.takeIf { it.isNotEmpty() },
                freeCashFlowHistoryJson = stockData.freeCashFlowHistory?.takeIf { it.isNotEmpty() },
                sharesOutstandingHistoryJson = stockData.sharesOutstandingHistory?.takeIf { it.isNotEmpty() },
                totalDebtHistoryJson = stockData.totalDebtHistory.takeIf { it.isNotEmpty() },
                lastFilingDate = lastFilingDate,
                analyzedAt = now,
                lastUpdated = now
            )
        }
    }

    fun toStockData(derived: FinancialDerivedMetricsEntity?): StockData {
        return StockData(
            symbol = symbol,
            companyName = companyName,
            price = derived?.price,
            marketCap = derived?.marketCap,
            revenue = revenue,
            netIncome = netIncome,
            eps = eps,
            peRatio = derived?.peRatio,
            psRatio = derived?.psRatio,
            roe = derived?.roe,
            debtToEquity = derived?.debtToEquity,
            freeCashFlow = freeCashFlow,
            pbRatio = derived?.pbRatio,
            ebitda = ebitda,
            costOfGoodsSold = costOfGoodsSold,
            grossProfit = grossProfit,
            outstandingShares = outstandingShares,
            totalAssets = totalAssets,
            totalLiabilities = totalLiabilities,
            totalCurrentAssets = totalCurrentAssets,
            totalCurrentLiabilities = totalCurrentLiabilities,
            totalAssetsHistory = totalAssetsHistoryJson ?: emptyList(),
            totalCurrentAssetsHistory = totalCurrentAssetsHistoryJson ?: emptyList(),
            totalCurrentLiabilitiesHistory = totalCurrentLiabilitiesHistoryJson ?: emptyList(),
            longTermDebtHistory = longTermDebtHistoryJson ?: emptyList(),
            retainedEarnings = retainedEarnings,
            netCashProvidedByOperatingActivities = operatingCashFlow,
            workingCapital = workingCapital,
            sector = sector,
            industry = industry,
            sicCode = sicCode,
            cik = cik,
            revenueGrowth = derived?.revenueGrowth,
            averageRevenueGrowth = derived?.averageRevenueGrowth,
            averageNetIncomeGrowth = derived?.averageNetIncomeGrowth,
            netIncomeGrowth = derived?.netIncomeGrowth,
            fcfGrowth = derived?.fcfGrowth,
            averageFcfGrowth = derived?.averageFcfGrowth,
            freeCashFlowMargin = derived?.fcfMargin,
            ebitdaMarginGrowth = null,
            revenueHistory = revenueHistoryJson ?: emptyList(),
            netIncomeHistory = netIncomeHistoryJson ?: emptyList(),
            ebitdaHistory = ebitdaHistoryJson ?: emptyList(),
            costOfGoodsSoldHistory = costOfGoodsSoldHistoryJson ?: emptyList(),
            grossProfitHistory = grossProfitHistoryJson ?: emptyList(),
            operatingCashFlowHistory = operatingCashFlowHistoryJson,
            capexHistory = capexHistoryJson,
            depreciationAmortizationHistory = depreciationAmortizationHistoryJson,
            freeCashFlowHistory = freeCashFlowHistoryJson,
            sharesOutstandingHistory = sharesOutstandingHistoryJson,
            revenueTtm = revenueTtm,
            netIncomeTtm = netIncomeTtm,
            epsTtm = epsTtm,
            ebitdaTtm = ebitdaTtm,
            costOfGoodsSoldTtm = costOfGoodsSoldTtm,
            freeCashFlowTtm = freeCashFlowTtm,
            operatingCashFlowTtm = operatingCashFlowTtm,
            capex = capex,
            capexTtm = capexTtm,
            depreciationAmortization = depreciationAmortization,
            depreciationAmortizationTtm = depreciationAmortizationTtm,
            totalDebt = totalDebt,
            cashAndEquivalents = cashAndEquivalents,
            accountsReceivable = accountsReceivable,
            operatingIncome = operatingIncome,
            operatingIncomeTtm = operatingIncomeTtm,
            operatingIncomeHistory = operatingIncomeHistoryJson,
            totalDebtHistory = totalDebtHistoryJson ?: emptyList()
        )
    }
}

@Entity(
    tableName = "quarterly_financial_facts",
    primaryKeys = ["symbol", "metricKey", "periodEnd"],
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["symbol", "periodEnd"])
    ]
)
data class QuarterlyFinancialFactEntity(
    val symbol: String,
    val metricKey: String,
    val periodStart: String?,
    val periodEnd: String,
    val fiscalYear: Int?,
    val fiscalPeriod: String?,
    val form: String?,
    val unit: String,
    val taxonomy: String,
    val tag: String,
    val value: Double,
    val filedDate: String?,
    /** SEC companyfacts `accn` when present — for filing deep links. */
    val accessionNumber: String? = null,
    val lastUpdated: Long
)

@Entity(
    tableName = "financial_derived_metrics",
    indices = [
        Index(value = ["marketCapTier"]),
        Index(value = ["peRatio"]),
        Index(value = ["psRatio"])
    ]
)
data class FinancialDerivedMetricsEntity(
    @PrimaryKey val symbol: String,
    val marketCapTier: String?,
    val price: Double?,
    val marketCap: Double?,
    val peRatio: Double?,
    val psRatio: Double?,
    val pbRatio: Double?,
    val roe: Double?,
    val debtToEquity: Double?,
    val currentRatio: Double?,
    val netMargin: Double?,
    val ebitdaMargin: Double?,
    val grossMargin: Double?,
    val fcfMargin: Double?,
    val revenueGrowth: Double?,
    val averageRevenueGrowth: Double?,
    val averageNetIncomeGrowth: Double?,
    val netIncomeGrowth: Double?,
    val fcfGrowth: Double?,
    val averageFcfGrowth: Double?,
    val analyzedAt: Long,
    val lastUpdated: Long
) {
    companion object {
        fun fromStockData(stockData: StockData): FinancialDerivedMetricsEntity {
            val now = System.currentTimeMillis()
            val netIncomeDisplay = stockData.netIncomeDisplay
            val revenueDisplay = stockData.revenueDisplay
            val ebitdaDisplay = stockData.ebitdaDisplay
            val netMargin = if (netIncomeDisplay != null &&
                revenueDisplay != null &&
                abs(revenueDisplay) > 1e-9
            ) {
                netIncomeDisplay / revenueDisplay
            } else {
                null
            }
            val ebitdaMargin = if (ebitdaDisplay != null &&
                revenueDisplay != null &&
                abs(revenueDisplay) > 1e-9
            ) {
                ebitdaDisplay / revenueDisplay
            } else {
                null
            }
            val grossProfitDisplay = stockData.grossProfitDisplay
            val grossMargin = if (grossProfitDisplay != null &&
                revenueDisplay != null &&
                abs(revenueDisplay) > 1e-9
            ) {
                grossProfitDisplay / revenueDisplay
            } else {
                null
            }
            val currentRatio = if (stockData.totalCurrentAssets != null &&
                stockData.totalCurrentLiabilities != null &&
                stockData.totalCurrentLiabilities > 0
            ) {
                stockData.totalCurrentAssets / stockData.totalCurrentLiabilities
            } else {
                null
            }
            return FinancialDerivedMetricsEntity(
                symbol = stockData.symbol,
                marketCapTier = computeMarketCapTier(stockData.marketCap),
                price = stockData.price,
                marketCap = stockData.marketCap,
                peRatio = stockData.peRatio,
                psRatio = stockData.psRatio,
                pbRatio = stockData.pbRatio,
                roe = stockData.roe,
                debtToEquity = stockData.debtToEquity,
                currentRatio = currentRatio,
                netMargin = netMargin,
                ebitdaMargin = ebitdaMargin,
                grossMargin = grossMargin,
                fcfMargin = stockData.freeCashFlowMargin,
                revenueGrowth = stockData.revenueGrowth,
                averageRevenueGrowth = stockData.averageRevenueGrowth,
                averageNetIncomeGrowth = stockData.averageNetIncomeGrowth,
                netIncomeGrowth = stockData.netIncomeGrowth,
                fcfGrowth = stockData.fcfGrowth,
                averageFcfGrowth = stockData.averageFcfGrowth,
                analyzedAt = now,
                lastUpdated = now
            )
        }
    }
}

@Entity(
    tableName = "score_snapshots",
    indices = [Index(value = ["symbol"]), Index(value = ["createdAt"])]
)
data class ScoreSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val modelVersion: String,
    val compositeScore: Int?,
    val healthSubScore: Int?,
    val growthSubScore: Int?,
    val resilienceSubScore: Int?,
    val createdAt: Long
) {
    companion object {
        fun fromHealthScore(symbol: String, healthScore: HealthScore, modelVersion: String): ScoreSnapshotEntity {
            return ScoreSnapshotEntity(
                symbol = symbol,
                modelVersion = modelVersion,
                compositeScore = healthScore.compositeScore,
                healthSubScore = healthScore.healthSubScore,
                growthSubScore = healthScore.forecastSubScore,
                resilienceSubScore = healthScore.zSubScore,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}

data class PeerProfileRow(
    val symbol: String,
    val companyName: String?,
    val sector: String?,
    val industry: String?,
    val price: Double?,
    val marketCap: Double?
)

/** Row for "viewed/analyzed" stocks list (from edgar_raw_facts ordered by last viewed). */
data class ViewedStockRow(
    val symbol: String,
    val companyName: String?,
    val sector: String?,
    val industry: String?,
    val price: Double?,
    val marketCap: Double?,
    val lastUpdated: Long
)

data class SymbolPriceRow(
    val symbol: String,
    val price: Double?
)

/** User's manual holdings or watchlist (no broker connection). */
@Entity(
    tableName = "user_stock_list",
    indices = [Index(value = ["list_type"])]
)
data class UserStockListItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    @ColumnInfo(name = "list_type") val listType: String, // "holding" | "watchlist"
    val shares: Double? = null,
    val avgCost: Double? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/** User-authored business profile / "About" text for a company. */
@Entity(tableName = "company_profiles")
data class CompanyProfileEntity(
    @PrimaryKey val symbol: String,
    val about: String?,
    val updatedAt: Long
)

/**
 * Per-symbol XBRL tag override when [com.example.stockzilla.sec.EdgarConcepts] standard lists miss.
 * Taxonomy: `us-gaap`, `ifrs-full`, or `dei` (must match SEC companyfacts JSON keys under `facts`).
 */
@Entity(
    tableName = "symbol_tag_overrides",
    primaryKeys = ["symbol", "metricKey", "scopeKey"],
    indices = [Index(value = ["symbol"])]
)
data class SymbolTagOverrideEntity(
    val symbol: String,
    val metricKey: String,
    /** Empty = global; quarterly example: FY2024:Q2 */
    val scopeKey: String = "",
    val taxonomy: String,
    val tag: String,
    val updatedAt: Long,
    val source: String
)

/** AI-generated + user-editable business profile for a company. */
@Entity(tableName = "stock_profiles")
data class StockProfileEntity(
    @PrimaryKey val symbol: String,
    val aboutSummary: String?,
    val aboutDetails: String?,
    val generatedAt: Long?,
    val editedByUser: Boolean,
    val updatedAt: Long
)

/**
 * Long-term AI memory notes across three scopes:
 * - STOCK (per symbol)
 * - GROUP (peer group id)
 * - USER (global user preferences)
 */
@Entity(
    tableName = "ai_memory_cache",
    indices = [Index(value = ["scope", "scopeKey"])]
)
data class AiMemoryCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val scopeKey: String,
    val noteType: String,
    val noteText: String,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String
)

/** Saved AI conversations for the assistant screen. */
@Entity(
    tableName = "ai_conversations",
    indices = [Index(value = ["symbol"]), Index(value = ["updatedAt"])]
)
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** Individual messages within a conversation. */
@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = AiConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * Eidos-as-analyst chat: one thread per symbol, persisted separately from [AiMessageEntity] /
 * [AiConversationEntity] (see EIDOS_AS_ANALYST.md).
 */
@Entity(
    tableName = "eidos_analyst_chat_messages",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["symbol", "timestampMs"])
    ]
)
data class EidosAnalystChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val role: String,
    val content: String,
    val timestampMs: Long
)

/**
 * Long-term notes for **Eidos as analyst** only (Full Analysis chat). Separate from [AiMemoryCacheEntity].
 * One row per saved note; scoped by [symbol] (the analyst session ticker).
 */
@Entity(
    tableName = "eidos_analyst_memory_notes",
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["symbol", "updatedAt"])
    ]
)
data class EidosAnalystMemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val noteType: String,
    val noteText: String,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String
)

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites ORDER BY addedDate DESC")
    suspend fun getAllFavorites(): List<FavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE symbol = :symbol")
    suspend fun deleteFavoriteBySymbol(symbol: String)

    @Query("SELECT * FROM favorites WHERE symbol = :symbol")
    suspend fun getFavoriteBySymbol(symbol: String): FavoriteEntity?
}

@Dao
interface StockCacheDao {
    @Query("SELECT * FROM stock_cache WHERE symbol = :symbol")
    suspend fun getCache(symbol: String): StockCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(cache: StockCacheEntity)

    @Query("DELETE FROM stock_cache WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)
}

@Dao
interface EdgarRawFactsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rawFacts: EdgarRawFactsEntity)

    @Query("SELECT * FROM edgar_raw_facts WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): EdgarRawFactsEntity?

    @Query("DELETE FROM edgar_raw_facts WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("""
        SELECT
            r.symbol AS symbol,
            r.companyName AS companyName,
            r.sector AS sector,
            r.industry AS industry,
            d.price AS price,
            d.marketCap AS marketCap
        FROM edgar_raw_facts r
        LEFT JOIN financial_derived_metrics d ON d.symbol = r.symbol
        WHERE r.symbol = :symbol
        LIMIT 1
    """)
    suspend fun getPeerProfileBySymbol(symbol: String): PeerProfileRow?

    @Query("""
        SELECT
            r.symbol AS symbol,
            r.companyName AS companyName,
            r.sector AS sector,
            r.industry AS industry,
            d.price AS price,
            d.marketCap AS marketCap
        FROM edgar_raw_facts r
        LEFT JOIN financial_derived_metrics d ON d.symbol = r.symbol
        WHERE r.naicsCode = :naicsCode
          AND r.symbol != :excludeSymbol
        ORDER BY d.marketCap DESC, r.symbol ASC
    """)
    suspend fun getPeerProfilesByNaics(naicsCode: String, excludeSymbol: String): List<PeerProfileRow>

    @Query("""
        SELECT
            r.symbol AS symbol,
            r.companyName AS companyName,
            r.sector AS sector,
            r.industry AS industry,
            d.price AS price,
            d.marketCap AS marketCap
        FROM edgar_raw_facts r
        LEFT JOIN financial_derived_metrics d ON d.symbol = r.symbol
        WHERE r.sicCode = :sicCode
          AND r.symbol != :excludeSymbol
        ORDER BY d.marketCap DESC, r.symbol ASC
    """)
    suspend fun getPeerProfilesBySic(sicCode: String, excludeSymbol: String): List<PeerProfileRow>

    @Query("""
        SELECT
            r.symbol AS symbol,
            r.companyName AS companyName,
            r.sector AS sector,
            r.industry AS industry,
            d.price AS price,
            d.marketCap AS marketCap
        FROM edgar_raw_facts r
        LEFT JOIN financial_derived_metrics d ON d.symbol = r.symbol
        WHERE r.sector = :sector
        ORDER BY d.marketCap DESC, r.symbol ASC
    """)
    suspend fun getPeerProfilesBySector(sector: String): List<PeerProfileRow>

    /** All stocks ever analyzed, most recently updated first. */
    @Query("""
        SELECT
            r.symbol AS symbol,
            r.companyName AS companyName,
            r.sector AS sector,
            r.industry AS industry,
            d.price AS price,
            d.marketCap AS marketCap,
            r.lastUpdated AS lastUpdated
        FROM edgar_raw_facts r
        LEFT JOIN financial_derived_metrics d ON d.symbol = r.symbol
        ORDER BY r.lastUpdated DESC
    """)
    suspend fun getViewedStocksOrderByLastUpdated(): List<ViewedStockRow>
}

@Dao
interface QuarterlyFinancialFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<QuarterlyFinancialFactEntity>)

    @Query("DELETE FROM quarterly_financial_facts WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query(
        """
        SELECT * FROM quarterly_financial_facts
        WHERE symbol = :symbol
        ORDER BY periodEnd DESC, metricKey ASC
        """
    )
    suspend fun getBySymbol(symbol: String): List<QuarterlyFinancialFactEntity>
}

@Dao
interface FinancialDerivedMetricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(derived: FinancialDerivedMetricsEntity)

    @Query("SELECT * FROM financial_derived_metrics WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): FinancialDerivedMetricsEntity?

    @Query("DELETE FROM financial_derived_metrics WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("SELECT symbol, price FROM financial_derived_metrics WHERE symbol IN (:symbols)")
    suspend fun getPricesBySymbols(symbols: List<String>): List<SymbolPriceRow>

    @Query("""
        SELECT d.* FROM financial_derived_metrics d
        INNER JOIN edgar_raw_facts r ON r.symbol = d.symbol
        WHERE r.sicCode = :sicCode
          AND d.marketCapTier = :capTier
          AND d.peRatio IS NOT NULL AND d.peRatio > 0
    """)
    suspend fun getBenchmarkPeersBySicAndCapTier(sicCode: String, capTier: String): List<FinancialDerivedMetricsEntity>

    @Query("""
        SELECT d.* FROM financial_derived_metrics d
        INNER JOIN edgar_raw_facts r ON r.symbol = d.symbol
        WHERE r.sicCode = :sicCode
          AND d.peRatio IS NOT NULL AND d.peRatio > 0
    """)
    suspend fun getBenchmarkPeersBySic(sicCode: String): List<FinancialDerivedMetricsEntity>

    @Query("""
        SELECT d.* FROM financial_derived_metrics d
        INNER JOIN edgar_raw_facts r ON r.symbol = d.symbol
        WHERE r.sector = :sector
          AND d.peRatio IS NOT NULL AND d.peRatio > 0
    """)
    suspend fun getBenchmarkPeersBySector(sector: String): List<FinancialDerivedMetricsEntity>
}

@Dao
interface ScoreSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: ScoreSnapshotEntity)

    @Query("SELECT * FROM score_snapshots WHERE symbol = :symbol ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(symbol: String): ScoreSnapshotEntity?

    @Query("DELETE FROM score_snapshots WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)
}

/** Saved industry peer list per stock (user can add/remove). */
@Entity(
    tableName = "stock_industry_peers",
    primaryKeys = ["ownerSymbol", "peerSymbol"],
    indices = [Index(value = ["ownerSymbol"])]
)
data class StockIndustryPeerEntity(
    val ownerSymbol: String,
    val peerSymbol: String,
    val addedAt: Long,
    val source: String = "initial" // "initial" | "user_added" | "finnhub"
)

@Dao
interface StockIndustryPeerDao {
    @Query("SELECT * FROM stock_industry_peers WHERE ownerSymbol = :ownerSymbol ORDER BY addedAt ASC")
    suspend fun getPeersForOwner(ownerSymbol: String): List<StockIndustryPeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(peer: StockIndustryPeerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(peers: List<StockIndustryPeerEntity>)

    @Query("DELETE FROM stock_industry_peers WHERE ownerSymbol = :ownerSymbol AND peerSymbol = :peerSymbol")
    suspend fun remove(ownerSymbol: String, peerSymbol: String)

    @Query("DELETE FROM stock_industry_peers WHERE ownerSymbol = :ownerSymbol")
    suspend fun removeAllForOwner(ownerSymbol: String)

    @Query("SELECT COUNT(*) FROM stock_industry_peers WHERE ownerSymbol = :ownerSymbol")
    suspend fun countForOwner(ownerSymbol: String): Int
}

@Dao
interface UserStockListDao {
    /** All holdings; no limit on count. */
    @Query("SELECT * FROM user_stock_list WHERE list_type = 'holding' ORDER BY addedAt ASC")
    suspend fun getAllHoldings(): List<UserStockListItemEntity>

    /** All watchlist items; no limit on count. */
    @Query("SELECT * FROM user_stock_list WHERE list_type = 'watchlist' ORDER BY addedAt ASC")
    suspend fun getAllWatchlist(): List<UserStockListItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: UserStockListItemEntity)

    @Query("DELETE FROM user_stock_list WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM user_stock_list WHERE symbol = :symbol AND list_type = :listType")
    suspend fun deleteBySymbolAndType(symbol: String, listType: String)

    @Query("UPDATE user_stock_list SET shares = :shares, avgCost = :avgCost WHERE id = :id")
    suspend fun updateHoldingValues(id: Long, shares: Double?, avgCost: Double?)
}

/** User-recorded cash adds/withdrawals for the portfolio (for ROI vs cash tracking). */
@Entity(tableName = "portfolio_cash_flows")
data class PortfolioCashFlowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute dollar amount of the cash movement. */
    val amount: Double,
    /** "ADD" for deposit, "WITHDRAW" for withdrawal. */
    val type: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface PortfolioCashFlowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(flow: PortfolioCashFlowEntity)

    @Query("SELECT * FROM portfolio_cash_flows ORDER BY createdAt DESC")
    suspend fun getAll(): List<PortfolioCashFlowEntity>

    @Query("SELECT SUM(CASE WHEN type = 'ADD' THEN amount ELSE -amount END) FROM portfolio_cash_flows")
    suspend fun getNetCash(): Double?
}

/** One row per calendar day: portfolio total value and day-over-day change. */
@Entity(tableName = "portfolio_value_snapshots")
data class PortfolioValueSnapshotEntity(
    /** Start of day in millis (local timezone). */
    @PrimaryKey val dateMs: Long,
    val value: Double,
    /** value minus previous day's value; null if no previous day. */
    val dayChange: Double? = null,
    val recordedAt: Long = System.currentTimeMillis()
)

@Dao
interface PortfolioValueSnapshotDao {
    @Query("SELECT * FROM portfolio_value_snapshots WHERE dateMs = :dateMs")
    suspend fun getByDate(dateMs: Long): PortfolioValueSnapshotEntity?

    @Query("SELECT * FROM portfolio_value_snapshots ORDER BY dateMs DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<PortfolioValueSnapshotEntity>

    @Query("SELECT * FROM portfolio_value_snapshots ORDER BY dateMs ASC LIMIT :limit")
    suspend fun getOldestFirst(limit: Int): List<PortfolioValueSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: PortfolioValueSnapshotEntity)
}

@Dao
interface CompanyProfileDao {
    @Query("SELECT * FROM company_profiles WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): CompanyProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: CompanyProfileEntity)
}

@Dao
interface SymbolTagOverrideDao {
    @Query("SELECT * FROM symbol_tag_overrides WHERE symbol = :symbol")
    suspend fun getAllForSymbol(symbol: String): List<SymbolTagOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SymbolTagOverrideEntity)

    @Query("DELETE FROM symbol_tag_overrides WHERE symbol = :symbol AND metricKey = :metricKey AND scopeKey = :scopeKey")
    suspend fun delete(symbol: String, metricKey: String, scopeKey: String)
}

@Dao
interface StockProfileDao {
    @Query("SELECT * FROM stock_profiles WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): StockProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: StockProfileEntity)
}

@Dao
interface AiMemoryCacheDao {
    @Query(
        """
        SELECT * FROM ai_memory_cache
        WHERE scope = :scope AND scopeKey = :scopeKey
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getNotesForScope(scope: String, scopeKey: String): List<AiMemoryCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: AiMemoryCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notes: List<AiMemoryCacheEntity>)

    @Query("DELETE FROM ai_memory_cache WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AiConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: AiConversationEntity): Long

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    suspend fun getById(id: Long): AiConversationEntity?

    @Query(
        """
        SELECT * FROM ai_conversations
        ORDER BY
            CASE WHEN symbol IS NULL THEN 0 ELSE 1 END,
            updatedAt DESC
        """
    )
    suspend fun getAllOrderedByUpdated(): List<AiConversationEntity>

    @Query(
        """
        SELECT * FROM ai_conversations
        WHERE symbol IS NULL
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getGeneralConversations(): List<AiConversationEntity>

    @Query(
        """
        SELECT * FROM ai_conversations
        WHERE symbol = :symbol
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getBySymbol(symbol: String): List<AiConversationEntity>

    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String, updatedAt: Long)

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AiMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AiMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AiMessageEntity>)

    @Query(
        """
        SELECT * FROM ai_messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp ASC, id ASC
        """
    )
    suspend fun getMessagesForConversation(conversationId: Long): List<AiMessageEntity>

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("DELETE FROM ai_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM ai_messages WHERE id = (
            SELECT id FROM ai_messages WHERE conversationId = :conversationId AND role = 'assistant'
            ORDER BY timestamp DESC, id DESC LIMIT 1
        )
        """
    )
    suspend fun deleteLastAssistantForConversation(conversationId: Long): Int
}

@Dao
interface EidosAnalystChatDao {
    @Insert
    suspend fun insert(message: EidosAnalystChatMessageEntity): Long

    @Query(
        """
        SELECT * FROM eidos_analyst_chat_messages
        WHERE symbol = :symbol
        ORDER BY timestampMs ASC, id ASC
        """
    )
    suspend fun getMessagesForSymbol(symbol: String): List<EidosAnalystChatMessageEntity>

    @Query("DELETE FROM eidos_analyst_chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        DELETE FROM eidos_analyst_chat_messages WHERE id = (
            SELECT id FROM eidos_analyst_chat_messages WHERE symbol = :symbol AND role = 'assistant'
            ORDER BY timestampMs DESC, id DESC LIMIT 1
        )
        """
    )
    suspend fun deleteLastAssistantForSymbol(symbol: String): Int
}

@Dao
interface EidosAnalystMemoryDao {
    @Query(
        """
        SELECT * FROM eidos_analyst_memory_notes
        WHERE symbol = :symbol
        ORDER BY updatedAt DESC, id DESC
        """
    )
    suspend fun getNotesForSymbol(symbol: String): List<EidosAnalystMemoryNoteEntity>

    @Insert
    suspend fun insert(note: EidosAnalystMemoryNoteEntity): Long

    @Query("DELETE FROM eidos_analyst_memory_notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * User-accepted numeric (or display) values from the Eidos analyst proposal flow.
 * Isolated from EDGAR/XBRL refresh tables; automated refresh must never delete these rows.
 */
@Entity(
    tableName = "eidos_analyst_confirmed_facts",
    primaryKeys = ["symbol", "metricKey", "periodLabel"],
    indices = [Index(value = ["symbol"])]
)
data class EidosAnalystConfirmedFactEntity(
    val symbol: String,
    /** Prefer [com.example.stockzilla.sec.EdgarMetricKey.name]. */
    val metricKey: String,
    /** Empty = primary Full Analysis scalar row; non-empty scopes history / proposal period. */
    val periodLabel: String,
    val valueText: String,
    val filingFormType: String?,
    val accessionNumber: String?,
    val filedDate: String?,
    val viewerUrl: String?,
    val primaryDocumentUrl: String?,
    val sourceSnippet: String?,
    val proposalId: String,
    val candidateId: String,
    val candidateLabel: String?,
    val confirmedAtMs: Long,
)

@Dao
interface EidosAnalystConfirmedFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: EidosAnalystConfirmedFactEntity)

    @Query("SELECT * FROM eidos_analyst_confirmed_facts WHERE symbol = :symbol")
    suspend fun getAllForSymbol(symbol: String): List<EidosAnalystConfirmedFactEntity>

    @Query(
        """
        DELETE FROM eidos_analyst_confirmed_facts
        WHERE symbol = :symbol AND metricKey = :metricKey AND periodLabel = :periodLabel
        """
    )
    suspend fun deleteByKey(symbol: String, metricKey: String, periodLabel: String)
}

/**
 * Append-only audit trail for analyst proposal actions (accept / decline).
 */
@Entity(
    tableName = "eidos_analyst_audit_events",
    indices = [Index(value = ["symbol"]), Index(value = ["symbol", "createdAtMs"])]
)
data class EidosAnalystAuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /** e.g. PROPOSAL_ACCEPTED, PROPOSAL_DECLINED */
    val eventType: String,
    val proposalId: String?,
    val metricKey: String?,
    val candidateId: String?,
    val detail: String?,
    val createdAtMs: Long,
)

@Dao
interface EidosAnalystAuditDao {
    @Insert
    suspend fun insert(event: EidosAnalystAuditEventEntity): Long

    @Query(
        """
        SELECT * FROM eidos_analyst_audit_events
        WHERE symbol = :symbol
        ORDER BY createdAtMs DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentForSymbol(symbol: String, limit: Int = 200): List<EidosAnalystAuditEventEntity>
}

// ==================== News Metadata Entity (Stage 1 — no AI data) ====================

@Entity(
    tableName = "news_metadata",
    primaryKeys = ["symbol", "accessionNumber"],
    indices = [Index(value = ["symbol"], name = "idx_news_meta_symbol")]
)
data class NewsMetadataEntity(
    val symbol: String,
    val cik: String,
    val accessionNumber: String,
    val filingDate: String,
    val formType: String,
    val primaryDocument: String?,
    val itemsRaw: String?,
    /** Comma-separated normalized item numbers, e.g. "1.01,2.02,5.02" */
    val normalizedItems: String?,
    val secFolderUrl: String,
    /** PENDING | ANALYZED | FAILED */
    val analysisStatus: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface NewsMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NewsMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<NewsMetadataEntity>)

    /** Inserts only rows that don't already exist (by PK). Existing rows keep their analysisStatus. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewOnly(entities: List<NewsMetadataEntity>)

    @Query("SELECT * FROM news_metadata WHERE symbol = :symbol ORDER BY filingDate DESC")
    suspend fun getForSymbol(symbol: String): List<NewsMetadataEntity>

    @Query(
        """SELECT * FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'PENDING'
           ORDER BY filingDate DESC"""
    )
    suspend fun getPendingForSymbol(symbol: String): List<NewsMetadataEntity>

    @Query(
        """SELECT filingDate FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'ANALYZED'
           ORDER BY filingDate DESC LIMIT 1"""
    )
    suspend fun getNewestAnalyzedDate(symbol: String): String?

    @Query(
        """SELECT COUNT(*) FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'PENDING'
           AND filingDate > :afterDate"""
    )
    suspend fun countPendingNewerThan(symbol: String, afterDate: String): Int

    @Query(
        """SELECT COUNT(*) FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'PENDING'"""
    )
    suspend fun countAllPending(symbol: String): Int

    @Query(
        """SELECT * FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'PENDING'
           AND filingDate > :afterDate
           ORDER BY filingDate DESC LIMIT :limit"""
    )
    suspend fun getPendingNewerThan(symbol: String, afterDate: String, limit: Int): List<NewsMetadataEntity>

    @Query(
        """SELECT * FROM news_metadata
           WHERE symbol = :symbol AND analysisStatus = 'PENDING'
           ORDER BY filingDate DESC LIMIT :limit"""
    )
    suspend fun getTopPending(symbol: String, limit: Int): List<NewsMetadataEntity>

    @Query("UPDATE news_metadata SET analysisStatus = :status, updatedAt = :updatedAt WHERE symbol = :symbol AND accessionNumber = :accessionNumber")
    suspend fun updateStatus(symbol: String, accessionNumber: String, status: String, updatedAt: Long)
}

// ==================== News Summary Entity (Stage 2 — Eidos outputs only) ====================

@Entity(
    tableName = "news_summaries",
    primaryKeys = ["symbol", "accessionNumber"],
    indices = [Index(value = ["symbol"], name = "idx_news_summaries_symbol")]
)
data class NewsSummaryEntity(
    val symbol: String,
    val cik: String,
    val accessionNumber: String,
    val filingDate: String,
    val title: String?,
    val shortSummary: String,
    val detailedSummary: String,
    val impact: String,
    /** JSON array of catalyst strings */
    val catalystsJson: String,
    /** Comma-separated normalized item numbers */
    val normalizedItems: String?,
    val secUrl: String,
    val createdAt: Long,
    val lastAnalyzedAt: Long
)

/**
 * Lightweight join projection used to display form labels in the UI.
 * We keep the `news_summaries` table schema unchanged and read `formType` from `news_metadata`.
 */
data class NewsSummaryWithFormTypeRow(
    val symbol: String,
    val cik: String,
    val accessionNumber: String,
    val filingDate: String,
    val title: String?,
    val shortSummary: String,
    val detailedSummary: String,
    val impact: String,
    val catalystsJson: String,
    val normalizedItems: String?,
    val secUrl: String,
    val createdAt: Long,
    val lastAnalyzedAt: Long,
    val formType: String
)

@Dao
interface NewsSummariesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NewsSummaryEntity)

    @Query(
        """SELECT * FROM news_summaries
           WHERE symbol = :symbol
           ORDER BY filingDate DESC
           LIMIT :limit"""
    )
    suspend fun getRecentForSymbol(symbol: String, limit: Int = 3): List<NewsSummaryEntity>

    @Query(
        """
        SELECT
            s.symbol,
            s.cik,
            s.accessionNumber,
            s.filingDate,
            s.title,
            s.shortSummary,
            s.detailedSummary,
            s.impact,
            s.catalystsJson,
            s.normalizedItems,
            s.secUrl,
            s.createdAt,
            s.lastAnalyzedAt,
            m.formType AS formType
        FROM news_summaries s
        INNER JOIN news_metadata m
            ON m.symbol = s.symbol AND m.accessionNumber = s.accessionNumber
        WHERE s.symbol = :symbol
        ORDER BY s.filingDate DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentForSymbolWithFormType(symbol: String, limit: Int = 3): List<NewsSummaryWithFormTypeRow>

    @Query("DELETE FROM news_summaries WHERE symbol = :symbol")
    suspend fun deleteForSymbol(symbol: String)
}

// ==================== Database ====================

@Database(
    entities = [
        FavoriteEntity::class,
        StockCacheEntity::class,
        StockIndustryPeerEntity::class,
        EdgarRawFactsEntity::class,
        QuarterlyFinancialFactEntity::class,
        FinancialDerivedMetricsEntity::class,
        ScoreSnapshotEntity::class,
        UserStockListItemEntity::class,
        CompanyProfileEntity::class,
        StockProfileEntity::class,
        AiMemoryCacheEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        EidosAnalystChatMessageEntity::class,
        EidosAnalystMemoryNoteEntity::class,
        EidosAnalystConfirmedFactEntity::class,
        EidosAnalystAuditEventEntity::class,
        PortfolioValueSnapshotEntity::class,
        PortfolioCashFlowEntity::class,
        NewsMetadataEntity::class,
        NewsSummaryEntity::class,
        SymbolTagOverrideEntity::class
    ],
    version = 35,
    exportSchema = false
)
@TypeConverters(DoubleListConverter::class)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun stockCacheDao(): StockCacheDao
    abstract fun stockIndustryPeerDao(): StockIndustryPeerDao
    abstract fun edgarRawFactsDao(): EdgarRawFactsDao
    abstract fun quarterlyFinancialFactDao(): QuarterlyFinancialFactDao
    abstract fun financialDerivedMetricsDao(): FinancialDerivedMetricsDao
    abstract fun scoreSnapshotDao(): ScoreSnapshotDao
    abstract fun userStockListDao(): UserStockListDao
    abstract fun companyProfileDao(): CompanyProfileDao
    abstract fun symbolTagOverrideDao(): SymbolTagOverrideDao
    abstract fun stockProfileDao(): StockProfileDao
        abstract fun aiMemoryCacheDao(): AiMemoryCacheDao
        abstract fun aiConversationDao(): AiConversationDao
        abstract fun aiMessageDao(): AiMessageDao
        abstract fun eidosAnalystChatDao(): EidosAnalystChatDao
        abstract fun eidosAnalystMemoryDao(): EidosAnalystMemoryDao
        abstract fun eidosAnalystConfirmedFactDao(): EidosAnalystConfirmedFactDao
        abstract fun eidosAnalystAuditDao(): EidosAnalystAuditDao
        abstract fun portfolioValueSnapshotDao(): PortfolioValueSnapshotDao
        abstract fun portfolioCashFlowDao(): PortfolioCashFlowDao
        abstract fun newsMetadataDao(): NewsMetadataDao
        abstract fun newsSummariesDao(): NewsSummariesDao

    companion object {
        @Volatile
        private var INSTANCE: StockzillaDatabase? = null

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Phase 2.8: remove legacy mixed-domain table after separated domains are live.
                db.execSQL("DROP TABLE IF EXISTS analyzed_stocks")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE financial_derived_metrics ADD COLUMN netIncomeGrowth REAL")
                db.execSQL("ALTER TABLE financial_derived_metrics ADD COLUMN fcfGrowth REAL")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE financial_derived_metrics ADD COLUMN averageFcfGrowth REAL")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN revenueHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN netIncomeHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN ebitdaHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN operatingCashFlowHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN freeCashFlowHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN sharesOutstandingHistoryJson TEXT")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN grossProfit REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN grossProfitHistoryJson TEXT")
                db.execSQL("ALTER TABLE financial_derived_metrics ADD COLUMN grossMargin REAL")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN costOfGoodsSold REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN costOfGoodsSoldTtm REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN costOfGoodsSoldHistoryJson TEXT")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN totalAssetsHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN totalCurrentAssetsHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN totalCurrentLiabilitiesHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN longTermDebtHistoryJson TEXT")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS company_profiles (
                        symbol TEXT NOT NULL PRIMARY KEY,
                        about TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eight_k_metadata (
                        symbol TEXT NOT NULL,
                        cik TEXT NOT NULL,
                        accessionNumber TEXT NOT NULL,
                        filingDate TEXT NOT NULL,
                        formType TEXT NOT NULL,
                        primaryDocument TEXT,
                        itemsRaw TEXT,
                        normalizedItems TEXT,
                        secFolderUrl TEXT NOT NULL,
                        analysisStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(symbol, accessionNumber)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_8k_meta_symbol ON eight_k_metadata(symbol)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eight_k_news (
                        symbol TEXT NOT NULL,
                        cik TEXT NOT NULL,
                        accessionNumber TEXT NOT NULL,
                        filingDate TEXT NOT NULL,
                        title TEXT,
                        shortSummary TEXT NOT NULL,
                        detailedSummary TEXT NOT NULL,
                        impact TEXT NOT NULL,
                        catalystsJson TEXT NOT NULL,
                        normalizedItems TEXT,
                        secUrl TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastAnalyzedAt INTEGER NOT NULL,
                        PRIMARY KEY(symbol, accessionNumber)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_8k_news_symbol ON eight_k_news(symbol)"
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename existing tables created under the old "8-K" naming.
                try {
                    db.execSQL("ALTER TABLE eight_k_metadata RENAME TO news_metadata")
                } catch (_: Exception) {
                    // ignore
                }
                try {
                    db.execSQL("ALTER TABLE eight_k_news RENAME TO news_summaries")
                } catch (_: Exception) {
                    // ignore
                }

                // Rebuild indices with the new names.
                try {
                    db.execSQL("DROP INDEX IF EXISTS idx_8k_meta_symbol")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_meta_symbol ON news_metadata(symbol)")
                } catch (_: Exception) {
                    // ignore
                }
                try {
                    db.execSQL("DROP INDEX IF EXISTS idx_8k_news_symbol")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_summaries_symbol ON news_summaries(symbol)")
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS symbol_tag_overrides (
                        symbol TEXT NOT NULL,
                        metricKey TEXT NOT NULL,
                        taxonomy TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        PRIMARY KEY(symbol, metricKey)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_symbol_tag_overrides_symbol ON symbol_tag_overrides(symbol)"
                )
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN totalDebt REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN cashAndEquivalents REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN accountsReceivable REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN operatingIncome REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN operatingIncomeTtm REAL")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN totalDebtHistoryJson TEXT")
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN capex REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN capexTtm REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN depreciationAmortization REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN depreciationAmortizationTtm REAL")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN capexHistoryJson TEXT")
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN depreciationAmortizationHistoryJson TEXT")
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS symbol_tag_overrides_new (
                        symbol TEXT NOT NULL,
                        metricKey TEXT NOT NULL,
                        scopeKey TEXT NOT NULL DEFAULT '',
                        taxonomy TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        PRIMARY KEY(symbol, metricKey, scopeKey)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO symbol_tag_overrides_new (symbol, metricKey, scopeKey, taxonomy, tag, updatedAt, source)
                    SELECT symbol, metricKey, '', taxonomy, tag, updatedAt, source FROM symbol_tag_overrides
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE symbol_tag_overrides")
                db.execSQL("ALTER TABLE symbol_tag_overrides_new RENAME TO symbol_tag_overrides")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_symbol_tag_overrides_symbol ON symbol_tag_overrides(symbol)"
                )
                db.execSQL("ALTER TABLE quarterly_financial_facts ADD COLUMN accessionNumber TEXT")
            }
        }

        /**
         * Keeps installs working after a brief v30 build shipped (e.g. experimental tables since removed).
         * Forward path 29→30: no SQL required for the current entity set.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop legacy analyst tables if present (harmless no-op when upgrading from 29).
                dropLegacyAnalystTables(db)
            }
        }

        /**
         * v30 DBs from the reverted "Eidos analyst" Room schema stored a different identity hash than
         * the current entity list; re-opening at v30 failed integrity checks. Bumping to 31 and
         * dropping any leftover analyst tables realigns the file with the shipped schema.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                dropLegacyAnalystTables(db)
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_analyst_chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestampMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_chat_messages_symbol ON eidos_analyst_chat_messages(symbol)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_chat_messages_symbol_timestampMs ON eidos_analyst_chat_messages(symbol, timestampMs)"
                )
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_analyst_confirmed_facts (
                        symbol TEXT NOT NULL,
                        metricKey TEXT NOT NULL,
                        periodLabel TEXT NOT NULL,
                        valueText TEXT NOT NULL,
                        filingFormType TEXT,
                        accessionNumber TEXT,
                        filedDate TEXT,
                        viewerUrl TEXT,
                        primaryDocumentUrl TEXT,
                        sourceSnippet TEXT,
                        proposalId TEXT NOT NULL,
                        candidateId TEXT NOT NULL,
                        candidateLabel TEXT,
                        confirmedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(symbol, metricKey, periodLabel)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_confirmed_facts_symbol ON eidos_analyst_confirmed_facts(symbol)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_analyst_audit_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        proposalId TEXT,
                        metricKey TEXT,
                        candidateId TEXT,
                        detail TEXT,
                        createdAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_audit_events_symbol ON eidos_analyst_audit_events(symbol)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_audit_events_symbol_createdAtMs ON eidos_analyst_audit_events(symbol, createdAtMs)"
                )
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edgar_raw_facts ADD COLUMN operatingIncomeHistoryJson TEXT")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS eidos_analyst_memory_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT NOT NULL,
                        noteType TEXT NOT NULL,
                        noteText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        source TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_memory_notes_symbol ON eidos_analyst_memory_notes(symbol)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_eidos_analyst_memory_notes_symbol_updatedAt ON eidos_analyst_memory_notes(symbol, updatedAt)"
                )
            }
        }

        private fun dropLegacyAnalystTables(db: SupportSQLiteDatabase) {
            // snake_case names (hand-authored SQL / docs)
            db.execSQL("DROP TABLE IF EXISTS analyst_messages")
            db.execSQL("DROP TABLE IF EXISTS analyst_confirmed_facts")
            db.execSQL("DROP TABLE IF EXISTS analyst_conversations")
            // Default Room table names when @Entity had no tableName
            db.execSQL("DROP TABLE IF EXISTS AnalystMessageEntity")
            db.execSQL("DROP TABLE IF EXISTS AnalystConversationEntity")
            db.execSQL("DROP TABLE IF EXISTS AnalystConfirmedFactEntity")
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quarterly_financial_facts (
                        symbol TEXT NOT NULL,
                        metricKey TEXT NOT NULL,
                        periodStart TEXT,
                        periodEnd TEXT NOT NULL,
                        fiscalYear INTEGER,
                        fiscalPeriod TEXT,
                        form TEXT,
                        unit TEXT NOT NULL,
                        taxonomy TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        value REAL NOT NULL,
                        filedDate TEXT,
                        lastUpdated INTEGER NOT NULL,
                        PRIMARY KEY(symbol, metricKey, periodEnd)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_quarterly_financial_facts_symbol ON quarterly_financial_facts(symbol)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_quarterly_financial_facts_symbol_periodEnd ON quarterly_financial_facts(symbol, periodEnd)"
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS portfolio_cash_flows (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        amount REAL NOT NULL,
                        type TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS portfolio_value_snapshots (
                        dateMs INTEGER PRIMARY KEY NOT NULL,
                        value REAL NOT NULL,
                        dayChange REAL,
                        recordedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_profiles (
                        symbol TEXT NOT NULL PRIMARY KEY,
                        aboutSummary TEXT,
                        aboutDetails TEXT,
                        generatedAt INTEGER,
                        editedByUser INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_memory_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scope TEXT NOT NULL,
                        scopeKey TEXT NOT NULL,
                        noteType TEXT NOT NULL,
                        noteText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        source TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_memory_cache_scope_scopeKey ON ai_memory_cache(scope, scopeKey)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_conversations_symbol ON ai_conversations(symbol)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_conversations_updatedAt ON ai_conversations(updatedAt)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        FOREIGN KEY(conversationId) REFERENCES ai_conversations(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_messages_conversationId ON ai_messages(conversationId)"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_stock_list (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        symbol TEXT NOT NULL,
                        list_type TEXT NOT NULL,
                        shares REAL,
                        avgCost REAL,
                        addedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_stock_list_listType ON user_stock_list(list_type)")
            }
        }

        fun getDatabase(context: Context): StockzillaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockzillaDatabase::class.java,
                    "stockzilla_database"
                )
                    .addMigrations(
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class FavoritesRepository(private val favoritesDao: FavoritesDao) {

    suspend fun getAllFavorites(): List<StockData> {
        return favoritesDao.getAllFavorites().map { entity ->
            StockData(
                symbol = entity.symbol,
                companyName = entity.companyName,
                price = entity.price,
                marketCap = entity.marketCap,
                revenue = entity.revenue,
                netIncome = entity.netIncome,
                eps = entity.eps,
                peRatio = entity.peRatio,
                psRatio = entity.psRatio,
                roe = entity.roe,
                debtToEquity = entity.debtToEquity,
                freeCashFlow = entity.freeCashFlow,
                pbRatio = entity.pbRatio,
                ebitda = entity.ebitda,
                outstandingShares = entity.outstandingShares,
                totalAssets = entity.totalAssets,
                totalLiabilities = entity.totalLiabilities,
                totalCurrentAssets = entity.totalCurrentAssets,
                totalCurrentLiabilities = entity.totalCurrentLiabilities,
                retainedEarnings = entity.retainedEarnings,
                netCashProvidedByOperatingActivities = entity.netCashProvidedByOperatingActivities,
                workingCapital = entity.workingCapital,
                sector = entity.sector,
                industry = entity.industry
            )
        }
    }

    suspend fun addFavorite(stockData: StockData, healthScore: Int? = null) {
        val favorite = FavoriteEntity(
            symbol = stockData.symbol,
            companyName = stockData.companyName,
            price = stockData.price,
            marketCap = stockData.marketCap,
            revenue = stockData.revenue,
            netIncome = stockData.netIncome,
            eps = stockData.eps,
            peRatio = stockData.peRatio,
            psRatio = stockData.psRatio,
            roe = stockData.roe,
            debtToEquity = stockData.debtToEquity,
            freeCashFlow = stockData.freeCashFlow,
            pbRatio = stockData.pbRatio,
            ebitda = stockData.ebitda,
            outstandingShares = stockData.outstandingShares,
            totalAssets = stockData.totalAssets,
            totalLiabilities = stockData.totalLiabilities,
            totalCurrentAssets = stockData.totalCurrentAssets,
            totalCurrentLiabilities = stockData.totalCurrentLiabilities,
            retainedEarnings = stockData.retainedEarnings,
            workingCapital = stockData.workingCapital,
            netCashProvidedByOperatingActivities = stockData.netCashProvidedByOperatingActivities,
            sector = stockData.sector,
            industry = stockData.industry,
            healthScore = healthScore,
            addedDate = System.currentTimeMillis(),
            lastUpdated = System.currentTimeMillis(),
            notes = null
        )
        favoritesDao.insertFavorite(favorite)
    }

    suspend fun updateFavoriteData(stockData: StockData, healthScore: Int? = null) {
        val existing = favoritesDao.getFavoriteBySymbol(stockData.symbol)
        if (existing != null) {
            val updated = existing.copy(
                companyName = stockData.companyName,
                price = stockData.price,
                marketCap = stockData.marketCap,
                revenue = stockData.revenue,
                netIncome = stockData.netIncome,
                eps = stockData.eps,
                peRatio = stockData.peRatio,
                psRatio = stockData.psRatio,
                roe = stockData.roe,
                debtToEquity = stockData.debtToEquity,
                freeCashFlow = stockData.freeCashFlow,
                pbRatio = stockData.pbRatio,
                ebitda = stockData.ebitda,
                outstandingShares = stockData.outstandingShares,
                totalAssets = stockData.totalAssets,
                totalLiabilities = stockData.totalLiabilities,
                totalCurrentAssets = stockData.totalCurrentAssets,
                totalCurrentLiabilities = stockData.totalCurrentLiabilities,
                retainedEarnings = stockData.retainedEarnings,
                workingCapital = stockData.workingCapital,
                netCashProvidedByOperatingActivities = stockData.netCashProvidedByOperatingActivities,
                sector = stockData.sector,
                industry = stockData.industry,
                healthScore = healthScore,
                lastUpdated = System.currentTimeMillis()
            )
            favoritesDao.insertFavorite(updated)
        }
    }

    suspend fun removeFavorite(symbol: String) {
        favoritesDao.deleteFavoriteBySymbol(symbol)
    }

    suspend fun isFavorite(symbol: String): Boolean {
        return favoritesDao.getFavoriteBySymbol(symbol) != null
    }
}
