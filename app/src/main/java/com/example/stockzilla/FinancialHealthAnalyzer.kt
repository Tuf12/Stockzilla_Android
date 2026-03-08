// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla

import java.io.Serializable
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max


enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

data class MetricConfig(
    val min: Double,
    val max: Double,
    val baseWeight: Double,
    val direction: Direction
)

private val DEFAULT_METRIC_CONFIG = mapOf(
    // Jesse metric: 10 metrics, weighted sum targets a 0-10 score.
    "revenue_growth" to MetricConfig(0.10, 0.50, 2.0, Direction.HIGHER_IS_BETTER),
    "ebitda_margin" to MetricConfig(0.10, 0.30, 1.5, Direction.HIGHER_IS_BETTER),
    "free_cash_flow" to MetricConfig(-500_000_000.0, 5_000_000_000.0, 1.5, Direction.HIGHER_IS_BETTER),
    "net_income" to MetricConfig(-1_000_000_000.0, 3_000_000_000.0, 1.0, Direction.HIGHER_IS_BETTER),
    "debt_to_equity" to MetricConfig(0.1, 2.0, 1.0, Direction.LOWER_IS_BETTER),
    "current_ratio" to MetricConfig(1.0, 3.0, 0.5, Direction.HIGHER_IS_BETTER),
    "roe" to MetricConfig(0.0, 0.30, 1.0, Direction.HIGHER_IS_BETTER),
    "pe_ratio" to MetricConfig(1.0, 200.0, 0.5, Direction.LOWER_IS_BETTER),
    "ps_ratio" to MetricConfig(0.5, 15.0, 0.5, Direction.LOWER_IS_BETTER),
    "pb_ratio" to MetricConfig(1.0, 30.0, 0.5, Direction.LOWER_IS_BETTER)
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
    val sicCode: String? = null,
    val cik: String? = null,
    val revenueGrowth: Double? = null,
    val averageRevenueGrowth: Double? = null,
    val averageNetIncomeGrowth: Double? = null,
    val netIncomeGrowth: Double? = null,
    val fcfGrowth: Double? = null,
    val averageFcfGrowth: Double? = null,
    val totalCurrentAssets: Double? = null,
    val totalCurrentLiabilities: Double? = null,
    val retainedEarnings: Double? = null,
    val netCashProvidedByOperatingActivities: Double? = null,
    val freeCashFlowMargin: Double? = null,
    val ebitdaMarginGrowth: Double? = null,
    val workingCapital: Double? = null,
    val revenueHistory: List<Double?> = emptyList(),
    val netIncomeHistory: List<Double?> = emptyList(),
    val ebitdaHistory: List<Double?> = emptyList(),
    val operatingCashFlowHistory: List<Double?>? = null,
    val freeCashFlowHistory: List<Double?>? = null,
    val sharesOutstandingHistory: List<Double?>? = null,
    val revenueTtm: Double? = null,
    val netIncomeTtm: Double? = null,
    val epsTtm: Double? = null,
    val ebitdaTtm: Double? = null,
    val freeCashFlowTtm: Double? = null,
    val operatingCashFlowTtm: Double? = null
): Serializable {

    /** TTM-preferred value for display and ratio calculations; falls back to annual 10-K. */
    val revenueDisplay: Double? get() = revenueTtm ?: revenue
    val netIncomeDisplay: Double? get() = netIncomeTtm ?: netIncome
    val epsDisplay: Double? get() = epsTtm ?: eps
    val ebitdaDisplay: Double? get() = ebitdaTtm ?: ebitda
    val freeCashFlowDisplay: Double? get() = freeCashFlowTtm ?: freeCashFlow
    val operatingCashFlowDisplay: Double? get() = operatingCashFlowTtm ?: netCashProvidedByOperatingActivities

    /** True when at least one TTM metric is available from four 10-Q quarters. */
    val hasTtm: Boolean get() = revenueTtm != null

    /**
     * Computes growth metrics from EDGAR annual 10-K history only.
     * TTM/scalar values are never mixed into YoY growth; margins use TTM-preferred values.
     */
    fun withGrowthFromHistory(): StockData {
        val rev = revenueHistory
        val ni = netIncomeHistory
        val ebitdaHist = ebitdaHistory

        val revenueGrowth = computeLatestYoYGrowth(rev)
        val averageRevenueGrowth = computeAverageYoYGrowth(rev) ?: revenueGrowth
        val averageNetIncomeGrowth = computeAverageYoYGrowth(ni)
        val netIncomeGrowth = computeLatestYoYGrowth(ni)
        val fcfGrowth = computeLatestYoYGrowth(freeCashFlowHistory.orEmpty())
        val averageFcfGrowth = computeAverageYoYGrowth(freeCashFlowHistory.orEmpty()) ?: fcfGrowth

        val effectiveRevenue = revenueDisplay
        val effectiveFcf = freeCashFlowDisplay
        val effectiveEbitda = ebitdaDisplay

        val freeCashFlowMargin = if (effectiveFcf != null && effectiveRevenue != null && abs(effectiveRevenue) > 1e-9) {
            effectiveFcf / effectiveRevenue
        } else null

        val latestEbitdaMargin = if (effectiveEbitda != null && effectiveRevenue != null && abs(effectiveRevenue) > 1e-9) {
            effectiveEbitda / effectiveRevenue
        } else null
        val priorRevenue = rev.getOrNull(1)
        val priorEbitda = ebitdaHist.getOrNull(1)
        val priorEbitdaMargin = if (priorEbitda != null && priorRevenue != null && abs(priorRevenue) > 1e-9) {
            priorEbitda / priorRevenue
        } else null
        val ebitdaMarginGrowth = if (latestEbitdaMargin != null && priorEbitdaMargin != null) {
            latestEbitdaMargin - priorEbitdaMargin
        } else null

        return copy(
            revenueGrowth = revenueGrowth ?: this.revenueGrowth,
            averageRevenueGrowth = averageRevenueGrowth ?: this.averageRevenueGrowth,
            averageNetIncomeGrowth = averageNetIncomeGrowth ?: this.averageNetIncomeGrowth,
            netIncomeGrowth = netIncomeGrowth ?: this.netIncomeGrowth,
            fcfGrowth = fcfGrowth ?: this.fcfGrowth,
            averageFcfGrowth = averageFcfGrowth ?: this.averageFcfGrowth,
            freeCashFlowMargin = freeCashFlowMargin ?: this.freeCashFlowMargin,
            ebitdaMarginGrowth = ebitdaMarginGrowth ?: this.ebitdaMarginGrowth
        )
    }
}

