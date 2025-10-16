// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla

import java.io.Serializable
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.max


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
    "eps"            to MetricConfig(-1.0, 5.0, 0.08, Direction.HIGHER_IS_BETTER),
    "pe_ratio"       to MetricConfig(5.0, 50.0, 0.08, Direction.LOWER_IS_BETTER, transform = "log"),
    "ps_ratio"       to MetricConfig(1.0, 15.0, 0.08, Direction.LOWER_IS_BETTER, transform = "log"),
    "roe"            to MetricConfig(0.0, 0.30, 0.12, Direction.HIGHER_IS_BETTER),
    "debt_to_equity" to MetricConfig(0.0, 2.0, 0.08, Direction.LOWER_IS_BETTER),
    "pb_ratio"       to MetricConfig(0.5, 15.0, 0.06, Direction.LOWER_IS_BETTER, transform = "log"),
    "ebitda"         to MetricConfig(-100_000_000.0, 500_000_000.0, 0.10, Direction.HIGHER_IS_BETTER),
    "free_cash_flow" to MetricConfig(-200_000_000.0, 400_000_000.0, 0.08, Direction.HIGHER_IS_BETTER),
    "operating_cash_flow" to MetricConfig(-150_000_000.0, 500_000_000.0, 0.07, Direction.HIGHER_IS_BETTER),
    "free_cash_flow_margin" to MetricConfig(-0.30, 0.30, 0.06, Direction.HIGHER_IS_BETTER),
    "net_margin"     to MetricConfig(-0.20, 0.35, 0.07, Direction.HIGHER_IS_BETTER),
    "ebitda_margin"  to MetricConfig(-0.10, 0.40, 0.06, Direction.HIGHER_IS_BETTER),
    "current_ratio"  to MetricConfig(0.7, 3.0, 0.06, Direction.HIGHER_IS_BETTER),
    "liability_to_asset_ratio" to MetricConfig(0.2, 1.2, 0.06, Direction.LOWER_IS_BETTER),
    "working_capital_ratio" to MetricConfig(-0.20, 0.40, 0.05, Direction.HIGHER_IS_BETTER),
    "retained_earnings" to MetricConfig(-5_000_000_000.0, 200_000_000_000.0, 0.05, Direction.HIGHER_IS_BETTER, transform = "log"),
    "outstanding_shares" to MetricConfig(5_000_000.0, 10_000_000_000.0, 0.04, Direction.LOWER_IS_BETTER, transform = "log"),
    "total_assets"   to MetricConfig(50_000_000.0, 2_000_000_000_000.0, 0.05, Direction.HIGHER_IS_BETTER, transform = "log"),
    "total_liabilities" to MetricConfig(10_000_000.0, 1_000_000_000_000.0, 0.05, Direction.LOWER_IS_BETTER, transform = "log")
)
private val ABSOLUTE_METRICS = setOf(
    "revenue",
    "net_income",
    "ebitda",
    "free_cash_flow",
    "operating_cash_flow",
    "retained_earnings",
    "total_assets",
    "total_liabilities"
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
    ),
    "Energy" to mapOf(
        "revenue" to (0.0 to 5_000_000_000.0),
        "net_income" to (-200_000_000.0 to 600_000_000.0),
        "eps" to (-3.0 to 8.0),
        "pe_ratio" to (4.0 to 35.0),
        "ps_ratio" to (0.5 to 8.0),
        "roe" to (-0.15 to 0.35),
        "debt_to_equity" to (0.0 to 2.5),
        "pb_ratio" to (0.4 to 8.0),
        "ebitda" to (-400_000_000.0 to 1_500_000_000.0),
        "outstanding_shares" to (5_000_000.0 to 4_000_000_000.0),
        "total_assets" to (80_000_000.0 to 300_000_000_000.0),
        "total_liabilities" to (40_000_000.0 to 150_000_000_000.0)
    ),
    "Utilities" to mapOf(
        "revenue" to (100_000_000.0 to 20_000_000_000.0),
        "net_income" to (-100_000_000.0 to 2_000_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (8.0 to 25.0),
        "ps_ratio" to (0.4 to 5.0),
        "roe" to (0.0 to 0.20),
        "debt_to_equity" to (0.3 to 3.0),
        "pb_ratio" to (0.5 to 4.0),
        "ebitda" to (0.0 to 5_000_000_000.0),
        "total_assets" to (500_000_000.0 to 400_000_000_000.0),
        "total_liabilities" to (300_000_000.0 to 300_000_000_000.0)
    ),
    "Consumer Cyclical" to mapOf(
        "revenue" to (10_000_000.0 to 50_000_000_000.0),
        "net_income" to (-200_000_000.0 to 3_000_000_000.0),
        "eps" to (-2.0 to 10.0),
        "pe_ratio" to (5.0 to 40.0),
        "ps_ratio" to (0.2 to 8.0),
        "roe" to (-0.2 to 0.4),
        "debt_to_equity" to (0.0 to 3.0),
        "pb_ratio" to (0.4 to 8.0),
        "ebitda" to (-500_000_000.0 to 5_000_000_000.0),
        "total_assets" to (100_000_000.0 to 250_000_000_000.0),
        "total_liabilities" to (50_000_000.0 to 150_000_000_000.0)
    ),
    "Consumer Defensive" to mapOf(
        "revenue" to (50_000_000.0 to 100_000_000_000.0),
        "net_income" to (-100_000_000.0 to 5_000_000_000.0),
        "eps" to (-1.0 to 8.0),
        "pe_ratio" to (10.0 to 35.0),
        "ps_ratio" to (0.3 to 6.0),
        "roe" to (0.0 to 0.35),
        "debt_to_equity" to (0.2 to 2.0),
        "pb_ratio" to (0.4 to 8.0),
        "ebitda" to (0.0 to 8_000_000_000.0),
        "total_assets" to (200_000_000.0 to 300_000_000_000.0),
        "total_liabilities" to (100_000_000.0 to 150_000_000_000.0)
    ),
    "Materials" to mapOf(
        "revenue" to (10_000_000.0 to 40_000_000_000.0),
        "net_income" to (-100_000_000.0 to 2_000_000_000.0),
        "eps" to (-2.0 to 6.0),
        "pe_ratio" to (5.0 to 30.0),
        "ps_ratio" to (0.3 to 5.0),
        "roe" to (-0.1 to 0.3),
        "debt_to_equity" to (0.0 to 2.5),
        "pb_ratio" to (0.3 to 6.0),
        "ebitda" to (-200_000_000.0 to 3_000_000_000.0),
        "total_assets" to (50_000_000.0 to 150_000_000_000.0),
        "total_liabilities" to (25_000_000.0 to 80_000_000_000.0)
    ),
    "Real Estate" to mapOf(
        "revenue" to (5_000_000.0 to 10_000_000_000.0),
        "net_income" to (-100_000_000.0 to 2_000_000_000.0),
        "eps" to (-1.0 to 6.0),
        "pe_ratio" to (4.0 to 25.0),
        "ps_ratio" to (0.3 to 5.0),
        "roe" to (0.0 to 0.25),
        "debt_to_equity" to (0.5 to 3.5),
        "pb_ratio" to (0.4 to 5.0),
        "ebitda" to (-200_000_000.0 to 3_000_000_000.0),
        "total_assets" to (100_000_000.0 to 200_000_000_000.0),
        "total_liabilities" to (50_000_000.0 to 120_000_000_000.0)
    ),
    "Communication Services" to mapOf(
        "revenue" to (50_000_000.0 to 100_000_000_000.0),
        "net_income" to (-500_000_000.0 to 10_000_000_000.0),
        "eps" to (-3.0 to 15.0),
        "pe_ratio" to (5.0 to 45.0),
        "ps_ratio" to (0.3 to 10.0),
        "roe" to (-0.15 to 0.45),
        "debt_to_equity" to (0.0 to 3.0),
        "pb_ratio" to (0.4 to 9.0),
        "ebitda" to (-1_000_000_000.0 to 20_000_000_000.0),
        "total_assets" to (500_000_000.0 to 400_000_000_000.0),
        "total_liabilities" to (250_000_000.0 to 300_000_000_000.0)
    ),

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
    val averageNetIncomeGrowth: Double? = null,
    val totalCurrentAssets: Double? = null,
    val totalCurrentLiabilities: Double? = null,
    val retainedEarnings: Double? = null,
    val netCashProvidedByOperatingActivities: Double? = null,
    val freeCashFlowMargin: Double? = null,
    val ebitdaMarginGrowth: Double? = null,
    val workingCapital: Double? = null,
    val revenueHistory: List<Double?> = emptyList(),
    val netIncomeHistory: List<Double?> = emptyList(),
    val ebitdaHistory: List<Double?> = emptyList()
): Serializable

