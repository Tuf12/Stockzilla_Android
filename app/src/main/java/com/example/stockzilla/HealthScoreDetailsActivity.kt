//app/src/main/java/com/example/stockzilla/HealthScoreDetailsActivity.kt
package com.example.stockzilla

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stockzilla.databinding.ActivityHealthScoreDetailsBinding
import kotlin.math.abs

@Suppress("DEPRECATION")
class HealthScoreDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthScoreDetailsBinding

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
            tvHealthSubScoreValue.text = getString(R.string.sub_score_format, healthScore.coreHealthScore)
            tvHealthDescription.text = getString(R.string.core_health_description)
            tvHealthMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.revenue),
                    getString(R.string.net_income),
                    getString(R.string.metric_eps),
                    getString(R.string.metric_roe),
                    getString(R.string.metric_ebitda)
                ).joinToString(", ")
            )

            tvForecastSubScoreValue.text = getString(R.string.sub_score_format, healthScore.growthForecastScore)
            tvForecastDescription.text = getString(R.string.growth_forecast_description)
            tvForecastMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.p_s_ratio),
                    getString(R.string.p_e_ratio),
                    getString(R.string.metric_revenue_growth),
                    getString(R.string.metric_average_revenue_growth),
                    getString(R.string.metric_average_net_income_growth)
                ).joinToString(", ")
            )

            tvZScoreValue.text = getString(R.string.sub_score_format, healthScore.resilienceScore)
            tvResilienceDescription.text = getString(R.string.balance_sheet_resilience_description)
            tvResilienceMetricsUsed.text = getString(
                R.string.metrics_used_value,
                listOf(
                    getString(R.string.net_income),
                    getString(R.string.metric_debt_to_equity),
                    getString(R.string.metric_free_cash_flow),
                    getString(R.string.metric_total_assets),
                    getString(R.string.metric_total_liabilities)
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