private fun computeLatestYoYGrowth(values: List<Double?>): Double? {
    if (values.size < 2) return null
    val current = values[0]
    val prior = values[1]
    if (current == null || prior == null || abs(prior) <= 1e-9) return null
    return (current - prior) / abs(prior)
}

private fun computeAverageYoYGrowth(values: List<Double?>): Double? {
    if (values.size < 2) return null
    val growthRates = mutableListOf<Double>()
    for (i in 0 until values.size - 1) {
        val current = values[i]
        val prior = values[i + 1]
        if (current != null && prior != null && abs(prior) > 1e-9) {
            growthRates.add((current - prior) / abs(prior))
        }
    }
    return if (growthRates.isNotEmpty()) growthRates.average() else null
}

data class HealthScore(
    val compositeScore: Int,
    val healthSubScore: Int,
    val forecastSubScore: Int,
    val zSubScore: Int,
    val breakdown: List<MetricScore>
): Serializable

data class MetricScore(
    val metric: String,
    val value: Double?,
    val normalizedPercent: Double,
    val weight: Double,
    val score: Double,
    val weightPercent: Double,
    val weightedContributionPercent: Double
): Serializable

/**
 * Scoring input contract: raw facts and deterministic derived metrics are separated.
 * Score outputs are produced from this input but are not written back into financial facts.
 */
data class ScoringInput(
    val rawFacts: Map<String, Double?>,
    val derivedMetrics: Map<String, Double?>,
    val stockData: StockData
)