data class HealthScore(
    val compositeScore: Int,
    val healthSubScore: Int,
    val forecastSubScore: Int,
    val zSubScore: Int,
    val breakdown: List<MetricScore>,
    val valuationAssessment: ValuationAssessment? = null
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
        val valuationAssessment = assessValuation(stockData)
        val (coreHealthScoreRaw, breakdown) = computeCoreHealthScore(stockData)
        val growthScoreRaw = calculateGrowthScore(stockData, valuationAssessment)
        val resilienceScoreRaw = calculateResilienceScore(stockData)

        val coreHealthScore = coreHealthScoreRaw ?: DEFAULT_NEUTRAL_SCORE
        val growthScore = growthScoreRaw ?: DEFAULT_NEUTRAL_SCORE
        val resilienceLevel = resilienceScoreRaw ?: DEFAULT_RESILIENCE_NEUTRAL_LEVEL
        val resilienceScore = ((resilienceLevel / RESILIENCE_LEVEL_MAX)
            .coerceIn(0.0, 1.0)) * 10.0

        val composite = (coreHealthScore * CORE_WEIGHT) +
                (growthScore * GROWTH_WEIGHT) +
                (resilienceScore * RESILIENCE_WEIGHT)

        return HealthScore(
            compositeScore = composite.roundToInt().coerceIn(0, 10),
            healthSubScore = coreHealthScore.roundToInt().coerceIn(0, 10),
            forecastSubScore = growthScore.roundToInt().coerceIn(0, 10),
            zSubScore = resilienceLevel.roundToInt().coerceIn(0, 3),
            breakdown = breakdown,
            valuationAssessment = valuationAssessment
        )
    }

    private fun computeCoreHealthScore(stockData: StockData): Pair<Double?, List<MetricScore>> {
        val metricData = buildMetricData(stockData)
        if (metricData.isEmpty()) {
            return null to emptyList()
        }

        var totalWeighted = 0.0
        var totalWeight = 0.0
        val breakdown = mutableListOf<MetricScore>()

        metricData.forEach { (name, metric) ->
            val value = metric.value ?: return@forEach
            val cfg = DEFAULT_METRIC_CONFIG[name]
            val normalizedFraction = if (cfg != null) {
                normalizeValueWithConfig(value, metric.min, metric.max, cfg)
            } else {
                normalizeValue(value, metric.min, metric.max)
            }

            val weighted = normalizedFraction * metric.weight
            totalWeighted += weighted
            totalWeight += metric.weight

            breakdown.add(
                MetricScore(
                    metric = name,
                    value = value,
                    normalizedPercent = normalizedFraction * 100.0,
                    weight = metric.weight,
                    score = weighted
                )
            )
        }

        if (totalWeight <= 0.0) {
            return null to breakdown
        }

        val score = (totalWeighted / totalWeight) * 10.0
        return score.coerceIn(0.0, 10.0) to breakdown
    }

    private fun buildMetricData(
        stockData: StockData
    ): Map<String, MetricData> {
        val sector = stockData.sector ?: "Unknown"
        val revenueGrowthSignal = stockData.revenueGrowth ?: stockData.averageRevenueGrowth
        val weights = getSectorSpecificWeights(sector, stockData.marketCap, revenueGrowthSignal)

        fun md(metric: String, value: Double?): MetricData? {
            val sanitizedValue = value?.takeIf { it.isFinite() }
            sanitizedValue ?: return null
            val (min, max) = getSectorRange(metric, sector, stockData.marketCap)
            if (min >= max) return null
            val w = weights[metric] ?: DEFAULT_METRIC_CONFIG[metric]?.baseWeight ?: return null
            return MetricData(value = sanitizedValue, min = min, max = max, weight = w)
        }
        val metricValues = computeMetricValues(stockData)
        val orderedMetrics = LinkedHashMap<String, MetricData>()
        DEFAULT_METRIC_CONFIG.keys.forEach { metric ->
            val value = metricValues[metric]
            md(metric, value)?.let { orderedMetrics[metric] = it }
        }

        return orderedMetrics
    }

    private fun computeMetricValues(stockData: StockData): Map<String, Double?> {
        val metrics = LinkedHashMap<String, Double?>()

        metrics["revenue"] = stockData.revenue
        metrics["net_income"] = stockData.netIncome
        metrics["eps"] = stockData.eps
        metrics["pe_ratio"] = stockData.peRatio
        metrics["ps_ratio"] = stockData.psRatio
        metrics["roe"] = stockData.roe
        metrics["debt_to_equity"] = stockData.debtToEquity
        metrics["pb_ratio"] = stockData.pbRatio
        metrics["ebitda"] = stockData.ebitda
        metrics["free_cash_flow"] = stockData.freeCashFlow
        metrics["operating_cash_flow"] = stockData.netCashProvidedByOperatingActivities
        metrics["free_cash_flow_margin"] = stockData.freeCashFlowMargin

        val revenue = stockData.revenue
        val totalAssets = stockData.totalAssets
        val totalLiabilities = stockData.totalLiabilities
        val netIncome = stockData.netIncome
        val ebitda = stockData.ebitda

        metrics["net_margin"] = if (revenue != null && revenue != 0.0 && netIncome != null) {
            netIncome / revenue
        } else {
            null
        }

        metrics["ebitda_margin"] = if (revenue != null && revenue != 0.0 && ebitda != null) {
            ebitda / revenue
        } else {
            null
        }

        val currentAssets = stockData.totalCurrentAssets
        val currentLiabilities = stockData.totalCurrentLiabilities
        metrics["current_ratio"] = if (currentAssets != null && currentLiabilities != null && currentLiabilities != 0.0) {
            currentAssets / currentLiabilities
        } else {
            null
        }

        metrics["liability_to_asset_ratio"] = if (totalAssets != null && totalAssets != 0.0 && totalLiabilities != null) {
            totalLiabilities / totalAssets
        } else {
            null
        }

        val workingCapital = stockData.workingCapital ?: run {
            if (currentAssets != null && currentLiabilities != null) {
                currentAssets - currentLiabilities
            } else {
                null
            }
        }
        metrics["working_capital_ratio"] = if (workingCapital != null && totalAssets != null && totalAssets != 0.0) {
            workingCapital / totalAssets
        } else {
            null
        }

        metrics["retained_earnings"] = stockData.retainedEarnings
        metrics["outstanding_shares"] = stockData.outstandingShares
        metrics["total_assets"] = totalAssets
        metrics["total_liabilities"] = totalLiabilities

        return metrics
    }

    private fun calculateGrowthScore(
        stockData: StockData,
        valuationAssessment: ValuationAssessment?
    ): Double? {
        val components = listOf(
            normalizeGrowthRate(stockData.averageRevenueGrowth) to 0.20,
            normalizeGrowthRate(stockData.averageNetIncomeGrowth) to 0.20,
            normalizeGrowthRate(stockData.revenueGrowth) to 0.30,
            normalizeMargin(stockData.freeCashFlowMargin) to 0.15,
            normalizeMarginTrend(stockData.ebitdaMarginGrowth) to 0.10,
            valuationAssessment?.normalizedScore to 0.05
        )

        return weightedScoreFromNormalizedComponents(components)
    }

    private fun calculateResilienceScore(stockData: StockData): Double? {
        val inputs = buildResilienceInputs(stockData) ?: return null

        val workingCapitalToAssets = inputs.workingCapitalToAssets ?: return null
        val retainedEarningsToAssets = inputs.retainedEarningsToAssets ?: return null
        val ebitToAssets = inputs.ebitToAssets ?: return null
        val marketValueToLiabilities = inputs.marketValueToLiabilities ?: return null
        val revenueToAssets = inputs.revenueToAssets ?: return null

        val altmanZ = (1.2 * workingCapitalToAssets) +
                (1.4 * retainedEarningsToAssets) +
                (3.3 * ebitToAssets) +
                (0.6 * marketValueToLiabilities) +
                (1.0 * revenueToAssets)

        if (!altmanZ.isFinite()) {
            return null

        }

        val baseLevel = when {
            altmanZ < 1.81 -> 0
            altmanZ < 2.3 -> 1
            altmanZ < 2.99 -> 2
            else -> 3
        }
        val adjustedLevel = if (inputs.hasTwoYearLosses) {
            max(baseLevel - 1, 0)
        } else {
            baseLevel
        }

        return adjustedLevel.toDouble()
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
                    baseWeights["roe"] =
                        (baseWeights["roe"] ?: 0.0) * if (isHighGrowth) 1.15 else 1.3
                    baseWeights["debt_to_equity"] =
                        (baseWeights["debt_to_equity"] ?: 0.0) * if (isHighGrowth) 1.1 else 1.25
                    baseWeights["ps_ratio"] = (baseWeights["ps_ratio"] ?: 0.0) * 0.85
                    baseWeights["revenue"] =
                        (baseWeights["revenue"] ?: 0.0) * if (isHighGrowth) 1.05 else 0.9
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

    private fun getSectorRange(
        metric: String,
        sector: String,
        marketCap: Double?
    ): Pair<Double, Double> {
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
            val epsilon = abs(max) * 0.01 + 1e-6
            max = min + epsilon
        }

        return min to max
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
            else -> x
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
            Direction.LOWER_IS_BETTER -> 1.0 - normalized

        }
    }


    private fun normalizeValue(value: Double, min: Double, max: Double): Double {
        val denominator = (max - min).takeIf { it > 0.0 } ?: return 0.5
        val frac = (value - min) / denominator
        return sigmoidNormalizeFraction(frac)
    }
}

    private fun sigmoidNormalizeFraction(frac: Double): Double {
        if (!frac.isFinite()) return 0.5
        val scaled = (frac - 0.5) * 6.0
        val logistic = 1.0 / (1.0 + exp(-scaled))
        return logistic.coerceIn(0.0, 1.0)
    }


