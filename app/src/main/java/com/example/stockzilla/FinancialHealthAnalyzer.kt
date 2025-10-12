// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla

import java.io.Serializable
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.pow



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
    "debt_to_equity" to MetricConfig(0.0, 2.0, 0.10, Direction.LOWER_IS_BETTER),
    "pb_ratio"       to MetricConfig(0.5, 15.0, 0.08, Direction.LOWER_IS_BETTER, transform = "log"),
    "ebitda"         to MetricConfig(-100_000_000.0, 500_000_000.0, 0.12, Direction.HIGHER_IS_BETTER),
    "outstanding_shares" to MetricConfig(5_000_000.0, 10_000_000_000.0, 0.05, Direction.LOWER_IS_BETTER, transform = "log"),
    "total_assets"   to MetricConfig(50_000_000.0, 2_000_000_000_000.0, 0.07, Direction.HIGHER_IS_BETTER, transform = "log"),
    "total_liabilities" to MetricConfig(10_000_000.0, 1_000_000_000_000.0, 0.07, Direction.LOWER_IS_BETTER, transform = "log")
)
private val ABSOLUTE_METRICS = setOf("revenue", "net_income", "ebitda", "total_assets", "total_liabilities")

// Optional: tune per-sector ranges (examples; adjust as you gather data)
private val SECTOR_RANGE_OVERRIDES: Map<String, Map<String, Pair<Double, Double>>> = mapOf(
    "Technology" to mapOf(
        "revenue" to (0.0 to 1_500_000_000.0),
        "net_income" to (-20_000_000.0 to 200_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (8.0 to 80.0),
        "ps_ratio" to (2.0 to 20.0),
        "roe" to (0.0 to 0.35),
        "debt_to_equity" to (0.0 to 1.5),
        "pb_ratio" to (1.0 to 20.0),
        "ebitda" to (-150_000_000.0 to 800_000_000.0),
        "outstanding_shares" to (10_000_000.0 to 8_000_000_000.0),
        "total_assets" to (100_000_000.0 to 2_500_000_000_000.0),
        "total_liabilities" to (50_000_000.0 to 1_200_000_000_000.0)
    ),
    "Healthcare" to mapOf(
        "revenue" to (0.0 to 1_000_000_000.0),
        "net_income" to (-50_000_000.0 to 100_000_000.0),
        "eps" to (-2.0 to 4.0),
        "pe_ratio" to (10.0 to 60.0),
        "ps_ratio" to (2.0 to 18.0),
        "roe" to (-0.10 to 0.30),
        "debt_to_equity" to (0.0 to 2.0),
        "pb_ratio" to (0.8 to 18.0),
        "ebitda" to (-200_000_000.0 to 600_000_000.0),
        "outstanding_shares" to (8_000_000.0 to 6_000_000_000.0),
        "total_assets" to (80_000_000.0 to 1_500_000_000_000.0),
        "total_liabilities" to (30_000_000.0 to 800_000_000_000.0)
    ),
    "Financial Services" to mapOf(
        "revenue" to (0.0 to 2_000_000_000.0),
        "net_income" to (-10_000_000.0 to 400_000_000.0),
        "eps" to (-1.0 to 8.0),
        "pe_ratio" to (6.0 to 30.0),
        "ps_ratio" to (1.0 to 10.0),
        "roe" to (0.05 to 0.25),
        "debt_to_equity" to (0.0 to 3.0),
        "pb_ratio" to (0.6 to 8.0),
        "ebitda" to (-150_000_000.0 to 1_200_000_000.0),
        "outstanding_shares" to (12_000_000.0 to 9_000_000_000.0),
        "total_assets" to (150_000_000.0 to 5_000_000_000_000.0),
        "total_liabilities" to (80_000_000.0 to 3_500_000_000_000.0)
    ),
    "Cannabis" to mapOf(
        "revenue" to (0.0 to 600_000_000.0),
        "net_income" to (-150_000_000.0 to 50_000_000.0),
        "eps" to (-3.0 to 2.0),
        "pe_ratio" to (0.0 to 80.0),        // often N/A or noisy; keep wide
        "ps_ratio" to (0.5 to 12.0),
        "roe" to (-0.40 to 0.15),
        "debt_to_equity" to (0.0 to 2.5),
        "pb_ratio" to (0.3 to 25.0),
        "ebitda" to (-300_000_000.0 to 400_000_000.0),
        "outstanding_shares" to (20_000_000.0 to 9_000_000_000.0),
        "total_assets" to (40_000_000.0 to 900_000_000_000.0),
        "total_liabilities" to (20_000_000.0 to 500_000_000_000.0)
    ),
    "Industrials" to mapOf(
        "revenue" to (0.0 to 2_500_000_000.0),
        "net_income" to (-30_000_000.0 to 300_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (8.0 to 35.0),
        "ps_ratio" to (0.8 to 8.0),
        "roe" to (0.03 to 0.25),
        "debt_to_equity" to (0.0 to 2.0),
        "pb_ratio" to (0.7 to 15.0),
        "ebitda" to (-200_000_000.0 to 1_000_000_000.0),
        "outstanding_shares" to (10_000_000.0 to 7_000_000_000.0),
        "total_assets" to (120_000_000.0 to 3_000_000_000_000.0),
        "total_liabilities" to (60_000_000.0 to 1_600_000_000_000.0)
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
    val pbRatio: Double?,
    val ebitda: Double?,
    val outstandingShares: Double?,
    val totalAssets: Double?,
    val totalLiabilities: Double?,
    val sector: String?,
    val industry: String?,
    val revenueGrowth: Double? = null,
    val averageRevenueGrowth: Double? = null,
    val averageNetIncomeGrowth: Double? = null
): Serializable

data class HealthScore(
    val compositeScore: Int,
    val coreHealthScore: Int,
    val growthForecastScore: Int,
    val resilienceScore: Int,
    val breakdown: List<MetricScore>
): Serializable

data class MetricScore(
    val metric: String,
    val value: Double?,
    val normalizedPercent: Double,
    val weight: Double,
    val score: Double
): Serializable

@Suppress("UNCHECKED_CAST")
class FinancialHealthAnalyzer {

    fun calculateCompositeScore(stockData: StockData): HealthScore {
        val healthData = buildHealthData(stockData)
        val coreResult = calculateCoreHealthScore(healthData)
        val growthScoreRaw = calculateGrowthForecastScore(stockData)
        val resilienceScoreRaw = calculateResilienceScore(stockData)

        val coreHealthScore = (coreResult.score ?: 5.0).coerceIn(0.0, 10.0)
        val growthForecastScore = (growthScoreRaw ?: 5.0).coerceIn(0.0, 10.0)
        val resilienceScore = (resilienceScoreRaw ?: 5.0).coerceIn(0.0, 10.0)

        val composite = (coreHealthScore * 0.4) + (growthForecastScore * 0.3) + (resilienceScore * 0.3)
        val compositeScore = composite.coerceIn(0.0, 10.0)

        return HealthScore(
            compositeScore = compositeScore.roundToInt().coerceIn(0, 10),
            coreHealthScore = coreHealthScore.roundToInt().coerceIn(0, 10),
            growthForecastScore = growthForecastScore.roundToInt().coerceIn(0, 10),
            resilienceScore = resilienceScore.roundToInt().coerceIn(0, 10),
            breakdown = coreResult.breakdown
        )

    }

    private fun buildHealthData(stockData: StockData): Map<String, MetricData> {
        val sector = stockData.sector ?: "Unknown"
        val revenueGrowthSignal = stockData.revenueGrowth ?: stockData.averageRevenueGrowth
        val weights = getSectorSpecificWeights(sector, stockData.marketCap, revenueGrowthSignal)

        fun md(metric: String, value: Double?): MetricData? {
            val (min, max) = getSectorRange(metric, sector, stockData.marketCap)
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
            "debt_to_equity" to md("debt_to_equity", stockData.debtToEquity),
            "pb_ratio"       to md("pb_ratio", stockData.pbRatio),
            "ebitda"         to md("ebitda", stockData.ebitda),
            "outstanding_shares" to md("outstanding_shares", stockData.outstandingShares),
            "total_assets"   to md("total_assets", stockData.totalAssets),
            "total_liabilities" to md("total_liabilities", stockData.totalLiabilities)
        ).toMap() as Map<String, MetricData>
    }


    private fun getSectorSpecificWeights(
        sector: String,
        marketCap: Double?,
        revenueGrowth: Double?
    ): Map<String, Double> {
        val baseWeights = DEFAULT_METRIC_CONFIG.mapValues { it.value.baseWeight }.toMutableMap()
        SECTOR_WEIGHT_OVERRIDES[sector]?.forEach { (metric, w) -> baseWeights[metric] = w }

        val isHighGrowth = (revenueGrowth ?: 0.0) > 0.15

        marketCap?.let { cap ->
            when {
                cap < 2_000_000_000 -> {
                    val revenueBump = if (isHighGrowth) 1.4 else 1.2
                    val psBump = if (isHighGrowth) 1.35 else 1.2
                    baseWeights["revenue"] = (baseWeights["revenue"] ?: 0.0) * revenueBump
                    baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * psBump
                    baseWeights["net_income"] = (baseWeights["net_income"] ?: 0.0) * 0.75
                    if (isHighGrowth) {
                        baseWeights["roe"] = (baseWeights["roe"] ?: 0.0) * 0.9
                        baseWeights["debt_to_equity"] = (baseWeights["debt_to_equity"] ?: 0.0) * 0.9
                    }
                }

                cap > 100_000_000_000 -> {
                    baseWeights["roe"] = (baseWeights["roe"] ?: 0.0) * if (isHighGrowth) 1.15 else 1.3
                    baseWeights["debt_to_equity"] = (baseWeights["debt_to_equity"] ?: 0.0) * if (isHighGrowth) 1.1 else 1.25
                    baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * 0.85
                    baseWeights["revenue"] = (baseWeights["revenue"] ?: 0.0) * if (isHighGrowth) 1.05 else 0.9
                }
                else -> {
                    if (isHighGrowth) {
                        baseWeights["revenue"] = (baseWeights["revenue"] ?: 0.0) * 1.25
                        baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * 1.2
                    }
                }
            }
        } ?: run {
            if (isHighGrowth) {
                baseWeights["revenue"] = (baseWeights["revenue"] ?: 0.0) * 1.25
                baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * 1.2
            }
        }
        return baseWeights
    }

    private fun getSectorRange(metric: String, sector: String, marketCap: Double?): Pair<Double, Double> {
        val override = SECTOR_RANGE_OVERRIDES[sector]?.get(metric)
        val baseRange = if (override != null && override.first < override.second) {
            override
        } else {
            val def = DEFAULT_METRIC_CONFIG[metric] ?: error("Missing default config for $metric")
            def.min to def.max
        }

        return applyDynamicRangeAdjustments(metric, baseRange, marketCap)
    }

    private fun applyDynamicRangeAdjustments(
        metric: String,
        baseRange: Pair<Double, Double>,
        marketCap: Double?
    ): Pair<Double, Double> {
        var (min, max) = baseRange
        val cap = marketCap

        if (cap != null && cap > 0) {
            val largeCapThreshold = 100_000_000_000.0
            if (metric in ABSOLUTE_METRICS) {
                val scaleBase = cap / 1_000_000_000.0
                val scale = if (scaleBase > 1.0) scaleBase.pow(0.25) else 1.0
                if (scale.isFinite() && scale > 0) {
                    if (min < 0) min *= scale
                    max *= scale
                }
            }

            if (cap >= largeCapThreshold) {
                when (metric) {
                    "pe_ratio" -> {
                        val tightenedMax = max - (max - min) * 0.15
                        if (tightenedMax > min) {
                            max = tightenedMax
                        }
                    }

                    "roe" -> {
                        val uplift = (max - min) * 0.1
                        min += uplift
                    }
                }
            }
        }

        if (min >= max) {
            val epsilon = kotlin.math.abs(max) * 0.01 + 1e-6
            max = min + epsilon
        }

        return min to max
    }




    private fun calculateCoreHealthScore(healthData: Map<String, MetricData>): CoreHealthResult {
        var total = 0.0
        var totalWeight = 0.0
        val breakdown = mutableListOf<MetricScore>()

        healthData.forEach { (name, metric) ->
            metric.value?.let { value ->
                val cfg = DEFAULT_METRIC_CONFIG[name]
                val normalizedFraction = if (cfg != null)
                    normalizeValueWithConfig(value, metric.min, metric.max, cfg)
                else
                    normalizeValue(value, metric.min, metric.max)

                val weighted = normalizedFraction * metric.weight
                val normalizedPercent = normalizedFraction * 100.0

                breakdown.add(
                    MetricScore(
                        metric = name,
                        value = value,
                        normalizedPercent = normalizedPercent,
                        weight = metric.weight,
                        score = weighted
                    )
                )

                total += weighted
                totalWeight += metric.weight
            }
        }

        val score = if (totalWeight > 0) (total / totalWeight) * 10 else null
        return CoreHealthResult(score, breakdown)
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
        if (tMin >= tMax) return 0.5


        val tVal = tx(value)
        val denominator = (tMax - tMin).takeIf { it > 0.0 } ?: return 0.5
        val frac = (tVal - tMin) / denominator
        val normalized = sigmoidNormalizeFraction(frac)

        return when (cfg.direction) {
            Direction.HIGHER_IS_BETTER -> normalized
            Direction.LOWER_IS_BETTER  -> 1.0 - normalized

        }
    }


    private fun normalizeValue(value: Double, min: Double, max: Double): Double {
        val denominator = (max - min).takeIf { it > 0.0 } ?: return 0.5
        val frac = (value - min) / denominator
        return sigmoidNormalizeFraction(frac)
    }

    private fun sigmoidNormalizeFraction(frac: Double): Double {
        if (!frac.isFinite()) return 0.5
        val scaled = (frac - 0.5) * 6.0
        val logistic = 1.0 / (1.0 + exp(-scaled))
        return logistic.coerceIn(0.0, 1.0)
    }

    private fun calculateGrowthForecastScore(stockData: StockData): Double? {
        val hasAnySignal = listOfNotNull(
            stockData.psRatio,
            stockData.peRatio,
            stockData.revenueGrowth,
            stockData.averageRevenueGrowth,
            stockData.averageNetIncomeGrowth
        ).isNotEmpty()

        if (!hasAnySignal) return null
        var valuationRaw = 0.0
        stockData.psRatio?.let { ps ->
            valuationRaw += when {
                ps < 3 -> 2.0
                ps < 8 -> 1.0
                else -> 0.0
            }
        }
        stockData.peRatio?.takeIf { it > 0 }?.let { pe ->
            valuationRaw += when {
                pe < 15 -> 2.0
                pe < 25 -> 1.0
                else -> 0.0
            }
        }

        val valuationScore = (valuationRaw / 5.0) * 4.0

        val revenueGrowthScore = scoreGrowthComponent(stockData.averageRevenueGrowth)
        val incomeGrowthScore = scoreGrowthComponent(stockData.averageNetIncomeGrowth)
        val nearTermGrowthScore = stockData.revenueGrowth?.let { scoreGrowthComponent(it) } ?: 0.0
        val growthScore = revenueGrowthScore + incomeGrowthScore + nearTermGrowthScore

        val synergyBonus = if (
            (stockData.psRatio != null && stockData.psRatio < 3) &&
            (stockData.peRatio != null && stockData.peRatio > 0 && stockData.peRatio < 15)
        ) 2.0 else 0.0

        return (valuationScore + growthScore + synergyBonus).coerceIn(0.0, 10.0)
    }

    private fun scoreGrowthComponent(growth: Double?): Double {
        val safeGrowth = growth?.takeIf { it.isFinite() }
        safeGrowth ?: return 1.5
        val capped = safeGrowth.coerceIn(-0.5, 0.6)
        val normalized = (capped + 0.5) / 1.1 // maps [-0.5, 0.6] to [0, 1]
        return (normalized * 3.0).coerceIn(0.0, 3.0)
    }

    private fun calculateResilienceScore(stockData: StockData): Double? {
        val componentScores = mutableListOf<Double>()

        stockData.netIncome?.let { income ->
            componentScores += if (income > 0) 7.5 else 3.0
        }

        stockData.debtToEquity?.takeIf { it.isFinite() }?.let { dte ->
            val capped = dte.coerceIn(0.0, 3.0)
            val normalized = 1.0 - (capped / 3.0)
            componentScores += (normalized * 10.0).coerceIn(0.0, 10.0)
        }

        stockData.freeCashFlow?.let { fcf ->
            componentScores += if (fcf > 0) 8.0 else 2.5
        }
        val assets = stockData.totalAssets
        val liabilities = stockData.totalLiabilities
        if (assets != null && liabilities != null && assets > 0) {
            val equityRatio = ((assets - liabilities) / assets).coerceIn(-1.0, 1.0)
            val scaled = ((equityRatio + 1.0) / 2.0) * 10.0
            componentScores += scaled.coerceIn(0.0, 10.0)
        }

        if (componentScores.isEmpty()) return null

        return componentScores.average().coerceIn(0.0, 10.0)
    }
}

data class MetricData(
    val value: Double?,
    val min: Double,
    val max: Double,
    val weight: Double
)

private data class CoreHealthResult(
    val score: Double?,
    val breakdown: List<MetricScore>
)
