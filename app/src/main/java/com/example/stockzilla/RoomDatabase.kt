package com.example.stockzilla

import android.content.Context
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(
    tableName = "analyzed_stocks",
    indices = [
        Index(value = ["sicCode"]),
        Index(value = ["naicsCode"]),
        Index(value = ["sector"]),
        Index(value = ["marketCapTier"])
    ]
)
data class AnalyzedStockEntity(
    @PrimaryKey val symbol: String,
    val companyName: String?,
    val sicCode: String?,
    val naicsCode: String?,
    val sector: String?,
    val industry: String?,
    val marketCapTier: String?,
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
    val operatingCashFlow: Double?,
    val grossProfit: Double?,
    val operatingIncome: Double?,
    val revenueGrowth: Double?,
    val netIncomeGrowth: Double?,
    val fcfMargin: Double?,
    val grossMargin: Double?,
    val netMargin: Double?,
    val ebitdaMargin: Double?,
    val currentRatio: Double?,
    val compositeScore: Int?,
    val healthSubScore: Int?,
    val growthSubScore: Int?,
    val resilienceSubScore: Int?,
    val altmanZScore: Double?,
    val lastFilingDate: String?,
    val analyzedAt: Long,
    val lastUpdated: Long
) {
    companion object {
        fun computeMarketCapTier(marketCap: Double?): String? {
            if (marketCap == null) return null
            return when {
                marketCap < 300_000_000 -> "micro"
                marketCap < 2_000_000_000 -> "small"
                marketCap < 10_000_000_000 -> "mid"
                marketCap < 200_000_000_000 -> "large"
                else -> "mega"
            }
        }

        fun fromStockData(
            stockData: StockData,
            sicCode: String?,
            naicsCode: String? = null,
            healthScore: HealthScore?,
            lastFilingDate: String?
        ): AnalyzedStockEntity {
            val revenue = stockData.revenue
            val netIncome = stockData.netIncome
            val ebitda = stockData.ebitda
            val freeCashFlow = stockData.freeCashFlow
            val totalAssets = stockData.totalAssets
            val currentAssets = stockData.totalCurrentAssets
            val currentLiabilities = stockData.totalCurrentLiabilities
            val grossProfit: Double? = null

            return AnalyzedStockEntity(
                symbol = stockData.symbol,
                companyName = stockData.companyName,
                sicCode = sicCode,
                naicsCode = naicsCode,
                sector = stockData.sector,
                industry = stockData.industry,
                marketCapTier = computeMarketCapTier(stockData.marketCap),
                price = stockData.price,
                marketCap = stockData.marketCap,
                revenue = revenue,
                netIncome = netIncome,
                eps = stockData.eps,
                peRatio = stockData.peRatio,
                psRatio = stockData.psRatio,
                roe = stockData.roe,
                debtToEquity = stockData.debtToEquity,
                freeCashFlow = freeCashFlow,
                pbRatio = stockData.pbRatio,
                ebitda = ebitda,
                outstandingShares = stockData.outstandingShares,
                totalAssets = totalAssets,
                totalLiabilities = stockData.totalLiabilities,
                totalCurrentAssets = currentAssets,
                totalCurrentLiabilities = currentLiabilities,
                retainedEarnings = stockData.retainedEarnings,
                workingCapital = stockData.workingCapital,
                operatingCashFlow = stockData.netCashProvidedByOperatingActivities,
                grossProfit = grossProfit,
                operatingIncome = stockData.ebitda,
                revenueGrowth = stockData.revenueGrowth,
                netIncomeGrowth = stockData.averageNetIncomeGrowth,
                fcfMargin = stockData.freeCashFlowMargin,
                grossMargin = null,
                netMargin = if (netIncome != null && revenue != null && kotlin.math.abs(revenue) > 1e-9) netIncome / revenue else null,
                ebitdaMargin = if (ebitda != null && revenue != null && kotlin.math.abs(revenue) > 1e-9) ebitda / revenue else null,
                currentRatio = if (currentAssets != null && currentLiabilities != null && currentLiabilities > 0) currentAssets / currentLiabilities else null,
                compositeScore = healthScore?.compositeScore,
                healthSubScore = healthScore?.healthSubScore,
                growthSubScore = healthScore?.forecastSubScore,
                resilienceSubScore = healthScore?.zSubScore,
                altmanZScore = null,
                lastFilingDate = lastFilingDate,
                analyzedAt = System.currentTimeMillis(),
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    fun toStockData(): StockData {
        return StockData(
            symbol = symbol,
            companyName = companyName,
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
            sector = sector,
            industry = industry,
            sicCode = sicCode,
            revenueGrowth = revenueGrowth,
            averageRevenueGrowth = revenueGrowth,
            averageNetIncomeGrowth = netIncomeGrowth,
            freeCashFlowMargin = fcfMargin,
            ebitdaMarginGrowth = null
        )
    }
}

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
interface AnalyzedStockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(stock: AnalyzedStockEntity)

    @Query("SELECT * FROM analyzed_stocks WHERE symbol = :symbol")
    suspend fun getStock(symbol: String): AnalyzedStockEntity?

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND marketCapTier = :capTier
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersByPeGroup(sicCode: String, capTier: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersBySic(sicCode: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sector = :sector
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersBySector(sector: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND symbol != :excludeSymbol
        ORDER BY compositeScore DESC
    """)
    suspend fun getSimilarStocks(sicCode: String, excludeSymbol: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT COUNT(*) FROM analyzed_stocks 
        WHERE sicCode = :sicCode 
        AND marketCapTier = :capTier
    """)
    suspend fun countPeersInGroup(sicCode: String, capTier: String): Int

    @Query("SELECT * FROM analyzed_stocks ORDER BY lastUpdated DESC")
    suspend fun getAllStocks(): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE lastUpdated < :cutoff
        ORDER BY lastUpdated ASC
        LIMIT :limit
    """)
    suspend fun getStaleStocks(cutoff: Long, limit: Int): List<AnalyzedStockEntity>

    // NAICS-based peer grouping
    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE naicsCode = :naicsCode
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersByNaics(naicsCode: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE naicsCode = :naicsCode
        AND marketCapTier = :capTier
        AND peRatio IS NOT NULL AND peRatio > 0
    """)
    suspend fun getPeersByNaicsAndCapTier(naicsCode: String, capTier: String): List<AnalyzedStockEntity>

    @Query("""
        SELECT * FROM analyzed_stocks 
        WHERE naicsCode = :naicsCode 
        AND symbol != :excludeSymbol
        ORDER BY compositeScore DESC
    """)
    suspend fun getSimilarStocksByNaics(naicsCode: String, excludeSymbol: String): List<AnalyzedStockEntity>
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

@Database(
    entities = [FavoriteEntity::class, StockCacheEntity::class, AnalyzedStockEntity::class, StockIndustryPeerEntity::class],
    version = 7,
    exportSchema = false
)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun stockCacheDao(): StockCacheDao
    abstract fun analyzedStockDao(): AnalyzedStockDao
    abstract fun stockIndustryPeerDao(): StockIndustryPeerDao

    companion object {
        @Volatile
        private var INSTANCE: StockzillaDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN totalCurrentAssets REAL")
                db.execSQL("ALTER TABLE favorites ADD COLUMN totalCurrentLiabilities REAL")
                db.execSQL("ALTER TABLE favorites ADD COLUMN retainedEarnings REAL")
                db.execSQL("ALTER TABLE favorites ADD COLUMN workingCapital REAL")
                db.execSQL("ALTER TABLE favorites ADD COLUMN netCashProvidedByOperatingActivities REAL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `analyzed_stocks` (
                        `symbol` TEXT NOT NULL PRIMARY KEY,
                        `companyName` TEXT,
                        `sicCode` TEXT,
                        `sector` TEXT,
                        `industry` TEXT,
                        `marketCapTier` TEXT,
                        `price` REAL,
                        `marketCap` REAL,
                        `revenue` REAL,
                        `netIncome` REAL,
                        `eps` REAL,
                        `peRatio` REAL,
                        `psRatio` REAL,
                        `roe` REAL,
                        `debtToEquity` REAL,
                        `freeCashFlow` REAL,
                        `pbRatio` REAL,
                        `ebitda` REAL,
                        `outstandingShares` REAL,
                        `totalAssets` REAL,
                        `totalLiabilities` REAL,
                        `totalCurrentAssets` REAL,
                        `totalCurrentLiabilities` REAL,
                        `retainedEarnings` REAL,
                        `workingCapital` REAL,
                        `operatingCashFlow` REAL,
                        `grossProfit` REAL,
                        `operatingIncome` REAL,
                        `revenueGrowth` REAL,
                        `netIncomeGrowth` REAL,
                        `fcfMargin` REAL,
                        `grossMargin` REAL,
                        `netMargin` REAL,
                        `ebitdaMargin` REAL,
                        `currentRatio` REAL,
                        `compositeScore` INTEGER,
                        `healthSubScore` INTEGER,
                        `growthSubScore` INTEGER,
                        `resilienceSubScore` INTEGER,
                        `altmanZScore` REAL,
                        `lastFilingDate` TEXT,
                        `analyzedAt` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_stocks_sicCode` ON `analyzed_stocks` (`sicCode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_stocks_sector` ON `analyzed_stocks` (`sector`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_analyzed_stocks_marketCapTier` ON `analyzed_stocks` (`marketCapTier`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analyzed_stocks ADD COLUMN naicsCode TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analyzed_stocks_naicsCode ON analyzed_stocks (naicsCode)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS stock_industry_peers (
                        ownerSymbol TEXT NOT NULL,
                        peerSymbol TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'initial',
                        PRIMARY KEY (ownerSymbol, peerSymbol)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_industry_peers_ownerSymbol ON stock_industry_peers (ownerSymbol)")
            }
        }

        fun getDatabase(context: Context): StockzillaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockzillaDatabase::class.java,
                    "stockzilla_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
