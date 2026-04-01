// FinancialHealthAnalyzer.kt - Core financial analysis logic ported to Kotlin
package com.example.stockzilla.scoring

import com.example.stockzilla.sec.EdgarConcepts
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max

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
    /** Operating income plus depreciation and amortization when both exist (annual 10-K); see [ebitdaTtm]. */
    val ebitda: Double?,
    val costOfGoodsSold: Double? = null,
    val grossProfit: Double? = null,
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
    val ocfGrowth: Double? = null,
    val averageOcfGrowth: Double? = null,
    val totalCurrentAssets: Double? = null,
    val totalCurrentLiabilities: Double? = null,
    val totalAssetsHistory: List<Double?> = emptyList(),
    val totalCurrentAssetsHistory: List<Double?> = emptyList(),
    val totalCurrentLiabilitiesHistory: List<Double?> = emptyList(),
    val longTermDebtHistory: List<Double?> = emptyList(),
    val retainedEarnings: Double? = null,
    val netCashProvidedByOperatingActivities: Double? = null,
    val freeCashFlowMargin: Double? = null,
    val ebitdaMarginGrowth: Double? = null,
    val grossMarginGrowth: Double? = null,
    val workingCapital: Double? = null,
    val revenueHistory: List<Double?> = emptyList(),
    val netIncomeHistory: List<Double?> = emptyList(),
    val ebitdaHistory: List<Double?> = emptyList(),
    val costOfGoodsSoldHistory: List<Double?> = emptyList(),
    val grossProfitHistory: List<Double?> = emptyList(),
    val operatingCashFlowHistory: List<Double?>? = null,
    /** Annual 10-K capex (cash flow statement); TTM via [capexTtm]. */
    val capex: Double? = null,
    val capexTtm: Double? = null,
    val capexHistory: List<Double?>? = null,
    /** D&amp;A from income or cash flow tags; TTM via [depreciationAmortizationTtm]. */
    val depreciationAmortization: Double? = null,
    val depreciationAmortizationTtm: Double? = null,
    val depreciationAmortizationHistory: List<Double?>? = null,
    val freeCashFlowHistory: List<Double?>? = null,
    val sharesOutstandingHistory: List<Double?>? = null,
    /** Fiscal years aligned with other annual series (10-K); summed debt tags or TOTAL_DEBT override. */
    val totalDebtHistory: List<Double?> = emptyList(),
    val revenueTtm: Double? = null,
    val netIncomeTtm: Double? = null,
    val epsTtm: Double? = null,
    val ebitdaTtm: Double? = null,
    val costOfGoodsSoldTtm: Double? = null,
    val freeCashFlowTtm: Double? = null,
    val operatingCashFlowTtm: Double? = null,
    /** Latest balance-sheet total debt (summed standard tags or override). */
    val totalDebt: Double? = null,
    val cashAndEquivalents: Double? = null,
    val accountsReceivable: Double? = null,
    /** Same fact as EBIT ([OperatingIncomeLoss]); tag fix uses [com.example.stockzilla.sec.EdgarMetricKey.EBIT]. */
    val operatingIncome: Double? = null,
    val operatingIncomeTtm: Double? = null
): Serializable {

    /** TTM-preferred value for display and ratio calculations; falls back to annual 10-K. */
    val revenueDisplay: Double? get() = revenueTtm ?: revenue
    val netIncomeDisplay: Double? get() = netIncomeTtm ?: netIncome
    val epsDisplay: Double? get() = epsTtm ?: eps
    val ebitdaDisplay: Double? get() = ebitdaTtm ?: ebitda
    val costOfGoodsSoldDisplay: Double? get() = costOfGoodsSoldTtm ?: costOfGoodsSold
    val freeCashFlowDisplay: Double? get() = freeCashFlowTtm ?: freeCashFlow
    val operatingCashFlowDisplay: Double? get() = operatingCashFlowTtm ?: netCashProvidedByOperatingActivities
    val capexDisplay: Double? get() = capexTtm ?: capex
    val depreciationAmortizationDisplay: Double? get() = depreciationAmortizationTtm ?: depreciationAmortization
    val operatingIncomeDisplay: Double? get() = operatingIncomeTtm ?: operatingIncome
    val grossProfitDisplay: Double?
        get() = when {
            revenueDisplay != null && costOfGoodsSoldDisplay != null -> revenueDisplay!! - costOfGoodsSoldDisplay!!
            else -> grossProfit
        }

    /** True when at least one TTM metric is available from four 10-Q quarters. */
    val hasTtm: Boolean get() = revenueTtm != null

    /**
     * Computes growth metrics from EDGAR annual 10-K history only.
     * TTM/scalar values are never mixed into YoY growth.
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
        val ocfGrowth = computeLatestYoYGrowth(operatingCashFlowHistory.orEmpty())
        val averageOcfGrowth = computeAverageYoYGrowth(operatingCashFlowHistory.orEmpty()) ?: ocfGrowth

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

        val currentAnnualRevenue = rev.getOrNull(0)
        val currentAnnualGrossProfit = grossProfitHistory.getOrNull(0)
        val latestGrossMargin = if (currentAnnualGrossProfit != null && currentAnnualRevenue != null && abs(currentAnnualRevenue) > 1e-9) {
            currentAnnualGrossProfit / currentAnnualRevenue
        } else null
        val priorGrossProfit = grossProfitHistory.getOrNull(1)
        val priorGrossMargin = if (priorGrossProfit != null && priorRevenue != null && abs(priorRevenue) > 1e-9) {
            priorGrossProfit / priorRevenue
        } else null
        val grossMarginGrowth = if (latestGrossMargin != null && priorGrossMargin != null) {
            latestGrossMargin - priorGrossMargin
        } else null

        return copy(
            revenueGrowth = revenueGrowth ?: this.revenueGrowth,
            averageRevenueGrowth = averageRevenueGrowth ?: this.averageRevenueGrowth,
            averageNetIncomeGrowth = averageNetIncomeGrowth ?: this.averageNetIncomeGrowth,
            netIncomeGrowth = netIncomeGrowth ?: this.netIncomeGrowth,
            fcfGrowth = fcfGrowth ?: this.fcfGrowth,
            averageFcfGrowth = averageFcfGrowth ?: this.averageFcfGrowth,
            ocfGrowth = ocfGrowth ?: this.ocfGrowth,
            averageOcfGrowth = averageOcfGrowth ?: this.averageOcfGrowth,
            freeCashFlowMargin = freeCashFlowMargin ?: this.freeCashFlowMargin,
            ebitdaMarginGrowth = ebitdaMarginGrowth ?: this.ebitdaMarginGrowth,
            grossMarginGrowth = grossMarginGrowth ?: this.grossMarginGrowth
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
            "outstanding_shares" to stockData.outstandingShares,
            "total_debt" to stockData.totalDebt,
            "cash_and_equivalents" to stockData.cashAndEquivalents,
            "accounts_receivable" to stockData.accountsReceivable,
            "operating_income" to stockData.operatingIncomeDisplay,
            "capex" to stockData.capexDisplay,
            "depreciation_amortization" to stockData.depreciationAmortizationDisplay
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
        val stockData = scoringInput.stockData
        val (coreHealthScoreRaw, breakdown) = computeCoreHealthScore(stockData)
        val growthScoreRaw = calculateGrowthScore(stockData)
        val resilienceScoreRaw = calculateResilienceScore(stockData)

        // Do not invent neutral scores when data is missing.
        // If a pillar cannot be scored, it contributes 0 and the UI should explain why.
        val coreHealthScore = coreHealthScoreRaw ?: 0.0
        val growthScore = growthScoreRaw ?: 0.0
        val resilienceLevel = resilienceScoreRaw ?: 0.0
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
        val tests = evaluatePiotroskiTests(stockData)

        val testSharePercent = 100.0 / tests.size.toDouble()
        val contributionPoints = CORE_HEALTH_SCORE_MAX / tests.size.toDouble()
        val breakdown = tests.map { test ->
            val passed = test.pass == true
            val missing = test.pass == null
            MetricScore(
                metric = test.metric,
                value = when {
                    missing -> null
                    passed -> 1.0
                    else -> 0.0
                },
                normalizedPercent = when {
                    missing -> 0.0
                    passed -> 100.0
                    else -> 0.0
                },
                weight = testSharePercent / 100.0,
                score = if (passed) contributionPoints else 0.0,
                weightPercent = testSharePercent,
                weightedContributionPercent = if (passed) testSharePercent else 0.0
            )
        }
        val score = breakdown.sumOf { it.score }
        return score.coerceIn(0.0, CORE_HEALTH_SCORE_MAX) to breakdown
    }

    private fun evaluatePiotroskiTests(stockData: StockData): List<PiotroskiTestResult> {
        val currentNetIncome = stockData.netIncomeHistory.getOrNull(0)?.finiteOrNull()
            ?: stockData.netIncome?.finiteOrNull()
        val priorNetIncome = stockData.netIncomeHistory.getOrNull(1)?.finiteOrNull()
        val currentOperatingCashFlow = stockData.operatingCashFlowHistory?.getOrNull(0)?.finiteOrNull()
            ?: stockData.netCashProvidedByOperatingActivities?.finiteOrNull()
        val currentAssets = stockData.totalAssetsHistory.getOrNull(0)?.positiveFiniteOrNull()
        val priorAssets = stockData.totalAssetsHistory.getOrNull(1)?.positiveFiniteOrNull()
        val currentRoa = ratio(currentNetIncome, currentAssets)
        val priorRoa = ratio(priorNetIncome, priorAssets)

        val currentOperatingIncome = stockData.operatingIncomeDisplay?.finiteOrNull()
        val nonOperatingIncome = if (currentNetIncome != null && currentOperatingIncome != null) {
            currentNetIncome - currentOperatingIncome
        } else {
            null
        }

        val currentShares = stockData.sharesOutstandingHistory?.getOrNull(0)?.positiveFiniteOrNull()
        val priorShares = stockData.sharesOutstandingHistory?.getOrNull(1)?.positiveFiniteOrNull()

        val currentRevenue = stockData.revenueHistory.getOrNull(0)?.finiteOrNull()
            ?: stockData.revenue?.finiteOrNull()
        val priorRevenue = stockData.revenueHistory.getOrNull(1)?.finiteOrNull()
        val currentGrossProfit = stockData.grossProfitHistory.getOrNull(0)?.finiteOrNull()
            ?: stockData.grossProfit?.finiteOrNull()
        val priorGrossProfit = stockData.grossProfitHistory.getOrNull(1)?.finiteOrNull()
        val currentGrossMargin = ratio(currentGrossProfit, currentRevenue)
        val priorGrossMargin = ratio(priorGrossProfit, priorRevenue)
        val currentAssetTurnover = ratio(currentRevenue, currentAssets)
        val priorAssetTurnover = ratio(priorRevenue, priorAssets)

        return listOf(
            PiotroskiTestResult(
                metric = "piotroski_positive_roa",
                pass = currentRoa?.let { it > 0.0 }
            ),
            PiotroskiTestResult(
                metric = "piotroski_positive_cfo",
                pass = currentOperatingCashFlow?.let { it > 0.0 }
            ),
            PiotroskiTestResult(
                metric = "piotroski_operating_income_sign_matches_net_income",
                pass = if (currentOperatingIncome != null && currentNetIncome != null) {
                    (currentOperatingIncome > 0.0 && currentNetIncome > 0.0) ||
                            (currentOperatingIncome < 0.0 && currentNetIncome < 0.0) ||
                            (currentOperatingIncome == 0.0 && currentNetIncome == 0.0)
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_accrual_quality",
                pass = if (currentOperatingCashFlow != null && currentNetIncome != null) {
                    currentOperatingCashFlow > currentNetIncome
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_ocf_to_net_income_gt_one",
                pass = if (currentOperatingCashFlow != null && currentNetIncome != null &&
                    currentOperatingCashFlow > 0.0 && currentNetIncome > 0.0
                ) {
                    (currentOperatingCashFlow / currentNetIncome) > 1.0
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_non_operating_income_share_lt_50pct",
                pass = if (nonOperatingIncome != null && currentNetIncome != null && currentNetIncome > 0.0) {
                    nonOperatingIncome < (0.5 * currentNetIncome)
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_no_dilution",
                pass = if (currentShares != null && priorShares != null) {
                    currentShares <= priorShares
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_margin_improved",
                pass = if (currentGrossMargin != null && priorGrossMargin != null) {
                    currentGrossMargin > priorGrossMargin
                } else {
                    null
                }
            ),
            PiotroskiTestResult(
                metric = "piotroski_asset_turnover_improved",
                pass = if (currentAssetTurnover != null && priorAssetTurnover != null) {
                    currentAssetTurnover > priorAssetTurnover
                } else {
                    null
                }
            )
        )
    }

    private fun calculateGrowthScore(stockData: StockData): Double? {
        fun combinedTierScore(values: List<Double?>): Double? {
            val scores = values
                .mapNotNull { tierScoreForGrowth(it) }
                .filter { it.isFinite() }
            return if (scores.isEmpty()) null else scores.average()
        }

        // 5 Growth metrics, each metric blends YoY + average when applicable.
        val revenueMetricScore = combinedTierScore(listOf(stockData.revenueGrowth, stockData.averageRevenueGrowth))
        val netIncomeMetricScore = combinedTierScore(listOf(stockData.netIncomeGrowth, stockData.averageNetIncomeGrowth))
        val fcfMetricScore = combinedTierScore(listOf(stockData.fcfGrowth, stockData.averageFcfGrowth))
        val ocfMetricScore = combinedTierScore(listOf(stockData.ocfGrowth, stockData.averageOcfGrowth))
        val grossProfitMarginMetricScore = tierScoreForGrowth(stockData.grossMarginGrowth)

        val presentScores = listOf(
            revenueMetricScore,
            netIncomeMetricScore,
            fcfMetricScore,
            ocfMetricScore,
            grossProfitMarginMetricScore
        ).mapNotNull { score -> score?.takeIf { it.isFinite() } }

        if (presentScores.isEmpty()) return null

        var base = presentScores.average()

        // Acceleration adjustment: reward revenue trajectory if latest YoY beats the multi-year average.
        val recentRevenueGrowth = stockData.revenueGrowth
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

}

private fun ratio(numerator: Double?, denominator: Double?): Double? {
    if (numerator == null || denominator == null || !numerator.isFinite() || !denominator.isFinite() || abs(denominator) <= 1e-9) {
        return null
    }
    return (numerator / denominator).takeIf { it.isFinite() }
}

private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

private fun Double.positiveFiniteOrNull(): Double? = takeIf { it.isFinite() && it > 0.0 }

private fun Double.nonNegativeFiniteOrNull(): Double? = takeIf { it.isFinite() && it >= 0.0 }

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

private data class PiotroskiTestResult(
    val metric: String,
    val pass: Boolean?
)