private fun assessValuation(stockData: StockData): ValuationAssessment? {
    val benchmark = BenchmarkData.getBenchmarkAverages(stockData)
    val hasPositiveIncome = (stockData.netIncome ?: 0.0) > 0

    val ratioType: String
    val ratio: Double?
    val benchmarkValue: Double?

    if (hasPositiveIncome) {
        ratioType = "P/E"
        ratio = stockData.peRatio
        benchmarkValue = benchmark.peAvg
    } else {
        ratioType = "P/S"
        ratio = stockData.psRatio
        benchmarkValue = benchmark.psAvg
    }

    if (ratio == null || ratio <= 0 || benchmarkValue == null || benchmarkValue <= 0) {
        return ValuationAssessment(
            ratioType = ratioType,
            ratio = ratio,
            benchmark = benchmarkValue,
            deviation = null,
            classification = ValuationClassification.UNKNOWN,
            normalizedScore = null
        )
    }

    val deviation = (ratio - benchmarkValue) / benchmarkValue
    val classification = when {
        deviation <= -0.15 -> ValuationClassification.UNDERVALUED
        deviation >= 0.15 -> ValuationClassification.OVERVALUED
        else -> ValuationClassification.FAIRLY_VALUED
    }

    val clamped = deviation.coerceIn(-1.0, 1.0)
    val fraction = ((-clamped) + 1.0) / 2.0
    val normalizedScore = sigmoidNormalizeFraction(fraction)

    return ValuationAssessment(
        ratioType = ratioType,
        ratio = ratio,
        benchmark = benchmarkValue,
        deviation = deviation,
        classification = classification,
        normalizedScore = normalizedScore
    )
}

