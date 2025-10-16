// RoomDatabase.kt - Updated to store complete financial data
package com.example.stockzilla

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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

@Database(
    entities = [FavoriteEntity::class, StockCacheEntity::class],
    version = 4, // Increment version due to schema change
    exportSchema = false
)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun stockCacheDao(): StockCacheDao

    companion object {
        @Volatile
        private var INSTANCE: StockzillaDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE favorites ADD COLUMN totalCurrentAssets REAL")
                database.execSQL("ALTER TABLE favorites ADD COLUMN totalCurrentLiabilities REAL")
                database.execSQL("ALTER TABLE favorites ADD COLUMN retainedEarnings REAL")
                database.execSQL("ALTER TABLE favorites ADD COLUMN workingCapital REAL")
                database.execSQL("ALTER TABLE favorites ADD COLUMN netCashProvidedByOperatingActivities REAL")
            }
        }

        fun getDatabase(context: Context): StockzillaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockzillaDatabase::class.java,
                    "stockzilla_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    // Allow destructive migrations while schema is stabilizing
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// Updated Repository for managing favorites
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
        // Get existing favorite to preserve addedDate and notes
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