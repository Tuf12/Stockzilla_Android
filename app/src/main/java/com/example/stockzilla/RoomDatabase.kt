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

@Database(
    entities = [FavoriteEntity::class, StockCacheEntity::class],
    version = 2, // Increment version due to schema change
    exportSchema = false
)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao

    companion object {
        @Volatile
        private var INSTANCE: StockzillaDatabase? = null

        fun getDatabase(context: Context): StockzillaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockzillaDatabase::class.java,
                    "stockzilla_database"
                )
                    .fallbackToDestructiveMigration() // Add this for schema changes
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