object ScoringInputFactory {
    fun fromStockData(stockData: StockData): ScoringInput {
        val rawFacts = linkedMapOf(
            "revenue" to stockData.revenueDisplay,
            "net_income" to stockData.netIncomeDisplay,
            "eps" to stockData.epsDisplay,
            "ebitda" to stockData.ebitdaDisplay,
            "free_cash_flow" to stockData.freeCashFlowDisplay,
            "operating_cash_flow" to stockData.operatingCashFlowDisplay,
            "total_assets" to stockData.totalAssets,
            "total_liabilities" to stockData.totalLiabilities,
            "current_assets" to stockData.totalCurrentAssets,
            "current_liabilities" to stockData.totalCurrentLiabilities,
            "retained_earnings" to stockData.retainedEarnings,
            "outstanding_shares" to stockData.outstandingShares
        )
        val derived = linkedMapOf(
            "pe_ratio" to stockData.peRatio,
            "ps_ratio" to stockData.psRatio,
            "pb_ratio" to stockData.pbRatio,
            "roe" to stockData.roe,
            "debt_to_equity" to stockData.debtToEquity,
            "revenue_growth" to stockData.revenueGrowth,
            "average_revenue_growth" to stockData.averageRevenueGrowth,
            "average_net_income_growth" to stockData.averageNetIncomeGrowth,
            "net_income_growth" to stockData.netIncomeGrowth,
            "fcf_growth" to stockData.fcfGrowth,
            "average_fcf_growth" to stockData.averageFcfGrowth,
            "free_cash_flow_margin" to stockData.freeCashFlowMargin,
            "ebitda_margin_growth" to stockData.ebitdaMarginGrowth
        )
        return ScoringInput(rawFacts = rawFacts, derivedMetrics = derived, stockData = stockData)
    }
}

@Suppress("UNCHECKED_CAST")
class FinancialHealthAnalyzer {

    fun calculateCompositeScore(stockData: StockData, benchmarkOverride: Benchmark? = null): HealthScore {
        return calculateCompositeScore(ScoringInputFactory.fromStockData(stockData), benchmarkOverride)
    }

