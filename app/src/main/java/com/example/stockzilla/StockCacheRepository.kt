package com.example.stockzilla

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CachedStock(
    val stockData: StockData,
    val cachedAt: Long
)

class StockCacheRepository(
    private val stockCacheDao: StockCacheDao,
    private val gson: Gson = Gson(),
    private val clockZone: ZoneId = ZoneId.of("America/New_York")
) {

    suspend fun getCachedStock(symbol: String, now: Instant = Instant.now()): CachedStock? =
        withContext(Dispatchers.IO) {
            val nowMillis = now.toEpochMilli()
            stockCacheDao.deleteExpired(nowMillis)
            val cached = stockCacheDao.getCache(symbol) ?: return@withContext null
            if (cached.expiresAt <= nowMillis) {
                null
            } else {
                CachedStock(
                    stockData = gson.fromJson(cached.stockDataJson, StockData::class.java),
                    cachedAt = cached.cachedAt
                )
            }
        }

    suspend fun saveStockData(symbol: String, stockData: StockData, now: Instant = Instant.now()) {
        withContext(Dispatchers.IO) {
            val nextTradingDayStart = calculateNextTradingDayStart(now)
            val nextTradingDayMillis = nextTradingDayStart.toEpochMilli()
            val entity = StockCacheEntity(
                symbol = symbol,
                stockDataJson = gson.toJson(stockData),
                cachedAt = now.toEpochMilli(),
                expiresAt = nextTradingDayMillis
            )
            stockCacheDao.upsertCache(entity)
        }
    }

    suspend fun pruneExpired(now: Instant = Instant.now()) {
        withContext(Dispatchers.IO) {
            stockCacheDao.deleteExpired(now.toEpochMilli())
        }
    }

    private fun calculateNextTradingDayStart(now: Instant): Instant {
        var nextDate: LocalDate = now.atZone(clockZone).toLocalDate().plusDays(1)
        while (nextDate.dayOfWeek == DayOfWeek.SATURDAY || nextDate.dayOfWeek == DayOfWeek.SUNDAY) {
            nextDate = nextDate.plusDays(1)
        }
        return nextDate.atStartOfDay(clockZone).toInstant()
    }
}