private fun weightedScoreFromNormalizedComponents(
    components: List<Pair<Double?, Double>>
): Double? {
    var totalWeight = 0.0
    var weighted = 0.0
    var hasActualValue = false

    components.forEach { (value, weight) ->
        if (weight <= 0.0) return@forEach
        val normalized = value ?: DEFAULT_NEUTRAL_FRACTION
        if (value != null) {
            hasActualValue = true
        }
        weighted += normalized * weight
        totalWeight += weight
    }

    if (totalWeight <= 0.0) {
        return null
    }

    val normalizedScore = (weighted / totalWeight).coerceIn(0.0, 1.0)
    val scaledScore = normalizedScore * 10.0
    return if (hasActualValue) scaledScore else null
}

private fun normalizeGrowthRate(growth: Double?): Double? {
    growth ?: return null
    val capped = growth.coerceIn(-0.5, 0.6)
    val fraction = (capped + 0.5) / 1.1
    return sigmoidNormalizeFraction(fraction)
}

private fun normalizeMargin(margin: Double?): Double? {
    margin ?: return null
    val capped = margin.coerceIn(-0.3, 0.3)
    val fraction = (capped + 0.3) / 0.6
    return sigmoidNormalizeFraction(fraction)
}

private fun normalizeMarginTrend(trend: Double?): Double? {
    trend ?: return null
    val capped = trend.coerceIn(-0.2, 0.2)
    val fraction = (capped + 0.2) / 0.4
    return sigmoidNormalizeFraction(fraction)
}

