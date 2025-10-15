//app/src/main/java/com/example/stockzilla/HealthScoreDetailsActivity.kt
package com.example.stockzilla

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stockzilla.databinding.ActivityHealthScoreDetailsBinding
import kotlin.math.abs
import java.util.Locale
import kotlin.math.exp

@Suppress("DEPRECATION")
class HealthScoreDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthScoreDetailsBinding
    private var currentStockData: StockData? = null
    private var currentHealthScore: HealthScore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthScoreDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.health_score_details_title)

        val stockData = intent.getSerializableExtra(EXTRA_STOCK_DATA) as? StockData
        val healthScore = intent.getSerializableExtra(EXTRA_HEALTH_SCORE) as? HealthScore

        if (stockData == null || healthScore == null) {
            finish()
            return
        }
        currentStockData = stockData
        currentHealthScore = healthScore
        bindSummary(healthScore)
        bindStockMetrics(stockData)
        setupSectionClickListeners()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun bindSummary(healthScore: HealthScore) {
        with(binding) {
            tvCompositeScore.text = getString(R.string.health_score_format, healthScore.compositeScore)
            tvCompositeSummary.text = getString(R.string.composite_score_description)
            tvHealthSubScoreValue.text = getString(R.string.sub_score_format, healthScore.healthSubScore)
            tvHealthDescription.text = getString(R.string.core_health_description)
            tvHealthMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.revenue),
                    getString(R.string.net_income),
                    getString(R.string.metric_eps),
                    getString(R.string.metric_pe_ratio),
                    getString(R.string.metric_ps_ratio),
                    getString(R.string.metric_roe),
                    getString(R.string.metric_debt_to_equity),
                    getString(R.string.metric_pb_ratio),
                    getString(R.string.metric_ebitda),
                    getString(R.string.metric_ebitda_margin),
                    getString(R.string.metric_free_cash_flow),
                    getString(R.string.metric_free_cash_flow_margin),
                    getString(R.string.metric_operating_cash_flow),
                    getString(R.string.metric_net_margin),
                    getString(R.string.metric_liability_to_asset_ratio),
                    getString(R.string.metric_working_capital_ratio),
                    getString(R.string.metric_retained_earnings),
                    getString(R.string.metric_total_assets),
                    getString(R.string.metric_total_liabilities),
                    getString(R.string.metric_outstanding_shares)

                ).joinToString(", ")
            )

            tvForecastSubScoreValue.text = getString(R.string.sub_score_format, healthScore.forecastSubScore)
            val growthDescription = getString(R.string.growth_forecast_description)
            val valuationInsight = formatValuationInsight(healthScore.valuationAssessment)
            tvForecastDescription.text = if (valuationInsight != null) {
                listOf(growthDescription, valuationInsight).joinToString("\n\n")
            } else {
                growthDescription
            }
            tvForecastMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.metric_average_revenue_growth),
                    getString(R.string.metric_average_net_income_growth),
                    getString(R.string.metric_revenue_growth),
                    getString(R.string.metric_free_cash_flow_margin),
                    getString(R.string.metric_ebitda_margin_trend),
                    getString(R.string.metric_relative_valuation)
                ).joinToString(", ")
            )


            tvZScoreValue.text = getString(R.string.resilience_score_format, healthScore.zSubScore)
            tvResilienceDescription.text = getString(R.string.balance_sheet_resilience_description)
            tvResilienceMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(

                    getString(R.string.metric_working_capital_to_assets),
                    getString(R.string.metric_retained_earnings_to_assets),
                    getString(R.string.metric_ebitda_to_assets),
                    getString(R.string.metric_market_cap_to_liabilities),
                    getString(R.string.metric_revenue_to_assets),
                    getString(R.string.metric_two_year_net_income_trend)
                ).joinToString(", ")
            )

            val colorRes = when (healthScore.compositeScore) {
                in 7..10 -> R.color.scoreGood
                in 4..6 -> R.color.scoreMedium
                else -> R.color.scorePoor
            }
            val scoreColor = ContextCompat.getColor(this@HealthScoreDetailsActivity, colorRes)
            tvCompositeScore.setTextColor(scoreColor)
        }
    }

    private fun bindStockMetrics(stockData: StockData) {
        with(binding) {
            tvSymbolValue.text = stockData.symbol
            tvCompanyValue.text = stockData.companyName ?: getString(R.string.not_available)
            tvPriceValue.text = formatCurrency(stockData.price)
            tvMarketCapValue.text = formatLargeCurrency(stockData.marketCap)
            tvRevenueValue.text = formatLargeCurrency(stockData.revenue)
            tvNetIncomeValue.text = formatLargeCurrency(stockData.netIncome)
            tvEpsValue.text = formatTwoDecimals(stockData.eps)
            tvPeRatioValue.text = formatTwoDecimals(stockData.peRatio)
            tvPsRatioValue.text = formatTwoDecimals(stockData.psRatio)
            tvRoeValue.text = formatPercent(stockData.roe)
            tvDebtToEquityValue.text = formatTwoDecimals(stockData.debtToEquity)
            tvFreeCashFlowValue.text = formatLargeCurrency(stockData.freeCashFlow)
            tvPbRatioValue.text = formatTwoDecimals(stockData.pbRatio)
            tvEbitdaValue.text = formatLargeCurrency(stockData.ebitda)
            tvOutstandingSharesValue.text = formatLargeNumber(stockData.outstandingShares)
            tvTotalAssetsValue.text = formatLargeCurrency(stockData.totalAssets)
            tvRevenueGrowthValue.text = formatPercent(stockData.revenueGrowth)
            tvAverageRevenueGrowthValue.text = formatPercent(stockData.averageRevenueGrowth)
            tvAverageNetIncomeGrowthValue.text = formatPercent(stockData.averageNetIncomeGrowth)
            tvTotalLiabilitiesValue.text = formatLargeCurrency(stockData.totalLiabilities)
            tvSectorValue.text = stockData.sector ?: getString(R.string.not_available)
            tvIndustryValue.text = stockData.industry ?: getString(R.string.not_available)
        }
    }

    private fun setupSectionClickListeners() {
        binding.tvHealthHeading.setOnClickListener { showSectionDetails(HealthDetailSection.CORE) }
        binding.tvValuationHeading.setOnClickListener { showSectionDetails(HealthDetailSection.GROWTH) }
        binding.tvResilienceHeading.setOnClickListener { showSectionDetails(HealthDetailSection.RESILIENCE) }
    }

    private fun showSectionDetails(section: HealthDetailSection) {
        val stock = currentStockData ?: return
        val score = currentHealthScore ?: return

        val content = when (section) {
            HealthDetailSection.CORE -> buildCoreSectionContent(score)
            HealthDetailSection.GROWTH -> buildGrowthSectionContent(stock, score)
            HealthDetailSection.RESILIENCE -> buildResilienceSectionContent(stock, score)
        }

        HealthScoreExplanationDialogFragment.newInstance(
            title = content.title,
            summary = content.summary,
            details = content.details,
            emptyMessage = content.emptyMessage
        ).show(supportFragmentManager, HealthScoreExplanationDialogFragment.TAG)
    }

    private fun buildCoreSectionContent(healthScore: HealthScore): SectionDetailContent {
        val title = getString(R.string.health_sub_score_label)
        val summary = getString(R.string.health_score_core_summary, healthScore.healthSubScore)

        if (healthScore.breakdown.isEmpty()) {
            return SectionDetailContent(
                title = title,
                summary = summary,
                details = emptyList(),
                emptyMessage = getString(R.string.health_score_no_metric_breakdown)
            )
        }

        val details = healthScore.breakdown.map { metricScore ->
            val metricKey = metricScore.metric
            HealthScoreDetail(
                label = metricLabel(metricKey),
                value = formatMetricValue(metricKey, metricScore.value),
                weight = formatPercentText(metricScore.weight * 100.0),
                normalized = formatPercentText(metricScore.normalizedPercent),
                rationale = metricRationale(metricKey)
            )
        }

        return SectionDetailContent(
            title = title,
            summary = summary,
            details = details,
            emptyMessage = getString(R.string.health_score_no_metric_breakdown)
        )
    }

    private fun buildGrowthSectionContent(
        stockData: StockData,
        healthScore: HealthScore
    ): SectionDetailContent {
        val title = getString(R.string.forecast_sub_score_label)
        val summaryParts = mutableListOf(
            getString(R.string.health_score_growth_summary, healthScore.forecastSubScore)
        )
        formatValuationInsight(healthScore.valuationAssessment)?.let { summaryParts.add(it) }

        val details = mutableListOf<HealthScoreDetail>()

        buildGrowthDetail(
            label = getString(R.string.metric_average_revenue_growth),
            value = stockData.averageRevenueGrowth,
            weight = 0.20,
            normalized = normalizeGrowthRateLocal(stockData.averageRevenueGrowth),
            rationale = getString(R.string.health_score_rationale_avg_revenue_growth)
        )?.let(details::add)

        buildGrowthDetail(
            label = getString(R.string.metric_average_net_income_growth),
            value = stockData.averageNetIncomeGrowth,
            weight = 0.20,
            normalized = normalizeGrowthRateLocal(stockData.averageNetIncomeGrowth),
            rationale = getString(R.string.health_score_rationale_avg_net_income_growth)
        )?.let(details::add)

        buildGrowthDetail(
            label = getString(R.string.metric_revenue_growth),
            value = stockData.revenueGrowth,
            weight = 0.30,
            normalized = normalizeGrowthRateLocal(stockData.revenueGrowth),
            rationale = getString(R.string.health_score_rationale_recent_revenue_growth)
        )?.let(details::add)

        buildGrowthDetail(
            label = getString(R.string.metric_free_cash_flow_margin),
            value = stockData.freeCashFlowMargin,
            weight = 0.15,
            normalized = normalizeMarginLocal(stockData.freeCashFlowMargin),
            rationale = getString(R.string.health_score_rationale_free_cash_flow_margin_growth)
        )?.let(details::add)

        buildGrowthDetail(
            label = getString(R.string.metric_ebitda_margin_trend),
            value = stockData.ebitdaMarginGrowth,
            weight = 0.10,
            normalized = normalizeMarginTrendLocal(stockData.ebitdaMarginGrowth),
            rationale = getString(R.string.health_score_rationale_ebitda_margin_trend)
        )?.let(details::add)

        healthScore.valuationAssessment?.let { assessment ->
            val weightPercent = formatPercentText(0.05 * 100.0)
            val normalized = assessment.normalizedScore?.let { formatPercentText(it * 100.0) }
            val label = getString(R.string.metric_relative_valuation) + " (${assessment.ratioType})"
            val valueText = getString(
                R.string.health_score_growth_valuation_value,
                formatTwoDecimals(assessment.ratio),
                formatTwoDecimals(assessment.benchmark)
            )
            details.add(
                HealthScoreDetail(
                    label = label,
                    value = valueText,
                    weight = weightPercent,
                    normalized = normalized,
                    rationale = getString(R.string.health_score_rationale_relative_valuation)
                )
            )
        }

        val summary = summaryParts.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n")

        return SectionDetailContent(
            title = title,
            summary = summary,
            details = details,
            emptyMessage = getString(R.string.health_score_growth_missing)
        )
    }

    private fun buildResilienceSectionContent(
        stockData: StockData,
        healthScore: HealthScore
    ): SectionDetailContent {
        val title = getString(R.string.z_sub_score_label)
        val summary = getString(R.string.health_score_resilience_summary, healthScore.zSubScore)
        val inputs = gatherResilienceRatios(stockData)
            ?: return SectionDetailContent(
                title = title,
                summary = summary,
                details = emptyList(),
                emptyMessage = getString(R.string.health_score_resilience_missing)
            )

        val totalCoefficient = RESILIENCE_COEFFICIENT_SUM
        val totalContribution = inputs.altmanZ
        val details = mutableListOf<HealthScoreDetail>()

        fun addResilienceDetail(
            label: String,
            ratio: Double,
            coefficient: Double,
            rationaleRes: Int
        ) {
            val contribution = coefficient * ratio
            val normalized = if (abs(totalContribution) > 1e-6) {
                contribution / totalContribution * 100.0
            } else {
                null
            }
            details.add(
                HealthScoreDetail(
                    label = label,
                    value = formatPercent(ratio),
                    weight = formatPercentText((coefficient / totalCoefficient) * 100.0),
                    normalized = formatPercentText(normalized),
                    rationale = getString(rationaleRes)
                )
            )
        }

        addResilienceDetail(
            label = getString(R.string.metric_working_capital_to_assets),
            ratio = inputs.workingCapitalToAssets,
            coefficient = 1.2,
            rationaleRes = R.string.health_score_rationale_working_capital_to_assets
        )
        addResilienceDetail(
            label = getString(R.string.metric_retained_earnings_to_assets),
            ratio = inputs.retainedEarningsToAssets,
            coefficient = 1.4,
            rationaleRes = R.string.health_score_rationale_retained_earnings_to_assets
        )
        addResilienceDetail(
            label = getString(R.string.metric_ebitda_to_assets),
            ratio = inputs.ebitdaToAssets,
            coefficient = 3.3,
            rationaleRes = R.string.health_score_rationale_ebitda_to_assets
        )
        addResilienceDetail(
            label = getString(R.string.metric_market_cap_to_liabilities),
            ratio = inputs.marketValueToLiabilities,
            coefficient = 0.6,
            rationaleRes = R.string.health_score_rationale_market_cap_to_liabilities
        )
        addResilienceDetail(
            label = getString(R.string.metric_revenue_to_assets),
            ratio = inputs.revenueToAssets,
            coefficient = 1.0,
            rationaleRes = R.string.health_score_rationale_revenue_to_assets
        )

        if (inputs.hasTwoYearLosses) {
            details.add(
                HealthScoreDetail(
                    label = getString(R.string.metric_two_year_net_income_trend),
                    value = getString(R.string.health_score_two_year_loss_detail_value),
                    weight = null,
                    normalized = null,
                    rationale = getString(R.string.health_score_rationale_two_year_loss_penalty)
                )
            )
        }

        return SectionDetailContent(
            title = title,
            summary = summary,
            details = details,
            emptyMessage = getString(R.string.health_score_resilience_missing)
        )
    }

    private fun buildGrowthDetail(
        label: String,
        value: Double?,
        weight: Double,
        normalized: Double?,
        rationale: String
    ): HealthScoreDetail? {
        val safeValue = value?.takeIf { it.isFinite() }
        val safeNormalized = normalized?.takeIf { it.isFinite() }
        if (safeValue == null || safeNormalized == null) {
            return null
        }

        return HealthScoreDetail(
            label = label,
            value = formatPercent(safeValue),
            weight = formatPercentText(weight * 100.0),
            normalized = formatPercentText(safeNormalized * 100.0),
            rationale = rationale
        )
    }

    private fun formatPercentText(value: Double?): String? {
        val safeValue = value?.takeIf { it.isFinite() }
        return safeValue?.let { String.format(Locale.US, "%.1f%%", it) }
    }

    private fun metricLabel(metric: String): String {
        return when (metric) {
            "revenue" -> getString(R.string.revenue)
            "net_income" -> getString(R.string.net_income)
            "eps" -> getString(R.string.metric_eps)
            "pe_ratio" -> getString(R.string.metric_pe_ratio)
            "ps_ratio" -> getString(R.string.metric_ps_ratio)
            "roe" -> getString(R.string.metric_roe)
            "debt_to_equity" -> getString(R.string.metric_debt_to_equity)
            "pb_ratio" -> getString(R.string.metric_pb_ratio)
            "ebitda" -> getString(R.string.metric_ebitda)
            "free_cash_flow" -> getString(R.string.metric_free_cash_flow)
            "operating_cash_flow" -> getString(R.string.metric_operating_cash_flow)
            "free_cash_flow_margin" -> getString(R.string.metric_free_cash_flow_margin)
            "net_margin" -> getString(R.string.metric_net_margin)
            "ebitda_margin" -> getString(R.string.metric_ebitda_margin)
            "current_ratio" -> getString(R.string.metric_current_ratio)
            "liability_to_asset_ratio" -> getString(R.string.metric_liability_to_asset_ratio)
            "working_capital_ratio" -> getString(R.string.metric_working_capital_ratio)
            "retained_earnings" -> getString(R.string.metric_retained_earnings)
            "outstanding_shares" -> getString(R.string.metric_outstanding_shares)
            "total_assets" -> getString(R.string.metric_total_assets)
            "total_liabilities" -> getString(R.string.metric_total_liabilities)
            else -> metric.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun metricRationale(metric: String): String? {
        return when (metric) {
            "revenue" -> getString(R.string.health_score_rationale_revenue)
            "net_income" -> getString(R.string.health_score_rationale_net_income)
            "eps" -> getString(R.string.health_score_rationale_eps)
            "pe_ratio" -> getString(R.string.health_score_rationale_pe_ratio)
            "ps_ratio" -> getString(R.string.health_score_rationale_ps_ratio)
            "roe" -> getString(R.string.health_score_rationale_roe)
            "debt_to_equity" -> getString(R.string.health_score_rationale_debt_to_equity)
            "pb_ratio" -> getString(R.string.health_score_rationale_pb_ratio)
            "ebitda" -> getString(R.string.health_score_rationale_ebitda)
            "free_cash_flow" -> getString(R.string.health_score_rationale_free_cash_flow)
            "operating_cash_flow" -> getString(R.string.health_score_rationale_operating_cash_flow)
            "free_cash_flow_margin" -> getString(R.string.health_score_rationale_free_cash_flow_margin)
            "net_margin" -> getString(R.string.health_score_rationale_net_margin)
            "ebitda_margin" -> getString(R.string.health_score_rationale_ebitda_margin)
            "current_ratio" -> getString(R.string.health_score_rationale_current_ratio)
            "liability_to_asset_ratio" -> getString(R.string.health_score_rationale_liability_to_asset_ratio)
            "working_capital_ratio" -> getString(R.string.health_score_rationale_working_capital_ratio)
            "retained_earnings" -> getString(R.string.health_score_rationale_retained_earnings)
            "outstanding_shares" -> getString(R.string.health_score_rationale_outstanding_shares)
            "total_assets" -> getString(R.string.health_score_rationale_total_assets)
            "total_liabilities" -> getString(R.string.health_score_rationale_total_liabilities)
            else -> null
        }
    }

    private fun formatMetricValue(metric: String, value: Double?): String {
        return when (metric) {
            "revenue", "net_income", "ebitda", "free_cash_flow", "operating_cash_flow", "retained_earnings", "total_assets", "total_liabilities" ->
                formatLargeCurrency(value)
            "outstanding_shares" -> formatLargeNumber(value)
            "eps" -> formatTwoDecimals(value)
            "pe_ratio", "ps_ratio", "pb_ratio", "debt_to_equity", "current_ratio" -> formatTwoDecimals(value)
            "roe", "net_margin", "ebitda_margin", "free_cash_flow_margin", "liability_to_asset_ratio", "working_capital_ratio" -> formatPercent(value)
            else -> {
                val safeValue = value?.takeIf { it.isFinite() }
                safeValue?.let { String.format(Locale.US, "%.2f", it) } ?: getString(R.string.not_available)
            }
        }
    }

    private fun normalizeGrowthRateLocal(value: Double?): Double? {
        value ?: return null
        val capped = value.coerceIn(-0.5, 0.6)
        val fraction = (capped + 0.5) / 1.1
        return sigmoidNormalizeFractionLocal(fraction)
    }

    private fun normalizeMarginLocal(value: Double?): Double? {
        value ?: return null
        val capped = value.coerceIn(-0.3, 0.3)
        val fraction = (capped + 0.3) / 0.6
        return sigmoidNormalizeFractionLocal(fraction)
    }

    private fun normalizeMarginTrendLocal(value: Double?): Double? {
        value ?: return null
        val capped = value.coerceIn(-0.2, 0.2)
        val fraction = (capped + 0.2) / 0.4
        return sigmoidNormalizeFractionLocal(fraction)
    }

    private fun sigmoidNormalizeFractionLocal(frac: Double): Double {
        if (!frac.isFinite()) return 0.5
        val scaled = (frac - 0.5) * 6.0
        val logistic = 1.0 / (1.0 + exp(-scaled))
        return logistic.coerceIn(0.0, 1.0)
    }

    private fun gatherResilienceRatios(stockData: StockData): ResilienceRatios? {
        val totalAssets = stockData.totalAssets?.takeIf { it > 0 } ?: return null
        val totalLiabilities = stockData.totalLiabilities?.takeIf { it > 0 } ?: return null
        val currentAssets = stockData.totalCurrentAssets
        val currentLiabilities = stockData.totalCurrentLiabilities

        val workingCapital = stockData.workingCapital ?: if (currentAssets != null && currentLiabilities != null) {
            currentAssets - currentLiabilities
        } else {
            null
        }

        val workingCapitalRatio = workingCapital?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val retainedEarningsRatio = stockData.retainedEarnings?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val ebitdaRatio = stockData.ebitda?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val marketValueRatio = stockData.marketCap?.let { (it / totalLiabilities).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val revenueRatio = stockData.revenue?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } } ?: return null

        val netIncomeHistory = if (stockData.netIncomeHistory.isNotEmpty()) {
            stockData.netIncomeHistory
        } else {
            listOf(stockData.netIncome)
        }
        val hasLosses = hasConsecutiveNetLosses(netIncomeHistory)

        val altmanZ = (1.2 * workingCapitalRatio) +
                (1.4 * retainedEarningsRatio) +
                (3.3 * ebitdaRatio) +
                (0.6 * marketValueRatio) +
                (1.0 * revenueRatio)

        if (!altmanZ.isFinite()) {
            return null
        }

        return ResilienceRatios(
            workingCapitalToAssets = workingCapitalRatio,
            retainedEarningsToAssets = retainedEarningsRatio,
            ebitdaToAssets = ebitdaRatio,
            marketValueToLiabilities = marketValueRatio,
            revenueToAssets = revenueRatio,
            hasTwoYearLosses = hasLosses,
            altmanZ = altmanZ
        )
    }

    private fun hasConsecutiveNetLosses(history: List<Double?>): Boolean {
        val relevant = history.filterNotNull().take(2)
        if (relevant.size < 2) return false
        return relevant.all { it < 0 }
    }

    private data class ResilienceRatios(
        val workingCapitalToAssets: Double,
        val retainedEarningsToAssets: Double,
        val ebitdaToAssets: Double,
        val marketValueToLiabilities: Double,
        val revenueToAssets: Double,
        val hasTwoYearLosses: Boolean,
        val altmanZ: Double
    )

    private data class SectionDetailContent(
        val title: String,
        val summary: String?,
        val details: List<HealthScoreDetail>,
        val emptyMessage: String
    )

    private enum class HealthDetailSection { CORE, GROWTH, RESILIENCE }

    private fun formatCurrency(value: Double?): String {
        val safeValue = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "$%.2f".format(safeValue)
    }

    private fun formatLargeCurrency(value: Double?): String {
        val safeValue = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        val sign = if (safeValue < 0) "-" else ""
        val absValue = abs(safeValue)
        val unit = when {
            absValue >= 1_000_000_000_000 -> Pair(absValue / 1_000_000_000_000, "T")
            absValue >= 1_000_000_000 -> Pair(absValue / 1_000_000_000, "B")
            absValue >= 1_000_000 -> Pair(absValue / 1_000_000, "M")
            absValue >= 1_000 -> Pair(absValue / 1_000, "K")
            else -> Pair(absValue, null)
        }
        val formatted = if (unit.second == null) {
            if (unit.first >= 1000) {
                "%.0f".format(unit.first)
            } else {
                "%.2f".format(unit.first)
            }
        } else {
            "%.2f".format(unit.first)
        }
        val suffix = unit.second.orEmpty()
        val currencySymbol = "$"
        return "$sign$currencySymbol$formatted$suffix"
    }

    private fun formatLargeNumber(value: Double?): String {
        val safeValue = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        val sign = if (safeValue < 0) "-" else ""
        val absValue = abs(safeValue)
        val unit = when {
            absValue >= 1_000_000_000_000 -> Pair(absValue / 1_000_000_000_000, "T")
            absValue >= 1_000_000_000 -> Pair(absValue / 1_000_000_000, "B")
            absValue >= 1_000_000 -> Pair(absValue / 1_000_000, "M")
            absValue >= 1_000 -> Pair(absValue / 1_000, "K")
            else -> Pair(absValue, null)
        }
        val formatted = if (unit.second == null) {
            if (unit.first >= 1000) {
                "%.0f".format(unit.first)
            } else {
                "%.2f".format(unit.first)
            }
        } else {
            "%.2f".format(unit.first)
        }
        val suffix = unit.second.orEmpty()
        return "$sign$formatted$suffix"
    }

    private fun formatTwoDecimals(value: Double?): String {
        val safeValue = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "%.2f".format(safeValue)
    }

    private fun formatPercent(value: Double?): String {
        val safeValue = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "%.1f%%".format(safeValue * 100)
    }

    private fun formatValuationInsight(assessment: ValuationAssessment?): String? {
        assessment ?: return null
        val ratioLabel = assessment.ratioType
        val deviationText = assessment.deviation?.let { deviation ->
            val percent = abs(deviation) * 100
            if (percent.isFinite()) {
                String.format(Locale.US, "%.1f%%", percent)
            } else null
        }
        val diffSuffix = deviationText?.let { getString(R.string.valuation_diff_detail, it) }.orEmpty()

        return when (assessment.classification) {
            ValuationClassification.UNDERVALUED ->
                getString(R.string.valuation_undervalued, ratioLabel, diffSuffix)
            ValuationClassification.OVERVALUED ->
                getString(R.string.valuation_overvalued, ratioLabel, diffSuffix)
            ValuationClassification.FAIRLY_VALUED ->
                getString(R.string.valuation_fair, ratioLabel)
            ValuationClassification.UNKNOWN ->
                getString(R.string.valuation_unknown, ratioLabel)
        }
    }

    companion object {
        private const val RESILIENCE_COEFFICIENT_SUM = 7.5
        const val EXTRA_STOCK_DATA = "extra_stock_data"
        const val EXTRA_HEALTH_SCORE = "extra_health_score"
    }
}
