// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla

enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

data class MetricConfig(
    val min: Double,
    val max: Double,
    val baseWeight: Double,
    val direction: Direction,
    val transform: String? = null // optional: "log"
)

private val DEFAULT_METRIC_CONFIG = mapOf(
    "revenue"        to MetricConfig(0.0, 1_000_000_000.0, 0.15, Direction.HIGHER_IS_BETTER),
    "net_income"     to MetricConfig(-5_000_000.0, 50_000_000.0, 0.15, Direction.HIGHER_IS_BETTER),
    "eps"            to MetricConfig(-1.0, 5.0, 0.10, Direction.HIGHER_IS_BETTER),
    "pe_ratio"       to MetricConfig(5.0, 50.0, 0.10, Direction.LOWER_IS_BETTER, transform = "log"),
    "ps_ratio"       to MetricConfig(1.0, 15.0, 0.10, Direction.LOWER_IS_BETTER, transform = "log"),
    "roe"            to MetricConfig(0.0, 0.30, 0.15, Direction.HIGHER_IS_BETTER),
    "debt_to_equity" to MetricConfig(0.0, 2.0, 0.10, Direction.LOWER_IS_BETTER)
)


// Optional: tune per-sector ranges (examples; adjust as you gather data)
private val SECTOR_RANGE_OVERRIDES: Map<String, Map<String, Pair<Double, Double>>> = mapOf(
    "Technology" to mapOf(
        "revenue" to (0.0 to 1_500_000_000.0),
        "net_income" to (-20_000_000.0 to 200_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (8.0 to 80.0),
        "ps_ratio" to (2.0 to 20.0),
        "roe" to (0.0 to 0.35),
        "debt_to_equity" to (0.0 to 1.5)
    ),
    "Healthcare" to mapOf(
        "revenue" to (0.0 to 1_000_000_000.0),
        "net_income" to (-50_000_000.0 to 100_000_000.0),
        "eps" to (-2.0 to 4.0),
        "pe_ratio" to (10.0 to 60.0),
        "ps_ratio" to (2.0 to 18.0),
        "roe" to (-0.10 to 0.30),
        "debt_to_equity" to (0.0 to 2.0)
    ),
    "Financial Services" to mapOf(
        "revenue" to (0.0 to 2_000_000_000.0),
        "net_income" to (-10_000_000.0 to 400_000_000.0),
        "eps" to (-1.0 to 8.0),
        "pe_ratio" to (6.0 to 30.0),
        "ps_ratio" to (1.0 to 10.0),
        "roe" to (0.05 to 0.25),
        "debt_to_equity" to (0.0 to 3.0)
    ),
    "Cannabis" to mapOf(
        "revenue" to (0.0 to 600_000_000.0),
        "net_income" to (-150_000_000.0 to 50_000_000.0),
        "eps" to (-3.0 to 2.0),
        "pe_ratio" to (0.0 to 80.0),        // often N/A or noisy; keep wide
        "ps_ratio" to (0.5 to 12.0),
        "roe" to (-0.40 to 0.15),
        "debt_to_equity" to (0.0 to 2.5)
    ),
    "Industrials" to mapOf(
        "revenue" to (0.0 to 2_500_000_000.0),
        "net_income" to (-30_000_000.0 to 300_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (8.0 to 35.0),
        "ps_ratio" to (0.8 to 8.0),
        "roe" to (0.03 to 0.25),
        "debt_to_equity" to (0.0 to 2.0)
    )
)

// Your original sector weight logic, moved into config form (weights only)
private val SECTOR_WEIGHT_OVERRIDES: Map<String, Map<String, Double>> = mapOf(
    "Technology" to mapOf("revenue" to 0.20, "ps_ratio" to 0.15, "net_income" to 0.08, "pe_ratio" to 0.05),
    "Healthcare" to mapOf("net_income" to 0.18, "roe" to 0.18, "ps_ratio" to 0.08),
    "Financial Services" to mapOf("roe" to 0.25, "net_income" to 0.20, "revenue" to 0.08, "debt_to_equity" to 0.15)
)


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
    val industry: String?
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

@Suppress("UNCHECKED_CAST")
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
            weightedScore < 7.5 -> 1
            weightedScore < 9.5 -> 2
            weightedScore < 11.5 -> 3
            weightedScore < 13.5 -> 4
            weightedScore < 15.5 -> 5
            weightedScore < 17.5 -> 6
            weightedScore < 19.5 -> 7
            weightedScore < 21.5 -> 8
            weightedScore < 23.5 -> 9
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

        fun md(metric: String, value: Double?): MetricData? {
            val (min, max) = getSectorRange(metric, sector)
            if (min >= max) return null
            val w = weights[metric] ?: DEFAULT_METRIC_CONFIG[metric]?.baseWeight ?: return null
            return MetricData(value = value, min = min, max = max, weight = w)
        }

        return listOfNotNull(
            "revenue"        to md("revenue", stockData.revenue),
            "net_income"     to md("net_income", stockData.netIncome),
            "eps"            to md("eps", stockData.eps),
            "pe_ratio"       to md("pe_ratio", stockData.peRatio),
            "ps_ratio"       to md("ps_ratio", stockData.psRatio),
            "roe"            to md("roe", stockData.roe),
            "debt_to_equity" to md("debt_to_equity", stockData.debtToEquity)
        ).toMap() as Map<String, MetricData>
    }


    private fun getSectorSpecificWeights(sector: String, marketCap: Double?): Map<String, Double> {
        val baseWeights = DEFAULT_METRIC_CONFIG.mapValues { it.value.baseWeight }.toMutableMap()
        SECTOR_WEIGHT_OVERRIDES[sector]?.forEach { (metric, w) -> baseWeights[metric] = w }

        marketCap?.let { cap ->
            when {
                cap < 2_000_000_000 -> { // Small cap
                    baseWeights["revenue"] = (baseWeights["revenue"] ?: 0.0) * 1.2
                    baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * 1.3
                    baseWeights["net_income"] = (baseWeights["net_income"] ?: 0.0) * 0.8
                }
                cap > 100_000_000_000 -> { // Large cap
                    baseWeights["net_income"] = (baseWeights["net_income"] ?: 0.0) * 1.2
                    baseWeights["roe"] = (baseWeights["roe"] ?: 0.0) * 1.2
                }
            }
        }
        return baseWeights
    }

    private fun getSectorRange(metric: String, sector: String): Pair<Double, Double> {
        val override = SECTOR_RANGE_OVERRIDES[sector]?.get(metric)
        if (override != null && override.first < override.second) return override
        val def = DEFAULT_METRIC_CONFIG[metric] ?: error("Missing default config for $metric")
        return def.min to def.max
    }




    private fun computeCompositeScore(healthData: Map<String, MetricData>): Pair<Double, List<MetricScore>> {
        var total = 0.0
        var totalWeight = 0.0
        val breakdown = mutableListOf<MetricScore>()

        healthData.forEach { (name, metric) ->
            metric.value?.let { value ->
                val cfg = DEFAULT_METRIC_CONFIG[name]
                val normalized = if (cfg != null)
                    normalizeValueWithConfig(value, metric.min, metric.max, cfg)
                else
                    normalizeValue(value, metric.min, metric.max)

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

    private fun normalizeValueWithConfig(
        value: Double,
        min: Double,
        max: Double,
        cfg: MetricConfig
    ): Double {
        // optional heavy-tail taming for ratios
        fun tx(x: Double): Double = when (cfg.transform) {
            "log" -> kotlin.math.ln(x.coerceAtLeast(0.0) + 1.0)
            else  -> x
        }
        val tMin = tx(min)
        val tMax = tx(max)
        if (tMin >= tMax) return 0.0

        val tVal = tx(value)
        val frac = ((tVal - tMin) / (tMax - tMin)).coerceIn(0.0, 1.0)

        return when (cfg.direction) {
            Direction.HIGHER_IS_BETTER -> frac
            Direction.LOWER_IS_BETTER  -> 1.0 - frac
        }
    }


    private fun normalizeValue(value: Double, min: Double, max: Double): Double {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }

    private fun calculateForecastScore(stockData: StockData): Int {
        // Simplified forecast scoring based on P/E and P/S ratios
        var score = 1

        val psBonus = stockData.psRatio?.let { ps ->
            when {
                ps < 3 -> 2
                ps < 8 -> 1
                else -> 0
            }
        } ?: 0

        val peBonus = stockData.peRatio?.takeIf { it > 0 }?.let { pe ->
            when {
                pe < 15 -> 2
                pe < 25 -> 1
                else -> 0
            }
        } ?: 0

        score += psBonus + peBonus

        if (psBonus == 2 && peBonus == 2) {
            score += 1
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