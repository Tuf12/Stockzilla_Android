//app/src/main/java/com/example/stockzilla/HealthScoreDetailsActivity.kt
package com.example.stockzilla

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.databinding.ActivityHealthScoreDetailsBinding
import com.example.stockzilla.databinding.ItemHealthBreakdownBinding
import kotlin.math.abs

@Suppress("DEPRECATION")
class HealthScoreDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthScoreDetailsBinding

    private val metricLabels = mapOf(
        "revenue" to R.string.revenue,
        "net_income" to R.string.net_income,
        "eps" to R.string.metric_eps,
        "pe_ratio" to R.string.p_e_ratio,
        "ps_ratio" to R.string.p_s_ratio,
        "roe" to R.string.metric_roe,
        "debt_to_equity" to R.string.metric_debt_to_equity
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthScoreDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.health_score_details_title)

        val stockData = intent.getSerializableExtra(EXTRA_STOCK_DATA) as? StockData
        val healthScore = intent.getSerializableExtra(EXTRA_HEALTH_SCORE) as? HealthScore

        if (stockData == null || healthScore == null) {
            finish()
            return
        }

        bindSummary(healthScore)
        bindStockMetrics(stockData)
        setupBreakdownList(healthScore.breakdown)
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
            tvForecastSubScoreValue.text = getString(R.string.sub_score_format, healthScore.forecastSubScore)
            tvZScoreValue.text = getString(R.string.sub_score_format, healthScore.zSubScore)

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
            tvSectorValue.text = stockData.sector ?: getString(R.string.not_available)
            tvIndustryValue.text = stockData.industry ?: getString(R.string.not_available)
        }
    }

    private fun setupBreakdownList(breakdown: List<MetricScore>) {
        binding.rvBreakdown.apply {
            layoutManager = LinearLayoutManager(this@HealthScoreDetailsActivity)
            adapter = HealthBreakdownAdapter(breakdown, metricLabels, ::formatMetricValue)
        }
    }

    private fun formatMetricValue(metricName: String, value: Double?): String {
        return when (metricName) {
            "revenue", "net_income" -> formatLargeCurrency(value)
            "eps" -> formatTwoDecimals(value)
            "roe" -> formatPercent(value)
            "pe_ratio", "ps_ratio", "debt_to_equity" -> formatTwoDecimals(value)
            else -> formatTwoDecimals(value)
        }
    }

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

private class HealthBreakdownAdapter(
    private val items: List<MetricScore>,
    private val metricLabels: Map<String, Int>,
    private val valueFormatter: (String, Double?) -> String
) : RecyclerView.Adapter<HealthBreakdownAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemHealthBreakdownBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        val binding = ItemHealthBreakdownBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val metricNameRes = metricLabels[item.metric]
        val readableName = metricNameRes?.let { context.getString(it) }
            ?: item.metric.split("_").joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }

        with(holder.binding) {
            tvMetricName.text = readableName
            val formattedValue = valueFormatter(item.metric, item.value)
            tvMetricValue.text = context.getString(R.string.breakdown_actual_value, formattedValue)

            val weightText = item.weight.takeIf { it.isFinite() }?.let {
                context.getString(R.string.breakdown_weight_value, it * 100)
            } ?: context.getString(R.string.breakdown_weight_value_na)
            tvMetricWeight.text = weightText

            val normalizedText = item.normalized.takeIf { it.isFinite() }?.let {
                context.getString(R.string.breakdown_normalized_value, it)
            } ?: context.getString(R.string.breakdown_normalized_value_na)
            tvMetricNormalized.text = normalizedText

            val contributionText = item.score.takeIf { it.isFinite() }?.let {
                context.getString(R.string.breakdown_contribution_value, it)
            } ?: context.getString(R.string.breakdown_contribution_value_na)
            tvMetricContribution.text = contributionText
        }
    }
}
