package com.example.stockzilla.data

import com.example.stockzilla.scoring.Benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes PE/PS benchmark from separated persistence domains when enough peers exist (≥10).
 * Falls back to null so callers can use hardcoded BenchmarkData.
 * Algorithm: try SIC + cap tier → if peers < 10 try SIC only → if still < 10 try sector → if still < 10 return null.
 */
class DynamicBenchmarkRepository(
    private val rawFactsDao: EdgarRawFactsDao,
    private val derivedMetricsDao: FinancialDerivedMetricsDao
) {

    companion object {
        private const val MIN_PEERS = 10
    }

    /**
     * Returns dynamic benchmark from peer averages when ≥10 peers exist; null otherwise.
     * Caller should use BenchmarkData (industry/sector) when null.
     */
    suspend fun getDynamicBenchmark(symbol: String): Benchmark? = withContext(Dispatchers.IO) {
        val raw = rawFactsDao.getBySymbol(symbol) ?: return@withContext null
        val derived = derivedMetricsDao.getBySymbol(symbol) ?: return@withContext null
        val sicCode = raw.sicCode?.takeIf { it.isNotBlank() } ?: return@withContext null
        val capTier = derived.marketCapTier?.takeIf { it.isNotBlank() } ?: return@withContext null
        val sector = raw.sector?.takeIf { it.isNotBlank() } ?: return@withContext null

        var peers = derivedMetricsDao.getBenchmarkPeersBySicAndCapTier(sicCode, capTier)
        if (peers.size < MIN_PEERS) {
            peers = derivedMetricsDao.getBenchmarkPeersBySic(sicCode)
        }
        if (peers.size < MIN_PEERS) {
            peers = derivedMetricsDao.getBenchmarkPeersBySector(sector)
        }
        if (peers.size < MIN_PEERS) {
            return@withContext null
        }

        val peValues = peers.mapNotNull { it.peRatio }.filter { it > 0 }
        val psValues = peers.mapNotNull { it.psRatio }.filter { it > 0 }
        val avgPe = if (peValues.isNotEmpty()) peValues.average() else null
        val avgPs = if (psValues.isNotEmpty()) psValues.average() else null
        if (avgPe == null && avgPs == null) return@withContext null

        Benchmark(peAvg = avgPe, psAvg = avgPs)
    }
}