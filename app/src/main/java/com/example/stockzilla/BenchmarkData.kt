// BenchmarkData.kt - Industry and Sector PE/PS benchmark averages
package com.example.stockzilla

object BenchmarkData {

    // Industry-specific benchmarks (most accurate)
    private val industryBenchmarks = mapOf(
        // Technology Industries
        "Software" to Benchmark(peAvg = 28.5, psAvg = 8.2),
        "Semiconductors" to Benchmark(peAvg = 22.1, psAvg = 5.8),
        "Hardware" to Benchmark(peAvg = 18.7, psAvg = 3.4),
        "Internet Content & Information" to Benchmark(peAvg = 24.3, psAvg = 6.9),
        "Electronic Gaming & Multimedia" to Benchmark(peAvg = 26.8, psAvg = 4.1),
        "Consumer Electronics" to Benchmark(peAvg = 15.2, psAvg = 1.8),

        // Healthcare Industries  
        "Biotechnology" to Benchmark(peAvg = null, psAvg = 12.4), // Often unprofitable
        "Pharmaceuticals" to Benchmark(peAvg = 16.8, psAvg = 4.2),
        "Medical Devices" to Benchmark(peAvg = 19.3, psAvg = 6.1),
        "Healthcare Plans" to Benchmark(peAvg = 14.2, psAvg = 0.8),
        "Hospitals & Healthcare Services" to Benchmark(peAvg = 12.6, psAvg = 1.4),

        // Financial Industries
        "Banks" to Benchmark(peAvg = 11.4, psAvg = 1.2),
        "Insurance" to Benchmark(peAvg = 13.8, psAvg = 1.1),
        "Investment Banking & Brokerage" to Benchmark(peAvg = 15.2, psAvg = 2.8),
        "Real Estate Investment Trusts (REITs)" to Benchmark(peAvg = 22.1, psAvg = 8.9),
        "Credit Services" to Benchmark(peAvg = 10.8, psAvg = 3.2),

        // Consumer Industries
        "Restaurants" to Benchmark(peAvg = 28.4, psAvg = 2.1),
        "Retail - Apparel" to Benchmark(peAvg = 16.9, psAvg = 1.3),
        "Retail - General" to Benchmark(peAvg = 14.7, psAvg = 0.8),
        "Automotive" to Benchmark(peAvg = 8.9, psAvg = 0.6),
        "Consumer Packaged Goods" to Benchmark(peAvg = 22.3, psAvg = 3.1),

        // Industrial Industries
        "Aerospace & Defense" to Benchmark(peAvg = 18.6, psAvg = 1.9),
        "Manufacturing" to Benchmark(peAvg = 16.2, psAvg = 1.4),
        "Transportation" to Benchmark(peAvg = 14.8, psAvg = 1.1),
        "Construction" to Benchmark(peAvg = 12.4, psAvg = 0.9),

        // Energy Industries
        "Oil & Gas" to Benchmark(peAvg = 12.1, psAvg = 1.2),
        "Renewable Energy" to Benchmark(peAvg = 19.8, psAvg = 4.6),
        "Utilities" to Benchmark(peAvg = 18.9, psAvg = 2.1),

        // Materials Industries
        "Mining" to Benchmark(peAvg = 11.6, psAvg = 2.8),
        "Chemicals" to Benchmark(peAvg = 14.3, psAvg = 1.7),
        "Steel" to Benchmark(peAvg = 9.2, psAvg = 0.8),

        // Communications Industries
        "Telecommunications" to Benchmark(peAvg = 16.4, psAvg = 2.3),
        "Media & Entertainment" to Benchmark(peAvg = 18.7, psAvg = 3.4),

        // Real Estate Industries
        "Real Estate Development" to Benchmark(peAvg = 15.6, psAvg = 2.1),
        "Real Estate Services" to Benchmark(peAvg = 19.2, psAvg = 4.8)
    )

    // Sector-level benchmarks (fallback when industry not available)
    private val sectorBenchmarks = mapOf(
        "Technology" to Benchmark(peAvg = 24.2, psAvg = 6.1),
        "Healthcare" to Benchmark(peAvg = 17.4, psAvg = 5.8),
        "Financial Services" to Benchmark(peAvg = 12.8, psAvg = 1.8),
        "Consumer Cyclical" to Benchmark(peAvg = 18.1, psAvg = 1.4),
        "Consumer Defensive" to Benchmark(peAvg = 20.6, psAvg = 2.1),
        "Industrials" to Benchmark(peAvg = 15.7, psAvg = 1.4),
        "Energy" to Benchmark(peAvg = 14.2, psAvg = 1.6),
        "Materials" to Benchmark(peAvg = 12.4, psAvg = 1.8),
        "Communication Services" to Benchmark(peAvg = 17.8, psAvg = 2.9),
        "Real Estate" to Benchmark(peAvg = 18.4, psAvg = 4.2),
        "Utilities" to Benchmark(peAvg = 18.9, psAvg = 2.1)
    )

    /**
     * Get benchmark for a stock, preferring industry over sector
     */
    private fun getBenchmark(industry: String?, sector: String?): Benchmark {
        // Try industry first (most specific)
        industry?.let { industryBenchmarks[it] }?.let { return it }

        // Fall back to sector
        sector?.let { sectorBenchmarks[it] }?.let { return it }

        // Default fallback if nothing matches
        return Benchmark(peAvg = null, psAvg = null)
    }

    fun getBenchmarkAverages(stockData: StockData): Benchmark {
        return getBenchmark(stockData.industry, stockData.sector)
    }
    /**
     * Get the appropriate ratio and benchmark based on net income
     */
    fun getDisplayMetrics(stockData: StockData): DisplayMetrics {
        val benchmark = getBenchmark(stockData.industry, stockData.sector)
        val hasPositiveIncome = (stockData.netIncome ?: 0.0) > 0

        return if (hasPositiveIncome) {
            // Show P/E ratio and average P/E
            DisplayMetrics(
                primaryRatio = stockData.peRatio,
                primaryLabel = "P/E Ratio",
                benchmarkRatio = benchmark.peAvg,
                benchmarkLabel = "Avg P/E"
            )
        } else {
            // Show P/S ratio and average P/S  
            DisplayMetrics(
                primaryRatio = stockData.psRatio,
                primaryLabel = "P/S Ratio",
                benchmarkRatio = benchmark.psAvg,
                benchmarkLabel = "Avg P/S"
            )
        }
    }
}

data class Benchmark(
    val peAvg: Double?,
    val psAvg: Double?
)

data class DisplayMetrics(
    val primaryRatio: Double?,
    val primaryLabel: String,
    val benchmarkRatio: Double?,
    val benchmarkLabel: String
)