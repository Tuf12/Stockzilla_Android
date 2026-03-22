package com.example.stockzilla.feature

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.R
import com.example.stockzilla.ai.AiAssistantActivity
import com.example.stockzilla.data.CompanyProfileEntity
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.databinding.ActivityFullAnalysisBinding
import com.example.stockzilla.scoring.StockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs

class FullAnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STOCK_DATA = "extra_stock_data"
        private const val STOCK_ANALYSIS_BASE = "https://stockanalysis.com/stocks/"
    }

    private lateinit var binding: ActivityFullAnalysisBinding
    private var symbol: String = ""
    private val database by lazy { StockzillaDatabase.Companion.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.full_analysis_title)

        val stockData = intent.getSerializableExtra(EXTRA_STOCK_DATA) as? StockData

        if (stockData == null) {
            finish()
            return
        }

        symbol = stockData.symbol
        binding.tvCompanyName.text = stockData.companyName ?: symbol
        binding.tvSymbol.text = symbol

        populateRawFactsTable(stockData)
        populateDerivedMetricsTable(stockData)
        populateFinancialHistoryTable(stockData)
        loadBusinessProfile(stockData)

        val cik = stockData.cik
        if (!cik.isNullOrBlank()) {
            binding.btnView10kSec.visibility = View.VISIBLE
            val paddedCik = cik.trim().padStart(10, '0')
            val sec10kUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$paddedCik&type=10-K"
            binding.btnView10kSec.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sec10kUrl)))
            }
        } else {
            binding.btnView10kSec.visibility = View.GONE
        }

        binding.btnOpenOnStockAnalysis.setOnClickListener {
            val url = STOCK_ANALYSIS_BASE + symbol.lowercase() + "/"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun loadBusinessProfile(stockData: StockData) {
        val summaryParts = listOfNotNull(
            stockData.companyName?.takeIf { it.isNotBlank() },
            stockData.symbol.takeIf { it.isNotBlank() }
        )
        binding.tvBusinessProfileSummary.text = if (summaryParts.isNotEmpty()) {
            summaryParts.joinToString(" • ")
        } else {
            getString(R.string.business_profile_unavailable)
        }

        val details = mutableListOf<String>()
        stockData.sector?.takeIf { it.isNotBlank() }?.let {
            details.add(getString(R.string.sector) + ": " + it)
        }
        stockData.industry?.takeIf { it.isNotBlank() }?.let {
            details.add(getString(R.string.industry) + ": " + it)
        }
        binding.tvBusinessProfileDetails.text = if (details.isNotEmpty()) {
            details.joinToString("\n")
        } else {
            getString(R.string.business_profile_unavailable)
        }

        // Load any saved user-authored "About" text for this company and allow editing.
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                database.companyProfileDao().getBySymbol(stockData.symbol)
            }
            val aboutText = existing?.about.orEmpty()
            binding.etBusinessProfileAbout.setText(aboutText)
            updateAboutVisibility(aboutText)
        }

        binding.btnSaveBusinessProfileAbout.setOnClickListener {
            val aboutText = binding.etBusinessProfileAbout.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch(Dispatchers.IO) {
                val entity = CompanyProfileEntity(
                    symbol = stockData.symbol,
                    about = aboutText.ifBlank { null },
                    updatedAt = System.currentTimeMillis()
                )
                database.companyProfileDao().upsert(entity)
                withContext(Dispatchers.Main) {
                    updateAboutVisibility(aboutText)
                }
            }
        }

        binding.btnEditBusinessProfileAbout.setOnClickListener {
            // Switch back to edit mode while keeping the current text.
            binding.tilBusinessProfileAbout.visibility = View.VISIBLE
            binding.btnSaveBusinessProfileAbout.visibility = View.VISIBLE
            binding.tvBusinessProfileAbout.visibility = View.GONE
        }
    }

    private fun updateAboutVisibility(aboutText: String) {
        if (aboutText.isBlank()) {
            binding.tvBusinessProfileAbout.visibility = View.GONE
            binding.tilBusinessProfileAbout.visibility = View.VISIBLE
            binding.btnSaveBusinessProfileAbout.visibility = View.VISIBLE
        } else {
            binding.tvBusinessProfileAbout.text = aboutText
            binding.tvBusinessProfileAbout.visibility = View.VISIBLE
            binding.tilBusinessProfileAbout.visibility = View.GONE
            binding.btnSaveBusinessProfileAbout.visibility = View.GONE
        }
    }

    private fun populateRawFactsTable(stockData: StockData) {
        val table = binding.tableRawFacts
        table.removeAllViews()
        addMetricHeaderRow(table)

        val rawRows = listOf(
            getString(R.string.revenue) to formatAbbreviatedCurrency(stockData.revenueDisplay),
            getString(R.string.net_income) to formatAbbreviatedCurrency(stockData.netIncomeDisplay),
            getString(R.string.metric_eps) to formatNumber(stockData.epsDisplay),
            getString(R.string.metric_ebitda) to formatAbbreviatedCurrency(stockData.ebitdaDisplay),
            getString(R.string.metric_cogs) to formatAbbreviatedCurrency(stockData.costOfGoodsSoldDisplay),
            getString(R.string.metric_gross_profit) to formatAbbreviatedCurrency(stockData.grossProfitDisplay),
            getString(R.string.metric_operating_cash_flow) to formatAbbreviatedCurrency(stockData.operatingCashFlowDisplay),
            getString(R.string.metric_free_cash_flow) to formatAbbreviatedCurrency(stockData.freeCashFlowDisplay),
            getString(R.string.metric_outstanding_shares) to formatAbbreviatedShares(stockData.outstandingShares),
            getString(R.string.metric_total_assets) to formatAbbreviatedCurrency(stockData.totalAssets),
            getString(R.string.metric_total_liabilities) to formatAbbreviatedCurrency(stockData.totalLiabilities),
            getString(R.string.metric_retained_earnings) to formatAbbreviatedCurrency(stockData.retainedEarnings)
        )

        rawRows.forEach { (label, value) -> addMetricRow(table, label, value) }
    }

    private fun populateDerivedMetricsTable(stockData: StockData) {
        val table = binding.tableDerivedMetrics
        table.removeAllViews()
        addMetricHeaderRow(table)

        val totalEquity = if (stockData.totalAssets != null && stockData.totalLiabilities != null) {
            stockData.totalAssets - stockData.totalLiabilities
        } else null
        val revenueDisplay = stockData.revenueDisplay
        val netIncomeDisplay = stockData.netIncomeDisplay
        val ebitdaDisplay = stockData.ebitdaDisplay
        val freeCashFlowDisplay = stockData.freeCashFlowDisplay
        val currentRatio = if (stockData.totalCurrentAssets != null &&
            stockData.totalCurrentLiabilities != null &&
            stockData.totalCurrentLiabilities != 0.0
        ) {
            stockData.totalCurrentAssets / stockData.totalCurrentLiabilities
        } else null
        val netMargin = if (netIncomeDisplay != null &&
            revenueDisplay != null &&
            revenueDisplay != 0.0
        ) {
            netIncomeDisplay / revenueDisplay
        } else null
        val ebitdaMargin = if (ebitdaDisplay != null &&
            revenueDisplay != null &&
            revenueDisplay != 0.0
        ) {
            ebitdaDisplay / revenueDisplay
        } else null
        val grossProfitDisplay = stockData.grossProfitDisplay
        val grossMargin = if (grossProfitDisplay != null &&
            revenueDisplay != null &&
            revenueDisplay != 0.0
        ) {
            grossProfitDisplay / revenueDisplay
        } else null
        val grossMarginYoY = computeAnnualGrossMarginYoY(stockData)
        val fcfMargin = if (freeCashFlowDisplay != null &&
            revenueDisplay != null &&
            revenueDisplay != 0.0
        ) {
            freeCashFlowDisplay / revenueDisplay
        } else null

        val derivedRows = listOf(
            getString(R.string.price_label) to formatPrice(stockData.price),
            getString(R.string.volume) to getString(R.string.placeholder_dash),
            getString(R.string.market_cap) to formatAbbreviatedCurrency(stockData.marketCap),
            getString(R.string.metric_pe_ratio) to formatNumber(stockData.peRatio),
            getString(R.string.metric_ps_ratio) to formatNumber(stockData.psRatio),
            getString(R.string.metric_pb_ratio) to formatNumber(stockData.pbRatio),
            getString(R.string.metric_roe) to formatPercent(stockData.roe),
            getString(R.string.metric_debt_to_equity) to formatNumber(stockData.debtToEquity),
            getString(R.string.metric_current_ratio) to formatNumber(currentRatio),
            getString(R.string.metric_net_margin) to formatPercent(netMargin),
            getString(R.string.metric_ebitda_margin) to formatPercent(ebitdaMargin),
            getString(R.string.metric_gross_margin) to formatPercent(grossMargin),
            getString(R.string.metric_gross_margin_yoy) to formatPercent(grossMarginYoY),
            getString(R.string.metric_free_cash_flow_margin) to formatPercent(fcfMargin),
            getString(R.string.metric_working_capital) to formatAbbreviatedCurrency(stockData.workingCapital),
            getString(R.string.metric_revenue_growth) to formatPercent(stockData.revenueGrowth),
            getString(R.string.metric_average_revenue_growth) to formatPercent(stockData.averageRevenueGrowth),
            getString(R.string.metric_average_net_income_growth) to formatPercent(stockData.averageNetIncomeGrowth),
            getString(R.string.metric_book_equity) to formatAbbreviatedCurrency(totalEquity)
        )

        derivedRows.forEach { (label, value) -> addMetricRow(table, label, value) }
    }

    private fun addMetricHeaderRow(table: TableLayout) {
        val row = TableRow(this)
        row.addView(buildCell(getString(R.string.metric_column_name), header = true))
        row.addView(buildCell(getString(R.string.metric_column_value), header = true))
        table.addView(row)
    }

    private fun addMetricRow(table: TableLayout, label: String, value: String) {
        val row = TableRow(this)
        row.addView(buildCell(label))
        row.addView(buildCell(value))
        table.addView(row)
    }

    private fun buildCell(text: String, header: Boolean = false): TextView {
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        val primaryColor = if (tv.resourceId != 0) {
            ResourcesCompat.getColor(resources, tv.resourceId, null)
        } else {
            0xff000000.toInt()
        }
        theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        val secondaryColor = if (tv.resourceId != 0) {
            ResourcesCompat.getColor(resources, tv.resourceId, null)
        } else {
            0xff757575.toInt()
        }
        return TextView(this).apply {
            setPadding(dp8, dp4, dp8, dp4)
            setTextColor(if (header) secondaryColor else primaryColor)
            this.text = text
            if (header) setTypeface(typeface, Typeface.BOLD)
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
        if (currentRevenue == 0.0 || priorRevenue == 0.0) return null
        val currentGrossMargin = currentGrossProfit / currentRevenue
        val priorGrossMargin = priorGrossProfit / priorRevenue
        return currentGrossMargin - priorGrossMargin
    }

    private fun populateFinancialHistoryTable(stockData: StockData) {
        val rev = stockData.revenueHistory
        val ni = stockData.netIncomeHistory
        val ebitda = stockData.ebitdaHistory
        val ocf = stockData.operatingCashFlowHistory.orEmpty()
        val fcf = stockData.freeCashFlowHistory.orEmpty()
        val shares = stockData.sharesOutstandingHistory.orEmpty()
        val allHistories = listOf(rev, ni, ebitda, ocf, fcf, shares)
        val columnCount = (allHistories.maxOfOrNull { it.size } ?: 0).coerceAtMost(5)
        val hasTtm = stockData.hasTtm

        if (columnCount == 0 && !hasTtm) {
            binding.tvHistoryNoData.visibility = View.VISIBLE
            binding.scrollFinancialHistory.visibility = View.GONE
            return
        }
        binding.tvHistoryNoData.visibility = View.GONE
        binding.scrollFinancialHistory.visibility = View.VISIBLE
        val table = binding.tableFinancialHistory
        table.removeAllViews()

        val density = resources.displayMetrics.density
        val dp12 = (12 * density).toInt()
        val dp6 = (6 * density).toInt()
        val labelMinWidth = (130 * density).toInt()
        val dataMinWidth = (72 * density).toInt()

        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        val primaryColor = if (tv.resourceId != 0) ResourcesCompat.getColor(resources, tv.resourceId, null) else 0xff000000.toInt()
        theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        val secondaryColor = if (tv.resourceId != 0) ResourcesCompat.getColor(resources, tv.resourceId, null) else 0xff757575.toInt()
        val accentColor = getColor(R.color.scoreMedium)

        fun labelCell(text: String): TextView = TextView(this).apply {
            setPadding(dp12, dp6, dp12, dp6)
            setTextColor(secondaryColor)
            setTypeface(typeface, Typeface.BOLD)
            setMinimumWidth(labelMinWidth)
            this.text = text
        }

        fun dataCell(text: String, highlight: Boolean = false): TextView = TextView(this).apply {
            setPadding(dp12, dp6, dp12, dp6)
            setTextColor(if (highlight) accentColor else primaryColor)
            setMinimumWidth(dataMinWidth)
            textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            this.text = text
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        val headerRow = TableRow(this).apply {
            addView(labelCell(""))
            if (hasTtm) addView(dataCell(getString(R.string.current_ttm_row), highlight = true))
            for (i in 0 until columnCount) {
                addView(dataCell("FY ${currentYear - 1 - i}"))
            }
        }
        table.addView(headerRow)

        val rowLabelResIds = listOf(
            R.string.revenue,
            R.string.net_income,
            R.string.metric_ebitda,
            R.string.metric_operating_cash_flow,
            R.string.metric_free_cash_flow,
            R.string.metric_outstanding_shares
        )
        val rows = allHistories
        val isCurrency = listOf(true, true, true, true, true, false)
        val ttmValues = listOf(
            stockData.revenueTtm,
            stockData.netIncomeTtm,
            stockData.ebitdaTtm,
            stockData.operatingCashFlowTtm,
            stockData.freeCashFlowTtm,
            null
        )

        for (r in rowLabelResIds.indices) {
            val label = getString(rowLabelResIds[r])
            val values = rows[r]
            val asCurrency = isCurrency[r]
            val tr = TableRow(this).apply {
                addView(labelCell(label))
                if (hasTtm) {
                    val ttmVal = ttmValues[r]
                    val ttmText = if (ttmVal == null) getString(R.string.not_available)
                    else if (asCurrency) formatAbbreviatedCurrency(ttmVal)
                    else formatAbbreviatedShares(ttmVal)
                    addView(dataCell(ttmText, highlight = true))
                }
                for (i in 0 until columnCount) {
                    val v = values.getOrNull(i)
                    val text = if (v == null) getString(R.string.not_available)
                    else if (asCurrency) formatAbbreviatedCurrency(v)
                    else formatAbbreviatedShares(v)
                    addView(dataCell(text))
                }
            }
            table.addView(tr)
        }
    }

    private fun formatAbbreviatedCurrency(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        val sign = if (v < 0) "-" else ""
        val absVal = abs(v)
        return when {
            absVal >= 1_000_000_000 -> "$sign$${"%.1f".format(absVal / 1_000_000_000)}B"
            absVal >= 1_000_000 -> "$sign$${"%.0f".format(absVal / 1_000_000)}M"
            absVal >= 1_000 -> "$sign$${"%.1f".format(absVal / 1_000)}K"
            else -> "$sign$${"%.0f".format(absVal)}"
        }
    }

    private fun formatAbbreviatedShares(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        val sign = if (v < 0) "-" else ""
        val absVal = abs(v)
        return when {
            absVal >= 1_000_000_000 -> "$sign${"%.1f".format(absVal / 1_000_000_000)}B"
            absVal >= 1_000_000 -> "$sign${"%.0f".format(absVal / 1_000_000)}M"
            absVal >= 1_000 -> "$sign${"%.1f".format(absVal / 1_000)}K"
            else -> "$sign${"%.0f".format(absVal)}"
        }
    }

    private fun formatPrice(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "$%.2f".format(v)
    }

    private fun formatNumber(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "%.2f".format(v)
    }

    private fun formatPercent(value: Double?): String {
        val v = value?.takeIf { it.isFinite() } ?: return getString(R.string.not_available)
        return "%.1f%%".format(v * 100)
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
                // From the full analysis screen, jump back into the current Eidos chat
                // without rebinding the conversation to this stock.
                AiAssistantActivity.Companion.start(this, null)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}