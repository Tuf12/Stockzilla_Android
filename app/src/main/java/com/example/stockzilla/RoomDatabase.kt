// RoomDatabase.kt - Local SQLite database using Room
package com.example.stockzilla

import androidx.room.*
import androidx.room.Database
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val symbol: String,
    val companyName: String?,
    val price: Double?,
    val healthScore: Int?,
    val sector: String?,
    val addedDate: Long,
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

    @Query("SELECT * FROM favorites ORDER BY addedDate DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE symbol = :symbol")
    suspend fun getFavorite(symbol: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE symbol = :symbol")
    suspend fun deleteFavoriteBySymbol(symbol: String)

    @Query("UPDATE favorites SET notes = :notes WHERE symbol = :symbol")
    suspend fun updateNotes(symbol: String, notes: String)
}

@Dao
interface StockCacheDao {
    @Query("SELECT * FROM stock_cache WHERE symbol = :symbol AND expiresAt > :currentTime")
    suspend fun getCachedStock(symbol: String, currentTime: Long): StockCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedStock(cache: StockCacheEntity)

    @Query("DELETE FROM stock_cache WHERE expiresAt < :currentTime")
    suspend fun clearExpiredCache(currentTime: Long)
}

@Database(
    entities = [FavoriteEntity::class, StockCacheEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StockzillaDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun stockCacheDao(): StockCacheDao

    companion object {
        @Volatile
        private var INSTANCE: StockzillaDatabase? = null

        fun getDatabase(context: android.content.Context): StockzillaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StockzillaDatabase::class.java,
                    "stockzilla_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    // Add any type converters if needed
}

// Repository for managing favorites
class FavoritesRepository(private val favoritesDao: FavoritesDao) {

    suspend fun getAllFavorites(): List<StockData> {
        return favoritesDao.getAllFavorites().map { entity ->
            StockData(
                symbol = entity.symbol,
                companyName = entity.companyName,
                price = entity.price,
                marketCap = null, // Not stored in favorites
                revenue = null,
                netIncome = null,
                eps = null,
                peRatio = null,
                psRatio = null,
                roe = null,
                debtToEquity = null,
                freeCashFlow = null,
                sector = entity.sector,
                industry = null
            )
        }
    }

    fun getAllFavoritesFlow(): Flow<List<StockData>> {
        return favoritesDao.getAllFavoritesFlow().map { entities ->
            entities.map { entity ->
                StockData(
                    symbol = entity.symbol,
                    companyName = entity.companyName,
                    price = entity.price,
                    marketCap = null,
                    revenue = null,
                    netIncome = null,
                    eps = null,
                    peRatio = null,
                    psRatio = null,
                    roe = null,
                    debtToEquity = null,
                    freeCashFlow = null,
                    sector = entity.sector,
                    industry = null
                )
            }
        }
    }

    suspend fun addFavorite(stockData: StockData, healthScore: Int? = null) {
        val favorite = FavoriteEntity(
            symbol = stockData.symbol,
            companyName = stockData.companyName,
            price = stockData.price,
            healthScore = healthScore,
            sector = stockData.sector,
            addedDate = System.currentTimeMillis(),
            notes = null
        )
        favoritesDao.insertFavorite(favorite)
    }

    suspend fun removeFavorite(symbol: String) {
        favoritesDao.deleteFavoriteBySymbol(symbol)
    }

    suspend fun updateNotes(symbol: String, notes: String) {
        favoritesDao.updateNotes(symbol, notes)
    }

    suspend fun isFavorite(symbol: String): Boolean {
        return favoritesDao.getFavorite(symbol) != null
    }
}

// Repository for stock data caching
class StockCacheRepository(private val stockCacheDao: StockCacheDao) {

    suspend fun getCachedStock(symbol: String): StockData? {
        val currentTime = System.currentTimeMillis()
        val cached = stockCacheDao.getCachedStock(symbol, currentTime)
        return cached?.let {
            // Parse JSON back to StockData
            parseStockDataFromJson(it.stockDataJson)
        }
    }

    suspend fun cacheStock(stockData: StockData, cacheDurationMinutes: Long = 15) {
        val currentTime = System.currentTimeMillis()
        val expiryTime = currentTime + (cacheDurationMinutes * 60 * 1000)

        val cache = StockCacheEntity(
            symbol = stockData.symbol,
            stockDataJson = stockDataToJson(stockData),
            cachedAt = currentTime,
            expiresAt = expiryTime
        )

        stockCacheDao.insertCachedStock(cache)
    }

    suspend fun clearExpiredCache() {
        stockCacheDao.clearExpiredCache(System.currentTimeMillis())
    }

    private fun stockDataToJson(stockData: StockData): String {
        // Use Gson or similar to convert to JSON
        return com.google.gson.Gson().toJson(stockData)
    }

    private fun parseStockDataFromJson(json: String): StockData? {
        return try {
            com.google.gson.Gson().fromJson(json, StockData::class.java)
        } catch (e: Exception) {
            null
        }
    }
}