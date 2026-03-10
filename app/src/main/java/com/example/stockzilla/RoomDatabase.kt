package com.example.stockzilla

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val revenueHistoryJson: List<Double?>?,
    val netIncomeHistoryJson: List<Double?>?,
    val ebitdaHistoryJson: List<Double?>?,
    val costOfGoodsSoldHistoryJson: List<Double?>?,
    val grossProfitHistoryJson: List<Double?>?,
    val operatingCashFlowHistoryJson: List<Double?>?,
    val freeCashFlowHistoryJson: List<Double?>?,
    val sharesOutstandingHistoryJson: List<Double?>?,
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
                revenueHistoryJson = stockData.revenueHistory.takeIf { it.isNotEmpty() },
                netIncomeHistoryJson = stockData.netIncomeHistory.takeIf { it.isNotEmpty() },
                ebitdaHistoryJson = stockData.ebitdaHistory.takeIf { it.isNotEmpty() },
                costOfGoodsSoldHistoryJson = stockData.costOfGoodsSoldHistory.takeIf { it.isNotEmpty() },
                grossProfitHistoryJson = stockData.grossProfitHistory.takeIf { it.isNotEmpty() },
                operatingCashFlowHistoryJson = stockData.operatingCashFlowHistory?.takeIf { it.isNotEmpty() },
                freeCashFlowHistoryJson = stockData.freeCashFlowHistory?.takeIf { it.isNotEmpty() },
                sharesOutstandingHistoryJson = stockData.sharesOutstandingHistory?.takeIf { it.isNotEmpty() },
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
            freeCashFlowHistory = freeCashFlowHistoryJson,
            sharesOutstandingHistory = sharesOutstandingHistoryJson,
            revenueTtm = revenueTtm,
            netIncomeTtm = netIncomeTtm,
            epsTtm = epsTtm,
            ebitdaTtm = ebitdaTtm,
            costOfGoodsSoldTtm = costOfGoodsSoldTtm,
            freeCashFlowTtm = freeCashFlowTtm,
            operatingCashFlowTtm = operatingCashFlowTtm
        )
    }
}

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
                kotlin.math.abs(revenueDisplay) > 1e-9
            ) {
                netIncomeDisplay / revenueDisplay
            } else {
                null
            }
            val ebitdaMargin = if (ebitdaDisplay != null &&
                revenueDisplay != null &&
                kotlin.math.abs(revenueDisplay) > 1e-9
            ) {
                ebitdaDisplay / revenueDisplay
            } else {
                null
            }
            val grossProfitDisplay = stockData.grossProfitDisplay
            val grossMargin = if (grossProfitDisplay != null &&
                revenueDisplay != null &&
                kotlin.math.abs(revenueDisplay) > 1e-9
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

@Database(
    entities = [
        FavoriteEntity::class,
        StockCacheEntity::class,
        StockIndustryPeerEntity::class,
        EdgarRawFactsEntity::class,
        FinancialDerivedMetricsEntity::class,
        ScoreSnapshotEntity::class,
        UserStockListItemEntity::class
    ],
    version = 17,
    exportSchema = false
)
@TypeConverters(DoubleListConverter::class)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun stockCacheDao(): StockCacheDao
    abstract fun stockIndustryPeerDao(): StockIndustryPeerDao
    abstract fun edgarRawFactsDao(): EdgarRawFactsDao
    abstract fun financialDerivedMetricsDao(): FinancialDerivedMetricsDao
    abstract fun scoreSnapshotDao(): ScoreSnapshotDao
    abstract fun userStockListDao(): UserStockListDao

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
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                    .fallbackToDestructiveMigration(true)
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
