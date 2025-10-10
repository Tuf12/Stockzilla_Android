// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla

data class StockData(
    val symbol: String,
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
    val pbRatio: Double?,
    val ebitda: Double?,
    val totalAssets: Double?,
    val totalLiabilities: Double?

)

data class HealthScore(
    val compositeScore: Int,
    val healthSubScore: Int,
    val forecastSubScore: Int,
    val zSubScore: Int,
    val breakdown: List<MetricScore>
)

data class MetricScore(
    val metric: String,
    val value: Double?,
    val normalized: Double,
    val weight: Double,
    val score: Double
)

class FinancialHealthAnalyzer {

    fun calculateCompositeScore(stockData: StockData): HealthScore {
        val healthData = buildHealthData(stockData)
        val (healthScore, breakdown) = computeCompositeScore(healthData)
        val healthSubScore = (healthScore / 2).coerceIn(1.0, 5.0).toInt()

        val forecastSubScore = calculateForecastScore(stockData)
        val zSubScore = calculateZScore(stockData)

        val weightedScore = (
                healthSubScore * 2.5 +
                        forecastSubScore * 2.0 +
                        zSubScore * 1.0
                )

        val compositeScore = when {
            weightedScore < 11 -> 1
            weightedScore < 13 -> 2
            weightedScore < 15 -> 3
            weightedScore < 17 -> 4
            weightedScore < 19 -> 5
            weightedScore < 21 -> 6
            weightedScore < 23 -> 7
            weightedScore < 25 -> 8
            weightedScore < 27 -> 9
            else -> 10
        }

        return HealthScore(
            compositeScore = compositeScore,
            healthSubScore = healthSubScore,
            forecastSubScore = forecastSubScore,
            zSubScore = zSubScore,
            breakdown = breakdown
        )
    }

    private fun buildHealthData(stockData: StockData): Map<String, MetricData> {
        val sector = stockData.sector ?: "Unknown"
        val weights = getSectorSpecificWeights(sector, stockData.marketCap)

        return mapOf(
            "revenue" to MetricData(stockData.revenue, 0.0, 1_000_000_000.0, weights["revenue"] ?: 0.15),
            "net_income" to MetricData(stockData.netIncome, -5_000_000.0, 50_000_000.0, weights["net_income"] ?: 0.15),
            "eps" to MetricData(stockData.eps, -1.0, 5.0, weights["eps"] ?: 0.10),
            "pe_ratio" to MetricData(stockData.peRatio, 5.0, 50.0, weights["pe_ratio"] ?: 0.10),
            "ps_ratio" to MetricData(stockData.psRatio, 1.0, 15.0, weights["ps_ratio"] ?: 0.10),
            "roe" to MetricData(stockData.roe, 0.0, 0.3, weights["roe"] ?: 0.15),
            "debt_to_equity" to MetricData(stockData.debtToEquity, 0.0, 2.0, weights["debt_to_equity"] ?: 0.10),
            "pb_ratio" to MetricData(stockData.pbRatio, 0.5, 10.0, weights["pb_ratio"] ?: 0.10),
            "ebitda" to MetricData(stockData.ebitda, -20_000_000.0, 200_000_000.0, weights["ebitda"] ?: 0.10),
            "assets" to MetricData(stockData.totalAssets, 10_000_000.0, 1_000_000_000_000.0, weights["assets"] ?: 0.10),
            "liabilities" to MetricData(stockData.totalLiabilities, 1_000_000.0, 500_000_000_000.0, weights["liabilities"] ?: 0.10),
            "current_ratio" to MetricData(
                if (stockData.totalLiabilities != null && stockData.totalLiabilities > 0)
                    stockData.totalAssets?.div(stockData.totalLiabilities)
                else null,
                0.5,
                3.0,
                weights["current_ratio"] ?: 0.10
            )


        )
    }

