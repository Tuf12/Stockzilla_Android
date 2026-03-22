package com.example.stockzilla.feature

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stockzilla.feature.HealthScoreExplanationDialogFragment
import com.example.stockzilla.R
import com.example.stockzilla.ai.AiAssistantActivity
import com.example.stockzilla.databinding.ActivityHealthScoreDetailsBinding
import com.example.stockzilla.scoring.HealthScore
import com.example.stockzilla.scoring.HealthScoreDetail
import com.example.stockzilla.scoring.MetricPerformance
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.sec.EdgarConcepts
import java.util.Locale
import kotlin.math.abs

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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.action_ai_chat -> {
                // Open Eidos chat without rebinding the conversation to this stock.
                AiAssistantActivity.Companion.start(this, null)
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
                    getString(R.string.metric_piotroski_positive_roa),
                    getString(R.string.metric_piotroski_positive_cfo),
                    getString(R.string.metric_piotroski_delta_roa_positive),
                    getString(R.string.metric_piotroski_accrual_quality),
                    getString(R.string.metric_piotroski_leverage_improved),
                    getString(R.string.metric_piotroski_current_ratio_improved),
                    getString(R.string.metric_piotroski_no_dilution),
                    getString(R.string.metric_piotroski_margin_improved),
                    getString(R.string.metric_piotroski_asset_turnover_improved)
                ).joinToString(", ")
            )

            tvForecastSubScoreValue.text = getString(R.string.sub_score_format, healthScore.forecastSubScore)
            tvForecastDescription.text = getString(R.string.growth_forecast_description)
            tvForecastMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.metric_average_revenue_growth),
                    getString(R.string.metric_revenue_growth),
                    getString(R.string.metric_average_net_income_growth),
                    getString(R.string.metric_recent_net_income_growth),
                    getString(R.string.metric_fcf_growth),
                    getString(R.string.metric_average_fcf_growth)
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
        val ttmTag = " " + getString(R.string.label_ttm)
        val annualTag = " " + getString(R.string.label_annual)
        val yoyTag = " " + getString(R.string.label_yoy_annual)
        val dataSuffix = if (stockData.hasTtm) ttmTag else annualTag
        val grossProfitDisplay = stockData.grossProfitDisplay
        val revenueDisplay = stockData.revenueDisplay
        val grossMarginVal = if (grossProfitDisplay != null &&
            revenueDisplay != null &&
            abs(revenueDisplay) > 1e-9
        ) {
            grossProfitDisplay / revenueDisplay
        } else null
        val grossMarginYoY = computeAnnualGrossMarginYoY(stockData)

        with(binding) {
            tvSymbolValue.text = stockData.symbol
            tvCompanyValue.text = stockData.companyName ?: getString(R.string.not_available)
            tvPriceValue.text = formatCurrency(stockData.price)
            tvMarketCapValue.text = formatLargeCurrency(stockData.marketCap)
            tvRevenueValue.text = formatLargeCurrency(stockData.revenueDisplay) + dataSuffix
            tvNetIncomeValue.text = formatLargeCurrency(stockData.netIncomeDisplay) + dataSuffix
            tvEpsValue.text = formatTwoDecimals(stockData.epsDisplay) + dataSuffix
            tvPeRatioValue.text = formatTwoDecimals(stockData.peRatio) + dataSuffix
            tvPsRatioValue.text = formatTwoDecimals(stockData.psRatio) + dataSuffix
            tvRoeValue.text = formatPercent(stockData.roe) + dataSuffix
            tvDebtToEquityValue.text = formatTwoDecimals(stockData.debtToEquity)
            tvFreeCashFlowValue.text = formatLargeCurrency(stockData.freeCashFlowDisplay) + dataSuffix
            tvPbRatioValue.text = formatTwoDecimals(stockData.pbRatio)
            tvEbitdaValue.text = formatLargeCurrency(stockData.ebitdaDisplay) + dataSuffix
            tvCogsValue.text = formatLargeCurrency(stockData.costOfGoodsSoldDisplay) + dataSuffix
            tvGrossProfitValue.text = formatLargeCurrency(grossProfitDisplay) + dataSuffix
            tvGrossMarginValue.text = formatPercent(grossMarginVal) + dataSuffix
            tvGrossMarginGrowthValue.text = formatPercent(grossMarginYoY) + yoyTag
            tvOutstandingSharesValue.text = formatLargeNumber(stockData.outstandingShares)
            tvTotalAssetsValue.text = formatLargeCurrency(stockData.totalAssets)
            tvTotalLiabilitiesValue.text = formatLargeCurrency(stockData.totalLiabilities)
            tvCurrentAssetsValue.text = formatLargeCurrency(stockData.totalCurrentAssets)
            tvCurrentLiabilitiesValue.text = formatLargeCurrency(stockData.totalCurrentLiabilities)
            tvRevenueGrowthValue.text = formatPercent(stockData.revenueGrowth) + yoyTag
            tvAverageRevenueGrowthValue.text = formatPercent(stockData.averageRevenueGrowth) + yoyTag
            tvNetIncomeGrowthValue.text = formatPercent(stockData.netIncomeGrowth) + yoyTag
            tvAverageNetIncomeGrowthValue.text = formatPercent(stockData.averageNetIncomeGrowth) + yoyTag
            tvFcfGrowthValue.text = formatPercent(stockData.fcfGrowth) + yoyTag
            tvAverageFcfGrowthValue.text = formatPercent(stockData.averageFcfGrowth) + yoyTag
            tvSectorValue.text = stockData.sector ?: getString(R.string.not_available)
            tvIndustryValue.text = stockData.industry ?: getString(R.string.not_available)
        }
    }

    private fun computeAnnualGrossMarginYoY(stockData: StockData): Double? {
        val currentRevenue = stockData.revenueHistory.getOrNull(0)
        val priorRevenue = stockData.revenueHistory.getOrNull(1)
        val currentGrossProfit = stockData.grossProfitHistory.getOrNull(0)
        val priorGrossProfit = stockData.grossProfitHistory.getOrNull(1)
        if (currentRevenue == null || priorRevenue == null || currentGrossProfit == null || priorGrossProfit == null) {
            return null
        }
        if (abs(currentRevenue) <= 1e-9 || abs(priorRevenue) <= 1e-9) return null
        val currentGrossMargin = currentGrossProfit / currentRevenue
        val priorGrossMargin = priorGrossProfit / priorRevenue
        return currentGrossMargin - priorGrossMargin
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

        HealthScoreExplanationDialogFragment.Companion.newInstance(
            title = content.title,
            summary = content.summary,
            details = content.details,
            emptyMessage = content.emptyMessage
        ).show(supportFragmentManager, HealthScoreExplanationDialogFragment.Companion.TAG)
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
            val isPiotroskiMetric = metricKey.startsWith("piotroski_")
            HealthScoreDetail(
                label = metricLabel(metricKey),
                value = if (isPiotroskiMetric) {
                    formatPiotroskiOutcome(metricScore.value)
                } else {
                    formatMetricValue(metricKey, metricScore.value)
                },
                weight = formatPercentText(metricScore.weightPercent),
                normalized = formatPercentText(metricScore.weightedContributionPercent),
                performance = if (isPiotroskiMetric) {
                    classifyPiotroskiPerformance(metricScore.value)
                } else {
                    classifyPerformance(metricScore.weightedContributionPercent)
                },
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
        val summary = getString(R.string.health_score_growth_summary, healthScore.forecastSubScore)

        val details = listOf(
            buildGrowthDetail(
                label = getString(R.string.metric_average_revenue_growth),
                value = stockData.averageRevenueGrowth,
                normalized = growthTierPercentLocal(stockData.averageRevenueGrowth),
                rationale = getString(R.string.health_score_rationale_avg_revenue_growth)
            ),
            buildGrowthDetail(
                label = getString(R.string.metric_revenue_growth),
                value = stockData.revenueGrowth,
                normalized = growthTierPercentLocal(stockData.revenueGrowth),
                rationale = getString(R.string.health_score_rationale_recent_revenue_growth)
            ),
            buildGrowthDetail(
                label = getString(R.string.metric_average_net_income_growth),
                value = stockData.averageNetIncomeGrowth,
                normalized = growthTierPercentLocal(stockData.averageNetIncomeGrowth),
                rationale = getString(R.string.health_score_rationale_avg_net_income_growth)
            ),
            buildGrowthDetail(
                label = getString(R.string.metric_recent_net_income_growth),
                value = stockData.netIncomeGrowth,
                normalized = growthTierPercentLocal(stockData.netIncomeGrowth),
                rationale = getString(R.string.health_score_rationale_recent_net_income_growth)
            ),
            buildGrowthDetail(
                label = getString(R.string.metric_fcf_growth),
                value = stockData.fcfGrowth,
                normalized = growthTierPercentLocal(stockData.fcfGrowth),
                rationale = getString(R.string.health_score_rationale_fcf_growth)
            ),
            buildGrowthDetail(
                label = getString(R.string.metric_average_fcf_growth),
                value = stockData.averageFcfGrowth,
                normalized = growthTierPercentLocal(stockData.averageFcfGrowth),
                rationale = getString(R.string.health_score_rationale_avg_fcf_growth)
            )
        )

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
        val title = getString(R.string.z_sub_score_label) // updated below when we have inputs
        val summary = getString(R.string.health_score_resilience_summary, healthScore.zSubScore)
        val inputs = gatherResilienceRatios(stockData)
            ?: return SectionDetailContent(
                title = title,
                summary = summary,
                details = emptyList(),
                emptyMessage = getString(R.string.health_score_resilience_missing)
            )

        val sectionTitle = if (inputs.useZPrime) getString(R.string.z_prime_score_label) else title
        val details = mutableListOf<HealthScoreDetail>()

        fun addResilienceDetail(
            label: String,
            ratio: Double,
            rationaleRes: Int
        ) {
            details.add(
                HealthScoreDetail(
                    label = label,
                    value = formatPercent(ratio),
                    weight = null,
                    normalized = null,
                    performance = null,
                    rationale = getString(rationaleRes)
                )
            )
        }

        if (inputs.useZPrime) {
            addResilienceDetail(
                label = getString(R.string.metric_working_capital_to_assets),
                ratio = inputs.workingCapitalToAssets,
                rationaleRes = R.string.health_score_rationale_working_capital_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_retained_earnings_to_assets),
                ratio = inputs.retainedEarningsToAssets,
                rationaleRes = R.string.health_score_rationale_retained_earnings_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_ebitda_to_assets),
                ratio = inputs.ebitdaToAssets,
                rationaleRes = R.string.health_score_rationale_ebitda_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_market_cap_to_liabilities),
                ratio = inputs.marketValueToLiabilities,
                rationaleRes = R.string.health_score_rationale_market_cap_to_liabilities
            )
        } else {
            addResilienceDetail(
                label = getString(R.string.metric_working_capital_to_assets),
                ratio = inputs.workingCapitalToAssets,
                rationaleRes = R.string.health_score_rationale_working_capital_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_retained_earnings_to_assets),
                ratio = inputs.retainedEarningsToAssets,
                rationaleRes = R.string.health_score_rationale_retained_earnings_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_ebitda_to_assets),
                ratio = inputs.ebitdaToAssets,
                rationaleRes = R.string.health_score_rationale_ebitda_to_assets
            )
            addResilienceDetail(
                label = getString(R.string.metric_market_cap_to_liabilities),
                ratio = inputs.marketValueToLiabilities,
                rationaleRes = R.string.health_score_rationale_market_cap_to_liabilities
            )
            inputs.revenueToAssets?.let { rev ->
                addResilienceDetail(
                    label = getString(R.string.metric_revenue_to_assets),
                    ratio = rev,
                    rationaleRes = R.string.health_score_rationale_revenue_to_assets
                )
            }
        }

        if (inputs.hasTwoYearLosses) {
            details.add(
                HealthScoreDetail(
                    label = getString(R.string.metric_two_year_net_income_trend),
                    value = getString(R.string.health_score_two_year_loss_detail_value),
                    weight = null,
                    normalized = null,
                    performance = null,
                    rationale = getString(R.string.health_score_rationale_two_year_loss_penalty)
                )
            )
        }

        return SectionDetailContent(
            title = sectionTitle,
            summary = summary,
            details = details,
            emptyMessage = getString(R.string.health_score_resilience_missing)
        )
    }

    private fun buildGrowthDetail(
        label: String,
        value: Double?,
        normalized: Double?,
        rationale: String
    ): HealthScoreDetail {
        val safeValue = value?.takeIf { it.isFinite() }
        val safeNormalized = normalized?.takeIf { it.isFinite() }
        val na = getString(R.string.not_available)

        return HealthScoreDetail(
            label = label,
            value = safeValue?.let { formatPercent(it) } ?: na,
            weight = null,
            normalized = safeNormalized?.let { formatPercentText(it * 100.0) } ?: na,
            performance = if (safeNormalized != null) classifyPerformance(safeNormalized * 100.0) else null,
            rationale = if (safeValue != null) rationale else getString(R.string.health_score_growth_insufficient_data)
        )
    }

    private fun classifyPerformance(normalizedPercent: Double?): MetricPerformance? {
        val value = normalizedPercent?.takeIf { it.isFinite() } ?: return null
        return when {
            value >= 70.0 -> MetricPerformance.GOOD
            value >= 40.0 -> MetricPerformance.NEUTRAL
            else -> MetricPerformance.POOR
        }
    }

    private fun formatPercentText(value: Double?): String? {
        val safeValue = value?.takeIf { it.isFinite() }
        return safeValue?.let { String.Companion.format(Locale.US, "%.1f%%", it) }
    }

    private fun formatPiotroskiOutcome(value: Double?): String {
        return when {
            value == null -> getString(R.string.not_available)
            value >= 0.5 -> getString(R.string.health_score_test_pass)
            else -> getString(R.string.health_score_test_fail)
        }
    }

    private fun classifyPiotroskiPerformance(value: Double?): MetricPerformance? {
        val safeValue = value?.takeIf { it.isFinite() } ?: return null
        return if (safeValue >= 0.5) MetricPerformance.GOOD else MetricPerformance.POOR
    }

    private fun metricLabel(metric: String): String {
        return when (metric) {
            "piotroski_positive_roa" -> getString(R.string.metric_piotroski_positive_roa)
            "piotroski_positive_cfo" -> getString(R.string.metric_piotroski_positive_cfo)
            "piotroski_delta_roa_positive" -> getString(R.string.metric_piotroski_delta_roa_positive)
            "piotroski_accrual_quality" -> getString(R.string.metric_piotroski_accrual_quality)
            "piotroski_leverage_improved" -> getString(R.string.metric_piotroski_leverage_improved)
            "piotroski_current_ratio_improved" -> getString(R.string.metric_piotroski_current_ratio_improved)
            "piotroski_no_dilution" -> getString(R.string.metric_piotroski_no_dilution)
            "piotroski_margin_improved" -> getString(R.string.metric_piotroski_margin_improved)
            "piotroski_asset_turnover_improved" -> getString(R.string.metric_piotroski_asset_turnover_improved)
            "revenue_growth" -> getString(R.string.metric_revenue_growth)
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
            else -> metric.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(
                Locale.getDefault()) else it.toString() }
        }
    }

    private fun metricRationale(metric: String): String? {
        return when (metric) {
            "piotroski_positive_roa" -> getString(R.string.health_score_rationale_piotroski_positive_roa)
            "piotroski_positive_cfo" -> getString(R.string.health_score_rationale_piotroski_positive_cfo)
            "piotroski_delta_roa_positive" -> getString(R.string.health_score_rationale_piotroski_delta_roa_positive)
            "piotroski_accrual_quality" -> getString(R.string.health_score_rationale_piotroski_accrual_quality)
            "piotroski_leverage_improved" -> getString(R.string.health_score_rationale_piotroski_leverage_improved)
            "piotroski_current_ratio_improved" -> getString(R.string.health_score_rationale_piotroski_current_ratio_improved)
            "piotroski_no_dilution" -> getString(R.string.health_score_rationale_piotroski_no_dilution)
            "piotroski_margin_improved" -> getString(R.string.health_score_rationale_piotroski_margin_improved)
            "piotroski_asset_turnover_improved" -> getString(R.string.health_score_rationale_piotroski_asset_turnover_improved)
            "revenue_growth" -> getString(R.string.health_score_rationale_recent_revenue_growth)
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
            "revenue_growth", "roe", "net_margin", "ebitda_margin", "free_cash_flow_margin", "liability_to_asset_ratio", "working_capital_ratio" -> formatPercent(value)
            else -> {
                val safeValue = value?.takeIf { it.isFinite() }
                safeValue?.let { String.Companion.format(Locale.US, "%.2f", it) } ?: getString(R.string.not_available)
            }
        }
    }

    private fun growthTierPercentLocal(value: Double?): Double? {
        val v = value?.takeIf { it.isFinite() } ?: return null
        val score = when {
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
        return score / 10.0
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
        val ebitdaRatio = stockData.ebitdaDisplay?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val rawMarketValueRatio = stockData.marketCap?.let { (it / totalLiabilities).takeIf { ratio -> ratio.isFinite() } } ?: return null
        val marketValueRatio = rawMarketValueRatio
        val revenueRatio = stockData.revenueDisplay?.let { (it / totalAssets).takeIf { ratio -> ratio.isFinite() } }

        val netIncomeHistory = if (stockData.netIncomeHistory.isNotEmpty()) {
            stockData.netIncomeHistory
        } else {
            listOf(stockData.netIncome)
        }
        val hasLosses = hasConsecutiveNetLosses(netIncomeHistory)

        val isManufacturing = EdgarConcepts.isManufacturingSic(stockData.sicCode)
        val scoreValue = if (isManufacturing) {
            val revenueToAssets = revenueRatio ?: return null
            (1.2 * workingCapitalRatio) +
                    (1.4 * retainedEarningsRatio) +
                    (3.3 * ebitdaRatio) +
                    (0.6 * marketValueRatio) +
                    (1.0 * revenueToAssets)
        } else {
            (6.56 * workingCapitalRatio) +
                    (3.26 * retainedEarningsRatio) +
                    (6.72 * ebitdaRatio) +
                    (1.05 * marketValueRatio)
        }

        if (!scoreValue.isFinite()) {
            return null
        }

        return ResilienceRatios(
            workingCapitalToAssets = workingCapitalRatio,
            retainedEarningsToAssets = retainedEarningsRatio,
            ebitdaToAssets = ebitdaRatio,
            marketValueToLiabilities = marketValueRatio,
            revenueToAssets = revenueRatio,
            hasTwoYearLosses = hasLosses,
            altmanZ = scoreValue,
            useZPrime = !isManufacturing
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
        val revenueToAssets: Double?,
        val hasTwoYearLosses: Boolean,
        val altmanZ: Double,
        val useZPrime: Boolean = false
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

    companion object {
        const val EXTRA_STOCK_DATA = "extra_stock_data"
        const val EXTRA_HEALTH_SCORE = "extra_health_score"
    }
}