private fun buildResilienceInputs(stockData: StockData): ResilienceInputs? {
    val totalAssets = stockData.totalAssets?.takeIf { it > 0 } ?: return null
    val totalLiabilities = stockData.totalLiabilities?.takeIf { it > 0 } ?: return null
    val currentAssets = stockData.totalCurrentAssets
    val currentLiabilities = stockData.totalCurrentLiabilities

    val workingCapital = stockData.workingCapital ?: run {
        if (currentAssets != null && currentLiabilities != null) {
            currentAssets - currentLiabilities
        } else {
            null
        }
    }

    val workingCapitalRatio = workingCapital?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }

    val retainedEarningsRatio = stockData.retainedEarnings?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }

    val ebitProxy = stockData.ebitda

    val ebitRatio = ebitProxy?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }

    val marketValueRatio = stockData.marketCap?.let {
        (it / totalLiabilities).takeIf { ratio -> ratio.isFinite() }
    }

    val revenueRatio = stockData.revenue?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }
    val netIncome = stockData.netIncome
        ?: stockData.netIncomeHistory.firstOrNull { it != null }

    val history = if (stockData.netIncomeHistory.isNotEmpty()) {
        stockData.netIncomeHistory
    } else {
        listOf(netIncome)
    }

    return ResilienceInputs(
        workingCapitalToAssets = workingCapitalRatio,
        retainedEarningsToAssets = retainedEarningsRatio,
        ebitToAssets = ebitRatio,
        marketValueToLiabilities = marketValueRatio,
        revenueToAssets = revenueRatio,
        hasTwoYearLosses = hasConsecutiveNetLosses(history)
    )
}