    private fun getSectorSpecificWeights(sector: String, marketCap: Double?): Map<String, Double> {
        val baseWeights = mutableMapOf(
            "revenue" to 0.12,
            "net_income" to 0.12,
            "eps" to 0.08,
            "pe_ratio" to 0.08,
            "ps_ratio" to 0.08,
            "pb_ratio" to 0.08,
            "roe" to 0.10,
            "debt_to_equity" to 0.08,
            "current_ratio" to 0.08,
            "ebitda" to 0.08,
            "assets" to 0.06,
            "liabilities" to 0.06
        )


        // Sector-specific adjustments
        when (sector) {
            "Technology" -> {
                baseWeights["revenue"] = 0.20
                baseWeights["ps_ratio"] = 0.15
                baseWeights["net_income"] = 0.08
                baseWeights["pe_ratio"] = 0.05
            }
            "Healthcare" -> {
                baseWeights["net_income"] = 0.18
                baseWeights["roe"] = 0.18
                baseWeights["ps_ratio"] = 0.08
            }
            "Financial Services" -> {
                baseWeights["roe"] = 0.25
                baseWeights["net_income"] = 0.20
                baseWeights["revenue"] = 0.08
                baseWeights["debt_to_equity"] = 0.15
            }
        }

        // Market cap adjustments
        marketCap?.let { cap ->
            when {
                cap < 2_000_000_000 -> { // Small cap
                    baseWeights["revenue"] = baseWeights["revenue"]!! * 1.2
                    baseWeights["ps_ratio"] = baseWeights["ps_ratio"]!! * 1.3
                    baseWeights["net_income"] = baseWeights["net_income"]!! * 0.8
                }
                cap > 100_000_000_000 -> { // Large cap
                    baseWeights["net_income"] = baseWeights["net_income"]!! * 1.2
                    baseWeights["roe"] = baseWeights["roe"]!! * 1.2
                }
            }
        }

        return baseWeights
    }

    private fun computeCompositeScore(healthData: Map<String, MetricData>): Pair<Double, List<MetricScore>> {
        var total = 0.0
        var totalWeight = 0.0
        val breakdown = mutableListOf<MetricScore>()

        healthData.forEach { (name, metric) ->
            metric.value?.let { value ->
                val normalized = normalizeValue(value, metric.min, metric.max)
                val weighted = normalized * metric.weight

                breakdown.add(
                    MetricScore(
                        metric = name,
                        value = value,
                        normalized = normalized,
                        weight = metric.weight,
                        score = weighted
                    )
                )

                total += weighted
                totalWeight += metric.weight
            }
        }

        val score = if (totalWeight > 0) (total / totalWeight) * 10 else 0.0
        return Pair(score, breakdown)
    }

    private fun normalizeValue(value: Double, min: Double, max: Double): Double {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    private fun calculateForecastScore(stockData: StockData): Int {
        // Simplified forecast scoring based on P/E and P/S ratios
        var score = 1

        stockData.psRatio?.let { ps ->
            if (ps < 3) score += 2
            else if (ps < 8) score += 1
        }

        stockData.peRatio?.let { pe ->
            if (pe < 15 && pe > 0) score += 1
            else if (pe < 25 && pe > 0) score += 1
        }

        return score.coerceIn(1, 5)
    }

    private fun calculateZScore(stockData: StockData): Int {
        // Simplified Z-score approximation
        val hasPositiveIncome = (stockData.netIncome ?: 0.0) > 0
        val hasReasonableDebt = (stockData.debtToEquity ?: 0.0) < 1.0
        val hasCashFlow = (stockData.freeCashFlow ?: 0.0) > 0

        return when {
            hasPositiveIncome && hasReasonableDebt && hasCashFlow -> 3
            hasPositiveIncome && hasReasonableDebt -> 2
            else -> 1
        }
    }
}

data class MetricData(
    val value: Double?,
    val min: Double,
    val max: Double,
    val weight: Double
)