    fun calculateCompositeScore(scoringInput: ScoringInput, benchmarkOverride: Benchmark? = null): HealthScore {
        // Retained for call-site compatibility; benchmark no longer participates in v3 scoring.
        @Suppress("UNUSED_VARIABLE")
        val ignoredBenchmark = benchmarkOverride
        val stockData = scoringInput.stockData
        val (coreHealthScoreRaw, breakdown) = computeCoreHealthScore(stockData)
        val growthScoreRaw = calculateGrowthScore(stockData)
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
            breakdown = breakdown
        )
    }

    private fun computeCoreHealthScore(stockData: StockData): Pair<Double?, List<MetricScore>> {
        val metricData = buildJesseCoreMetricData(stockData)
        if (metricData.isEmpty()) {
            return null to emptyList()
        }

        val interim = mutableListOf<InterimMetricScore>()
        var totalWeightedScore = 0.0
        var totalAvailableWeight = 0.0

        metricData.forEach { (name, metric) ->
            val value = metric.value ?: return@forEach
            val cfg = DEFAULT_METRIC_CONFIG[name] ?: return@forEach
            val normalizedFraction = normalizeLinear(value, metric.min, metric.max, cfg.direction)
            val weighted = normalizedFraction * metric.weight
            totalWeightedScore += weighted
            totalAvailableWeight += metric.weight

            interim.add(
                InterimMetricScore(
                    metric = name,
                    value = value,
                    normalizedFraction = normalizedFraction,
                    weight = metric.weight,
                    weightedScore = weighted
                )
            )
        }

        if (interim.isEmpty() || totalAvailableWeight <= 0.0) {
            return null to emptyList()
        }

        // Re-scale by available metric weight so missing metrics do not force lower scores.
        val score = (totalWeightedScore / totalAvailableWeight) * CORE_HEALTH_SCORE_MAX
        val breakdown = interim.map { item ->
            MetricScore(
                metric = item.metric,
                value = item.value,
                normalizedPercent = item.normalizedFraction * 100.0,
                weight = item.weight,
                score = item.weightedScore,
                weightPercent = (item.weight / totalAvailableWeight) * 100.0,
                weightedContributionPercent = (item.weightedScore / totalAvailableWeight) * 100.0
            )
        }
        return score.coerceIn(0.0, 10.0) to breakdown
    }

    private fun buildJesseCoreMetricData(stockData: StockData): Map<String, MetricData> {
        val tier = toMarketCapTier(stockData.marketCap)
        val revenue = stockData.revenueDisplay
        val ebitda = stockData.ebitdaDisplay
        val ebitdaMargin = if (ebitda != null && revenue != null && abs(revenue) > 1e-9) {
            ebitda / revenue
        } else {
            null
        }
        val currentRatio = if (stockData.totalCurrentAssets != null &&
            stockData.totalCurrentLiabilities != null &&
            abs(stockData.totalCurrentLiabilities) > 1e-9
        ) {
            stockData.totalCurrentAssets / stockData.totalCurrentLiabilities
        } else {
            null
        }

        val values = linkedMapOf(
            "revenue_growth" to stockData.revenueGrowth,
            "ebitda_margin" to ebitdaMargin,
            "free_cash_flow" to stockData.freeCashFlowDisplay,
            "net_income" to stockData.netIncomeDisplay,
            "debt_to_equity" to stockData.debtToEquity,
            "current_ratio" to currentRatio,
            "roe" to stockData.roe,
            "pe_ratio" to stockData.peRatio,
            "ps_ratio" to stockData.psRatio,
            "pb_ratio" to stockData.pbRatio
        )

        val ordered = LinkedHashMap<String, MetricData>()
        values.forEach { (metric, rawValue) ->
            val value = rawValue?.takeIf { it.isFinite() } ?: return@forEach
            val cfg = getTierAdjustedConfig(metric, tier) ?: return@forEach
            ordered[metric] = MetricData(value = value, min = cfg.min, max = cfg.max, weight = cfg.baseWeight)
        }
        return ordered
    }

    private fun toMarketCapTier(marketCap: Double?): String {
        val cap = marketCap ?: return "unknown"
        return when {
            cap < 300_000_000.0 -> "micro"
            cap < 2_000_000_000.0 -> "small"
            cap < 10_000_000_000.0 -> "mid"
            cap < 200_000_000_000.0 -> "large"
            else -> "mega"
        }
    }

    private fun getTierAdjustedConfig(metric: String, tier: String): MetricConfig? {
        val cfg = DEFAULT_METRIC_CONFIG[metric] ?: return null
        return when (tier) {
            "micro", "small" -> when (metric) {
                "revenue_growth" -> cfg.copy(min = 0.20, max = 0.80, baseWeight = 2.5)
                "ebitda_margin" -> cfg.copy(min = -0.15, max = 0.25)
                "free_cash_flow" -> cfg.copy(min = -5_000_000.0, max = 10_000_000.0, baseWeight = 2.0)
                "net_income" -> cfg.copy(min = -2_000_000.0, max = 5_000_000.0)
                "pe_ratio" -> cfg.copy(min = 15.0, max = 50.0)
                "ps_ratio" -> cfg.copy(min = 0.5, max = 10.0, baseWeight = 0.25)
                "pb_ratio" -> cfg.copy(min = 1.0, max = 5.0, baseWeight = 0.25)
                "roe" -> cfg.copy(min = -0.10, max = 0.20, baseWeight = 0.5)
                else -> cfg
            }
            else -> cfg
        }
    }

    private fun calculateGrowthScore(stockData: StockData): Double? {
        val recentRevenueGrowth = stockData.revenueGrowth
        val components = listOf(
            tierScoreForGrowth(stockData.averageRevenueGrowth),
            tierScoreForGrowth(recentRevenueGrowth),
            tierScoreForGrowth(stockData.averageNetIncomeGrowth),
            tierScoreForGrowth(stockData.netIncomeGrowth),
            tierScoreForGrowth(stockData.fcfGrowth),
            tierScoreForGrowth(stockData.averageFcfGrowth)
        )

        val presentScores = components.mapNotNull { score -> score?.takeIf { it.isFinite() } }
        if (presentScores.isEmpty()) return null

        var base = presentScores.average()
        val avgRevenueGrowth = stockData.averageRevenueGrowth
        if (recentRevenueGrowth != null && avgRevenueGrowth != null) {
            base += when {
                recentRevenueGrowth > avgRevenueGrowth -> 0.5
                recentRevenueGrowth < avgRevenueGrowth -> -0.5
                else -> 0.0
            }
        }
        return base.coerceIn(0.0, 10.0)
    }

    private fun tierScoreForGrowth(value: Double?): Double? {
        val v = value?.takeIf { it.isFinite() } ?: return null
        return when {
            v <= 0.0 -> 0.0
            v <= 0.05 -> 1.0
            v <= 0.10 -> 2.0
            v <= 0.15 -> 3.0
            v <= 0.20 -> 4.0
            v <= 0.25 -> 5.0
            v <= 0.30 -> 6.0
            v <= 0.40 -> 7.0
            v <= 0.50 -> 8.0
            v <= 0.75 -> 9.0
            else -> 10.0
        }
    }

    private fun calculateResilienceScore(stockData: StockData): Double? {
        val inputs = buildResilienceInputs(stockData) ?: return null

        val workingCapitalToAssets = inputs.workingCapitalToAssets ?: return null
        val retainedEarningsToAssets = inputs.retainedEarningsToAssets ?: return null
        val ebitToAssets = inputs.ebitToAssets ?: return null
        val marketValueToLiabilities = inputs.marketValueToLiabilities ?: return null

        val baseLevel = if (EdgarConcepts.isManufacturingSic(stockData.sicCode)) {
            // Original Z-Score (manufacturing): Z = 1.2*A + 1.4*B + 3.3*C + 0.6*D + 1.0*E
            val revenueToAssets = inputs.revenueToAssets ?: return null
            val altmanZ = (1.2 * workingCapitalToAssets) +
                    (1.4 * retainedEarningsToAssets) +
                    (3.3 * ebitToAssets) +
                    (0.6 * marketValueToLiabilities) +
                    (1.0 * revenueToAssets)
            if (!altmanZ.isFinite()) return null
            when {
                altmanZ < 1.81 -> 0
                altmanZ < 2.3 -> 1
                altmanZ < 2.99 -> 2
                else -> 3
            }
        } else {
            // Z''-Score (non-manufacturing): Z'' = 6.56*A + 3.26*B + 6.72*C + 1.05*D (no E)
            val altmanZPrime = (6.56 * workingCapitalToAssets) +
                    (3.26 * retainedEarningsToAssets) +
                    (6.72 * ebitToAssets) +
                    (1.05 * marketValueToLiabilities)
            if (!altmanZPrime.isFinite()) return null
            when {
                altmanZPrime < 1.10 -> 0
                altmanZPrime < 1.85 -> 1
                altmanZPrime < 2.60 -> 2
                else -> 3
            }
        }

        val adjustedLevel = if (inputs.hasTwoYearLosses) {
            max(baseLevel - 1, 0)
        } else {
            baseLevel
        }

        return adjustedLevel.toDouble()
    }

    private fun normalizeLinear(value: Double, min: Double, max: Double, direction: Direction): Double {
        val denominator = max - min
        if (!denominator.isFinite() || denominator <= 0.0) return 0.5
        val fraction = ((value - min) / denominator).coerceIn(0.0, 1.0)
        return when (direction) {
            Direction.HIGHER_IS_BETTER -> fraction
            Direction.LOWER_IS_BETTER -> 1.0 - fraction
        }
    }
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

    val ebitProxy = stockData.ebitdaDisplay
    val ebitRatio = ebitProxy?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }

    // Altman formula uses the direct market-value-to-liabilities ratio.
    val marketValueRatio = stockData.marketCap?.let {
        (it / totalLiabilities).takeIf { ratio -> ratio.isFinite() }
    }

    val revenueRatio = stockData.revenueDisplay?.let {
        (it / totalAssets).takeIf { ratio -> ratio.isFinite() }
    }

    val netIncome = stockData.netIncome ?: stockData.netIncomeHistory.firstOrNull { it != null }
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
private const val CORE_WEIGHT = 0.4
private const val GROWTH_WEIGHT = 0.4
private const val RESILIENCE_WEIGHT = 0.2
private const val RESILIENCE_LEVEL_MAX = 3.0
private const val CORE_HEALTH_SCORE_MAX = 10.0

private data class InterimMetricScore(
    val metric: String,
    val value: Double?,
    val normalizedFraction: Double,
    val weight: Double,
    val weightedScore: Double
)

data class MetricData(
    val value: Double?,
    val min: Double,
    val max: Double,
    val weight: Double
)