private fun hasConsecutiveNetLosses(history: List<Double?>): Boolean {
    val relevant = history.take(2)
    if (relevant.size < 2) return false
    return relevant.all { value -> value != null && value < 0 }
}

private data class ResilienceInputs(
    val workingCapitalToAssets: Double?,
    val retainedEarningsToAssets: Double?,
    val ebitToAssets: Double?,
    val marketValueToLiabilities: Double?,
    val revenueToAssets: Double?,
    val hasTwoYearLosses: Boolean,

)

private const val DEFAULT_NEUTRAL_SCORE = 5.0
private const val DEFAULT_RESILIENCE_NEUTRAL_LEVEL = 1.5
private const val DEFAULT_NEUTRAL_FRACTION = 0.5
private const val CORE_WEIGHT = 0.4
private const val GROWTH_WEIGHT = 0.3
private const val RESILIENCE_WEIGHT = 0.3
private const val RESILIENCE_LEVEL_MAX = 3.0


enum class ValuationClassification { UNDERVALUED, FAIRLY_VALUED, OVERVALUED, UNKNOWN }

data class ValuationAssessment(
    val ratioType: String,
    val ratio: Double?,
    val benchmark: Double?,
    val deviation: Double?,
    val classification: ValuationClassification,
    val normalizedScore: Double?
) : Serializable

data class MetricData(
    val value: Double?,
    val min: Double,
    val max: Double,
    val weight: Double
)
