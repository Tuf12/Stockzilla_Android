package com.example.stockzilla.feature

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.R
import com.example.stockzilla.ai.AiAssistantActivity
import com.example.stockzilla.ai.AiAssistantViewModel
import com.example.stockzilla.analyst.EidosAnalystActivity
import com.example.stockzilla.analyst.EidosAnalystConfirmedFactMerge
import com.example.stockzilla.analyst.EidosAnalystMetricKey
import com.example.stockzilla.analyst.EidosAnalystPeriodScope
import com.example.stockzilla.data.EidosAnalystConfirmedFactEntity
import com.example.stockzilla.data.CompanyProfileEntity
import com.example.stockzilla.data.QuarterlyFinancialFactEntity
import com.example.stockzilla.data.SecEdgarService
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.databinding.ActivityFullAnalysisBinding
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.sec.EdgarMetricKey
import com.example.stockzilla.sec.TagOverrideResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.time.LocalDate
import kotlin.math.abs

class FullAnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STOCK_DATA = "extra_stock_data"
        private const val STOCK_ANALYSIS_BASE = "https://stockanalysis.com/stocks/"
    }

    private lateinit var binding: ActivityFullAnalysisBinding
    private lateinit var latestStockData: StockData
    private var quarterlyFacts: List<QuarterlyFinancialFactEntity> = emptyList()
    private var stockDataDirty: Boolean = false
    private var tagFixPending: Boolean = false
    private var symbol: String = ""
    private var cik: String? = null
    private val database by lazy { StockzillaDatabase.Companion.getDatabase(this) }
    private val tagFixVm: AiAssistantViewModel by viewModels()
    private var historyMode: HistoryMode = HistoryMode.YEARLY

    /** Primary-scoped analyst confirmations keyed by [com.example.stockzilla.sec.EdgarMetricKey.name]. */
    private var analystConfirmedByMetric: Map<String, EidosAnalystConfirmedFactEntity> = emptyMap()

    /** All analyst-confirmed facts (including per-period rows for the history table). */
    private var analystConfirmedFacts: List<EidosAnalystConfirmedFactEntity> = emptyList()

    private enum class HistoryMode { YEARLY, QUARTERLY }

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

        latestStockData = stockData
        symbol = stockData.symbol.trim().uppercase()
        cik = stockData.cik
        tagFixVm.setInitialSymbol(symbol)

        tagFixVm.tagFixCompletedStock.observe(this) { updated ->
            if (updated == null) return@observe
            tagFixPending = false
            binding.tagFixOverlay.isVisible = false
            tagFixVm.clearTagFixCompletedStock()
            latestStockData = updated
            stockDataDirty = true
            populateRawFactsTable(updated)
            populateDerivedMetricsTable(updated)
            populateFinancialHistoryTable(updated)
            loadBusinessProfile(updated)
            loadQuarterlyFacts()
        }
        tagFixVm.loading.observe(this) { loading ->
            if (loading == true && tagFixPending) {
                binding.tagFixOverlay.isVisible = true
            }
            if (loading == false && tagFixPending) {
                binding.root.post {
                    if (!tagFixPending) return@post
                    tagFixPending = false
                    binding.tagFixOverlay.isVisible = false
                    val err = tagFixVm.error.value
                    if (!err.isNullOrBlank()) {
                        Toast.makeText(this@FullAnalysisActivity, err, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@FullAnalysisActivity,
                            R.string.tag_fix_incomplete,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        binding.tvCompanyName.text = stockData.companyName ?: symbol
        binding.tvSymbol.text = symbol
        binding.toggleHistoryMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            historyMode = when (checkedId) {
                R.id.btnHistoryQuarterly -> HistoryMode.QUARTERLY
                else -> HistoryMode.YEARLY
            }
            updateQuarterlySecButtonVisibility()
            populateFinancialHistoryTable(latestStockData)
        }
        binding.btnHistoryYearly.isChecked = true

        populateRawFactsTable(stockData)
        populateDerivedMetricsTable(stockData)
        populateFinancialHistoryTable(stockData)
        loadBusinessProfile(stockData)
        loadQuarterlyFacts()
        // Analyst facts load asynchronously in onResume; prime immediately so first paint matches
        // after DB read without waiting for another lifecycle pass.
        lifecycleScope.launch { refreshAnalystConfirmedOverrides() }

        if (!cik.isNullOrBlank()) {
            binding.btnView10kSec.visibility = View.VISIBLE
            val paddedCik = cik!!.trim().padStart(10, '0')
            val sec10kUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$paddedCik&type=10-K"
            binding.btnView10kSec.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sec10kUrl)))
            }
        } else {
            binding.btnView10kSec.visibility = View.GONE
        }

        binding.btnViewQuarterlySec.setOnClickListener {
            val localCik = cik?.trim().orEmpty()
            if (localCik.isBlank()) return@setOnClickListener
            val rows = quarterlyFacts
            if (rows.isEmpty()) {
                Toast.makeText(this, getString(R.string.financial_history_no_data), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                    val selectedPeriodEnds = buildQuarterlyTableData(maxColumns = 8).periodEnds

                val factsByPeriodEnd = rows.groupBy { it.periodEnd }
                    .mapValues { (_, facts) ->
                        facts.firstOrNull { it.form != null && it.filedDate != null } ?: facts.first()
                    }

                val formTypes = setOf("10-Q", "10-Q/A", "6-K", "6-K/A")
                val filingMetas = withContext(Dispatchers.IO) {
                    SecEdgarService.getInstance().getRecentFilingsMetadata(
                        cik = localCik,
                        limit = 200,
                        formTypesFilter = formTypes
                    )
                }

                val byFormAndDate = filingMetas.associateBy { Pair(it.formType, it.filingDate) }

                val linkRows = selectedPeriodEnds.mapNotNull { periodEnd ->
                    val fact = factsByPeriodEnd[periodEnd] ?: return@mapNotNull null
                    val form = fact.form
                    val filedDate = fact.filedDate
                    if (form.isNullOrBlank() || filedDate.isNullOrBlank()) return@mapNotNull null
                    val meta = byFormAndDate[Pair(form, filedDate)] ?: return@mapNotNull null
                    val primaryUrl = meta.primaryDocument?.let { meta.secFolderUrl + it } ?: (meta.secFolderUrl + "index.html")
                    val labelPeriod = if (periodEnd.length >= 7) periodEnd.take(7) else periodEnd
                    "$labelPeriod (${meta.formType}, filed ${meta.filingDate})" to primaryUrl
                }

                if (linkRows.isEmpty()) {
                    val padded = localCik.padStart(10, '0')
                    val fallbackType = factsByPeriodEnd.values.firstOrNull()?.form?.takeIf { it.startsWith("6-K") }?.let { "6-K" } ?: "10-Q"
                    val fallbackUrl = "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=$padded&type=$fallbackType"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                    return@launch
                }

                val labels = linkRows.map { it.first }.toTypedArray()
                AlertDialog.Builder(this@FullAnalysisActivity)
                    .setTitle(getString(R.string.view_quarterly_sec))
                    .setItems(labels) { _, which ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkRows[which].second)))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        updateQuarterlySecButtonVisibility()

        binding.btnOpenOnStockAnalysis.setOnClickListener {
            val url = STOCK_ANALYSIS_BASE + symbol.lowercase() + "/"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.btnEidosAnalyst.setOnClickListener {
            startActivity(
                Intent(this, EidosAnalystActivity::class.java).putExtra(
                    EXTRA_STOCK_DATA,
                    latestStockData as java.io.Serializable
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAnalystConfirmedOverrides()
    }

    private fun refreshAnalystConfirmedOverrides() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                database.eidosAnalystConfirmedFactDao().getAllForSymbol(symbol)
            }
            analystConfirmedFacts = rows
            analystConfirmedByMetric = rows
                .filter { it.periodLabel.isBlank() }
                .associateBy { EidosAnalystMetricKey.normalize(it.metricKey) }
            populateRawFactsTable(latestStockData)
            populateFinancialHistoryTable(latestStockData)
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

    private fun updateQuarterlySecButtonVisibility() {
        binding.btnViewQuarterlySec.visibility =
            if (historyMode == HistoryMode.QUARTERLY && !cik.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    private data class RawFactRowSpec(
        val label: String,
        /** Row identity for analyst overrides (matches proposal / [EdgarMetricKey.name]). */
        val metricKey: EdgarMetricKey,
        /** Metric Eidos should adjust when this row is missing (may differ from [metricKey] for EBITDA / FCF). */
        val tagFixKey: EdgarMetricKey,
        val isMissing: Boolean,
        val displayValue: String
    )

    /** Prefer fixing OCF when absent; otherwise capex is required for true FCF. */
    private fun tagFixKeyForMissingFreeCashFlow(stockData: StockData): EdgarMetricKey =
        when {
            stockData.operatingCashFlowDisplay == null -> EdgarMetricKey.OPERATING_CASH_FLOW
            else -> EdgarMetricKey.CAPEX
        }

    /** EBITDA is operating income plus depreciation and amortization; point Eidos at the missing input. */
    private fun tagFixKeyForMissingEbitda(stockData: StockData): EdgarMetricKey =
        when {
            stockData.operatingIncomeDisplay == null -> EdgarMetricKey.EBIT
            stockData.depreciationAmortizationDisplay == null -> EdgarMetricKey.DEPRECIATION
            else -> EdgarMetricKey.EBIT
        }

    private fun populateRawFactsTable(stockData: StockData) {
        val table = binding.tableRawFacts
        table.removeAllViews()
        addMetricHeaderRow(table)

        val specs = listOf(
            RawFactRowSpec(
                getString(R.string.revenue),
                EdgarMetricKey.REVENUE,
                EdgarMetricKey.REVENUE,
                stockData.revenueDisplay == null,
                formatAbbreviatedCurrency(stockData.revenueDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.net_income),
                EdgarMetricKey.NET_INCOME,
                EdgarMetricKey.NET_INCOME,
                stockData.netIncomeDisplay == null,
                formatAbbreviatedCurrency(stockData.netIncomeDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_eps),
                EdgarMetricKey.EPS,
                EdgarMetricKey.EPS,
                stockData.epsDisplay == null,
                formatNumber(stockData.epsDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_ebitda),
                EdgarMetricKey.EBITDA,
                tagFixKeyForMissingEbitda(stockData),
                stockData.ebitdaDisplay == null,
                formatAbbreviatedCurrency(stockData.ebitdaDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_operating_income),
                EdgarMetricKey.EBIT,
                EdgarMetricKey.EBIT,
                stockData.operatingIncomeDisplay == null,
                formatAbbreviatedCurrency(stockData.operatingIncomeDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_depreciation_and_amortization),
                EdgarMetricKey.DEPRECIATION,
                EdgarMetricKey.DEPRECIATION,
                stockData.depreciationAmortizationDisplay == null,
                formatAbbreviatedCurrency(stockData.depreciationAmortizationDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_cogs),
                EdgarMetricKey.COST_OF_GOODS,
                EdgarMetricKey.COST_OF_GOODS,
                stockData.costOfGoodsSoldDisplay == null,
                formatAbbreviatedCurrency(stockData.costOfGoodsSoldDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_gross_profit),
                EdgarMetricKey.GROSS_PROFIT,
                EdgarMetricKey.GROSS_PROFIT,
                stockData.grossProfitDisplay == null,
                formatAbbreviatedCurrency(stockData.grossProfitDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_operating_cash_flow),
                EdgarMetricKey.OPERATING_CASH_FLOW,
                EdgarMetricKey.OPERATING_CASH_FLOW,
                stockData.operatingCashFlowDisplay == null,
                formatAbbreviatedCurrency(stockData.operatingCashFlowDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_capex),
                EdgarMetricKey.CAPEX,
                EdgarMetricKey.CAPEX,
                stockData.capexDisplay == null,
                formatAbbreviatedCurrency(stockData.capexDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_free_cash_flow),
                EdgarMetricKey.FREE_CASH_FLOW,
                tagFixKeyForMissingFreeCashFlow(stockData),
                stockData.freeCashFlowDisplay == null,
                formatAbbreviatedCurrency(stockData.freeCashFlowDisplay)
            ),
            RawFactRowSpec(
                getString(R.string.metric_outstanding_shares),
                EdgarMetricKey.SHARES_OUTSTANDING,
                EdgarMetricKey.SHARES_OUTSTANDING,
                stockData.outstandingShares == null,
                formatAbbreviatedShares(stockData.outstandingShares)
            ),
            RawFactRowSpec(
                getString(R.string.metric_total_assets),
                EdgarMetricKey.TOTAL_ASSETS,
                EdgarMetricKey.TOTAL_ASSETS,
                stockData.totalAssets == null,
                formatAbbreviatedCurrency(stockData.totalAssets)
            ),
            RawFactRowSpec(
                getString(R.string.metric_total_liabilities),
                EdgarMetricKey.TOTAL_LIABILITIES,
                EdgarMetricKey.TOTAL_LIABILITIES,
                stockData.totalLiabilities == null,
                formatAbbreviatedCurrency(stockData.totalLiabilities)
            ),
            RawFactRowSpec(
                getString(R.string.metric_total_debt),
                EdgarMetricKey.TOTAL_DEBT,
                EdgarMetricKey.TOTAL_DEBT,
                stockData.totalDebt == null,
                formatAbbreviatedCurrency(stockData.totalDebt)
            ),
            RawFactRowSpec(
                getString(R.string.metric_cash_and_equivalents),
                EdgarMetricKey.CASH_AND_EQUIVALENTS,
                EdgarMetricKey.CASH_AND_EQUIVALENTS,
                stockData.cashAndEquivalents == null,
                formatAbbreviatedCurrency(stockData.cashAndEquivalents)
            ),
            RawFactRowSpec(
                getString(R.string.metric_accounts_receivable),
                EdgarMetricKey.ACCOUNTS_RECEIVABLE,
                EdgarMetricKey.ACCOUNTS_RECEIVABLE,
                stockData.accountsReceivable == null,
                formatAbbreviatedCurrency(stockData.accountsReceivable)
            ),
            RawFactRowSpec(
                getString(R.string.metric_retained_earnings),
                EdgarMetricKey.RETAINED_EARNINGS,
                EdgarMetricKey.RETAINED_EARNINGS,
                stockData.retainedEarnings == null,
                formatAbbreviatedCurrency(stockData.retainedEarnings)
            )
        )

        specs.forEach { addRawFactRow(table, it, stockData, analystConfirmedByMetric) }
        val known = specs.map { it.metricKey.name }.toSet()
        analystConfirmedByMetric
            .filterKeys { it !in known }
            .toSortedMap()
            .forEach { (metricKey, fact) ->
                val row = TableRow(this)
                row.addView(buildCell(EidosAnalystMetricKey.humanizeMetricKey(metricKey)))
                row.addView(buildCell(fact.valueText, analystConfirmed = true))
                table.addView(row)
            }
    }

    private fun addRawFactRow(
        table: TableLayout,
        spec: RawFactRowSpec,
        stockData: StockData,
        analystByMetric: Map<String, EidosAnalystConfirmedFactEntity>,
    ) {
        when (
            val merged = EidosAnalystConfirmedFactMerge.mergeRawFactCell(
                spec.displayValue,
                spec.isMissing,
                analystByMetric[spec.metricKey.name],
            )
        ) {
            is EidosAnalystConfirmedFactMerge.RawFactCellMergeOutcome.ShowTagFixButton -> {
                val row = TableRow(this)
                row.addView(buildCell(spec.label))
                val dp8 = (8 * resources.displayMetrics.density).toInt()
                val btn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                    text = getString(R.string.tag_fix_find_tag)
                    setPadding(dp8, paddingTop, dp8, paddingBottom)
                    setOnClickListener { launchTagFixForMetric(stockData, spec.tagFixKey) }
                }
                row.addView(btn)
                table.addView(row)
            }
            is EidosAnalystConfirmedFactMerge.RawFactCellMergeOutcome.ValueCell -> {
                val row = TableRow(this)
                row.addView(buildCell(spec.label))
                val display = if (merged.fromAnalyst) {
                    formatAnalystDisplayForRawMetric(spec.metricKey, merged.displayText)
                } else {
                    merged.displayText
                }
                row.addView(buildCell(display, analystConfirmed = merged.fromAnalyst))
                table.addView(row)
            }
        }
    }

    /**
     * Column context for quarterly or annual history cells (Eidos tag fix / scoped override hint).
     * [fiscalPeriod] null means an annual FY column.
     */
    private data class HistoryColumnContext(
        val fiscalYear: Int,
        val fiscalPeriod: String?,
        val periodEnd: String?,
        val accessionHint: String?,
    )

    private fun launchTagFixForMetric(
        stockData: StockData,
        metricKey: EdgarMetricKey,
        column: HistoryColumnContext? = null
    ) {
        val cik = stockData.cik
        val symLog = stockData.symbol.trim().uppercase()
        if (cik.isNullOrBlank()) {
            DiagnosticsLogger.log(
                symLog,
                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_UI_ABORT",
                "Find tag (Eidos): no CIK",
                "metricKey=${metricKey.name}"
            )
            Toast.makeText(this, R.string.tag_fix_no_cik, Toast.LENGTH_LONG).show()
            return
        }
        val cikTrim = cik.trim()
        lifecycleScope.launch {
            DiagnosticsLogger.log(
                symLog,
                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_UI_START",
                "Find tag (Eidos): loading company facts",
                "metricKey=${metricKey.name} fy=${column?.fiscalYear} fp=${column?.fiscalPeriod} periodEnd=${column?.periodEnd}"
            )
            val facts = withContext(Dispatchers.IO) {
                SecEdgarService.getInstance().getCompanyFacts(cikTrim)
            }
            if (facts == null) {
                DiagnosticsLogger.log(
                    symLog,
                    "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_UI_FACTS_FAIL",
                    "companyfacts fetch failed for CIK $cikTrim",
                    "metricKey=${metricKey.name}"
                )
                Toast.makeText(this@FullAnalysisActivity, R.string.tag_fix_facts_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val indexJson = SecEdgarService.getInstance().buildFactsConceptIndexJson(facts)
            if (tagFixVm.requiresApiKey.value != false) {
                DiagnosticsLogger.log(
                    symLog,
                    "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_UI_SKIP_NO_KEY",
                    "Find tag (Eidos): AI API key not configured",
                    "metricKey=${metricKey.name}"
                )
                Toast.makeText(
                    this@FullAnalysisActivity,
                    R.string.ai_assistant_missing_key_hint,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val resolvedAcc = withContext(Dispatchers.IO) {
                column?.accessionHint?.takeIf { it.isNotBlank() }
                    ?: run {
                        val col = column ?: return@run null
                        if (!col.periodEnd.isNullOrBlank() && col.fiscalPeriod != null) {
                            SecEdgarService.getInstance()
                                .resolveFilingForFiscalQuarter(
                                    cikTrim,
                                    col.periodEnd,
                                    col.fiscalYear,
                                    col.fiscalPeriod
                                )?.accessionNumber
                        } else {
                            SecEdgarService.getInstance()
                                .resolveFilingForFiscalYear(cikTrim, col.fiscalYear)?.accessionNumber
                        }
                    }
            }
            val viewerUrl =
                if (!resolvedAcc.isNullOrBlank()) SecEdgarService.getInstance().filingViewerUrl(cikTrim, resolvedAcc)
                else null
            val suggestedScope =
                column?.let { TagOverrideResolver.formatScopedKey(it.fiscalYear, it.fiscalPeriod) }
            val sym = stockData.symbol.trim().uppercase()
            DiagnosticsLogger.log(
                sym,
                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_UI_BOOTSTRAP",
                "Opening Eidos overlay; runTagFixBootstrap next",
                "factsIndexChars=${indexJson.length} suggestedScope=${suggestedScope ?: ""}"
            )
            tagFixVm.setInitialSymbol(sym)
            tagFixPending = true
            binding.tagFixOverlay.isVisible = true
            tagFixVm.runTagFixBootstrap(
                symbol = sym,
                metricKey = metricKey.name,
                factsIndexJson = indexJson,
                fiscalYear = column?.fiscalYear,
                fiscalPeriod = column?.fiscalPeriod,
                periodEnd = column?.periodEnd,
                accessionNumber = resolvedAcc,
                filingViewerUrl = viewerUrl,
                cik = cikTrim,
                suggestedScopeKey = suggestedScope
            )
        }
    }

    private fun representativeQuarterlyAccession(rowsByMetric: Map<String, QuarterlyFinancialFactEntity>): String? {
        val priority = listOf(
            "REVENUE", "NET_INCOME", "GROSS_PROFIT", "OPERATING_CASH_FLOW",
            "EBITDA", "TOTAL_DEBT", "SHARES_OUTSTANDING", "CAPEX", "DEPRECIATION", "FREE_CASH_FLOW"
        )
        for (k in priority) {
            rowsByMetric[k]?.accessionNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return rowsByMetric.values.firstOrNull { !it.accessionNumber.isNullOrBlank() }?.accessionNumber?.trim()
    }

    private fun buildMissingTagFixCellStrip(
        stockData: StockData,
        metricKey: EdgarMetricKey,
        column: HistoryColumnContext,
        primaryColor: Int,
        dataMinWidth: Int,
        dp12: Int,
        dp6: Int,
        dp8: Int,
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp12, dp6, dp12, dp6)
            setMinimumWidth(dataMinWidth)
        }
        val na = TextView(this).apply {
            setPadding(0, 0, dp6, 0)
            setTextColor(primaryColor)
            text = getString(R.string.not_available)
            textAlignment = View.TEXT_ALIGNMENT_TEXT_END
        }
        fun smallButton(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.buttonStyleSmall).apply {
            this.text = text
            setPadding(dp8, paddingTop, dp8, paddingBottom)
            setOnClickListener { onClick() }
        }
        container.addView(
            na,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(
            smallButton(getString(R.string.tag_fix_tag_short)) {
                launchTagFixForMetric(stockData, metricKey, column)
            }
        )
        return container
    }

    override fun finish() {
        if (stockDataDirty) {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_STOCK_DATA, latestStockData as java.io.Serializable)
            )
        }
        super.finish()
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

    private fun buildCell(text: String, header: Boolean = false, analystConfirmed: Boolean = false): TextView {
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
        val analystColor = ResourcesCompat.getColor(resources, R.color.eidos_analyst_confirmed_value, null)
        return TextView(this).apply {
            setPadding(dp8, dp4, dp8, dp4)
            when {
                header -> setTextColor(secondaryColor)
                analystConfirmed -> setTextColor(analystColor)
                else -> setTextColor(primaryColor)
            }
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

    private enum class HistoryValueKind { CURRENCY, SHARES, PERCENT }

    private data class HistoryTableRow(
        val labelRes: Int,
        val tagFixKey: EdgarMetricKey,
        /** Null for derived % rows; otherwise used for analyst-confirmed history overrides. */
        val analystMetricKey: EdgarMetricKey?,
        val annualValues: List<Double?>,
        /** Key for [QuarterlyHistoryTableData.valuesByMetric] (string resource id). */
        val quarterlyValuesKey: Int,
        val ttmValue: Double?,
        val valueKind: HistoryValueKind,
        /** False for derived rows (margins, EBITDA, FCF): no per-cell tag/filing actions. */
        val allowTagFix: Boolean,
    )

    private fun ratioHistorySeries(numerator: List<Double?>, denominator: List<Double?>): List<Double?> {
        val n = kotlin.math.max(numerator.size, denominator.size)
        return List(n) { i ->
            val num = numerator.getOrNull(i)
            val den = denominator.getOrNull(i)
            if (num != null && den != null && abs(den) > 1e-9) num / den else null
        }
    }

    private fun grossProfitTtmForHistory(stockData: StockData): Double? {
        if (!stockData.hasTtm) return null
        val r = stockData.revenueTtm
        val c = stockData.costOfGoodsSoldTtm
        return if (r != null && c != null) r - c else null
    }

    private fun populateFinancialHistoryTable(stockData: StockData) {
        val grossMarginAnnual = ratioHistorySeries(stockData.grossProfitHistory, stockData.revenueHistory)
        val netMarginAnnual = ratioHistorySeries(stockData.netIncomeHistory, stockData.revenueHistory)
        val revTtm = stockData.revenueTtm
        val grossMarginTtm = if (stockData.hasTtm && revTtm != null) {
            val gp = grossProfitTtmForHistory(stockData)
            if (gp != null && abs(revTtm) > 1e-9) gp / revTtm else null
        } else null
        val netMarginTtm = if (stockData.hasTtm && revTtm != null && stockData.netIncomeTtm != null) {
            if (abs(revTtm) > 1e-9) stockData.netIncomeTtm!! / revTtm else null
        } else null

        val historyRows = listOf(
            HistoryTableRow(
                R.string.revenue,
                EdgarMetricKey.REVENUE,
                EdgarMetricKey.REVENUE,
                stockData.revenueHistory,
                R.string.revenue,
                stockData.revenueTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_gross_profit,
                EdgarMetricKey.GROSS_PROFIT,
                EdgarMetricKey.GROSS_PROFIT,
                stockData.grossProfitHistory,
                R.string.metric_gross_profit,
                grossProfitTtmForHistory(stockData),
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_history_gross_margin_pct,
                EdgarMetricKey.GROSS_PROFIT,
                null,
                grossMarginAnnual,
                R.string.metric_history_gross_margin_pct,
                grossMarginTtm,
                HistoryValueKind.PERCENT,
                allowTagFix = false
            ),
            HistoryTableRow(
                R.string.net_income,
                EdgarMetricKey.NET_INCOME,
                EdgarMetricKey.NET_INCOME,
                stockData.netIncomeHistory,
                R.string.net_income,
                stockData.netIncomeTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_history_net_margin_pct,
                EdgarMetricKey.NET_INCOME,
                null,
                netMarginAnnual,
                R.string.metric_history_net_margin_pct,
                netMarginTtm,
                HistoryValueKind.PERCENT,
                allowTagFix = false
            ),
            HistoryTableRow(
                R.string.metric_ebitda,
                tagFixKeyForMissingEbitda(stockData),
                EdgarMetricKey.EBITDA,
                stockData.ebitdaHistory,
                R.string.metric_ebitda,
                stockData.ebitdaTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = false
            ),
            HistoryTableRow(
                R.string.metric_depreciation_and_amortization,
                EdgarMetricKey.DEPRECIATION,
                EdgarMetricKey.DEPRECIATION,
                stockData.depreciationAmortizationHistory.orEmpty(),
                R.string.metric_depreciation_and_amortization,
                stockData.depreciationAmortizationTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_operating_cash_flow,
                EdgarMetricKey.OPERATING_CASH_FLOW,
                EdgarMetricKey.OPERATING_CASH_FLOW,
                stockData.operatingCashFlowHistory.orEmpty(),
                R.string.metric_operating_cash_flow,
                stockData.operatingCashFlowTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_capex,
                EdgarMetricKey.CAPEX,
                EdgarMetricKey.CAPEX,
                stockData.capexHistory.orEmpty(),
                R.string.metric_capex,
                stockData.capexTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_free_cash_flow,
                tagFixKeyForMissingFreeCashFlow(stockData),
                EdgarMetricKey.FREE_CASH_FLOW,
                stockData.freeCashFlowHistory.orEmpty(),
                R.string.metric_free_cash_flow,
                stockData.freeCashFlowTtm,
                HistoryValueKind.CURRENCY,
                allowTagFix = false
            ),
            HistoryTableRow(
                R.string.metric_total_debt,
                EdgarMetricKey.TOTAL_DEBT,
                EdgarMetricKey.TOTAL_DEBT,
                stockData.totalDebtHistory,
                R.string.metric_total_debt,
                null,
                HistoryValueKind.CURRENCY,
                allowTagFix = true
            ),
            HistoryTableRow(
                R.string.metric_outstanding_shares,
                EdgarMetricKey.SHARES_OUTSTANDING,
                EdgarMetricKey.SHARES_OUTSTANDING,
                stockData.sharesOutstandingHistory.orEmpty(),
                R.string.metric_outstanding_shares,
                null,
                HistoryValueKind.SHARES,
                allowTagFix = true
            )
        )

        val annualColumnCount = historyRows.maxOfOrNull { it.annualValues.size }?.coerceAtMost(5) ?: 0
        val hasTtm = stockData.hasTtm
        val quarterlyData = buildQuarterlyTableData()
        val quarterlyColumnCount = quarterlyData.periodLabels.size

        val knownHistoryMetricKeys = historyRows.mapNotNull { it.analystMetricKey?.name }.toSet()
        val dynamicHistoryMetricKeys = analystConfirmedFacts
            .map { EidosAnalystMetricKey.normalize(it.metricKey) }
            .filter { it.isNotBlank() && it !in knownHistoryMetricKeys }
            .distinct()
            .sorted()

        val activeColumnCount = if (historyMode == HistoryMode.QUARTERLY && quarterlyColumnCount > 0) {
            quarterlyColumnCount
        } else {
            annualColumnCount
        }
        if (activeColumnCount == 0 && !hasTtm) {
            if (dynamicHistoryMetricKeys.isEmpty()) {
                binding.tvHistoryNoData.visibility = View.VISIBLE
                binding.scrollFinancialHistory.visibility = View.GONE
                binding.tableFinancialHistory.removeAllViews()
                binding.tableFinancialHistoryAnalyst.removeAllViews()
                binding.tvFinancialHistoryAnalystSection.visibility = View.GONE
                binding.tableFinancialHistoryAnalyst.visibility = View.GONE
                return
            }
            binding.tvHistoryNoData.visibility = View.GONE
            binding.scrollFinancialHistory.visibility = View.VISIBLE
            binding.tableFinancialHistory.removeAllViews()
            populateAnalystOnlyFinancialHistoryTable(
                dynamicHistoryMetricKeys,
                quarterlyColumnCount,
            )
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
        val analystHistoryColor = ResourcesCompat.getColor(resources, R.color.eidos_analyst_confirmed_value, null)

        fun labelCell(text: String): TextView = TextView(this).apply {
            setPadding(dp12, dp6, dp12, dp6)
            setTextColor(secondaryColor)
            setTypeface(typeface, Typeface.BOLD)
            setMinimumWidth(labelMinWidth)
            this.text = text
        }

        fun dataCell(text: String, highlight: Boolean = false, analystConfirmed: Boolean = false): TextView =
            TextView(this).apply {
                setPadding(dp12, dp6, dp12, dp6)
                setTextColor(
                    when {
                        analystConfirmed -> analystHistoryColor
                        highlight -> accentColor
                        else -> primaryColor
                    }
                )
                setMinimumWidth(dataMinWidth)
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                this.text = text
            }

        fun formatHistoryValue(kind: HistoryValueKind, value: Double?): String {
            return when (kind) {
                HistoryValueKind.CURRENCY -> formatAbbreviatedCurrency(value)
                HistoryValueKind.SHARES -> formatAbbreviatedShares(value)
                HistoryValueKind.PERCENT -> formatPercent(value)
            }
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val annualColumnContexts: List<HistoryColumnContext> = if (annualColumnCount > 0) {
            List(annualColumnCount) { i ->
                HistoryColumnContext(
                    fiscalYear = currentYear - 1 - i,
                    fiscalPeriod = null,
                    periodEnd = null,
                    accessionHint = null
                )
            }
        } else {
            emptyList()
        }
        val quarterlyColumnContexts = quarterlyData.columnContexts
        val existingQuarterlyLabels = quarterlyData.periodLabels.toSet()
        val existingAnnualYears = annualColumnContexts.map { it.fiscalYear }.toSet()
        // Extra period columns on the SEC grid only when a known (mapped) metric has analyst data
        // for a fiscal period that the mechanical table does not already include.
        val factsForKnownScopedHistory = analystConfirmedFacts.filter {
            EidosAnalystMetricKey.normalize(it.metricKey) in knownHistoryMetricKeys &&
                EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel).isNotEmpty()
        }
        val quarterlyPeriodRegex = Regex("""Q[1-4] (19|20)\d{2}""")
        val extraQuarterlyLabels = factsForKnownScopedHistory
            .map { EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel) }
            .filter { it.matches(quarterlyPeriodRegex) && it !in existingQuarterlyLabels }
            .distinct()
            .sortedWith(
                compareByDescending<String> { it.takeLast(4).toIntOrNull() ?: 0 }
                    .thenByDescending { it.getOrNull(1)?.digitToIntOrNull() ?: 0 }
            )
        val extraAnnualYears = factsForKnownScopedHistory
            .map { EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel) }
            .mapNotNull { canonicalPeriodToFiscalYear(it) }
            .filter { it !in existingAnnualYears }
            .distinct()
            .sortedDescending()

        val headerRow = TableRow(this).apply {
            addView(labelCell(""))
            if (hasTtm) addView(dataCell(getString(R.string.current_ttm_row), highlight = true))
            if (historyMode == HistoryMode.QUARTERLY && quarterlyColumnCount > 0) {
                for (label in quarterlyData.periodLabels) {
                    addView(dataCell(label))
                }
                for (label in extraQuarterlyLabels) {
                    addView(dataCell(label, highlight = true))
                }
            } else {
                for (i in 0 until annualColumnCount) {
                    addView(dataCell("FY ${currentYear - 1 - i}"))
                }
                for (year in extraAnnualYears) {
                    addView(dataCell("FY $year", highlight = true))
                }
            }
        }
        table.addView(headerRow)

        for (row in historyRows) {
            val label = getString(row.labelRes)
            val quarterlyValues = quarterlyData.valuesByMetric[row.quarterlyValuesKey].orEmpty()
            val rowMissingAllHistorical = if (historyMode == HistoryMode.QUARTERLY && quarterlyColumnCount > 0) {
                quarterlyValues.none { it != null }
            } else {
                row.annualValues.none { it != null }
            }
            val rowMissingTtm = row.ttmValue == null
            val rowMissingEverywhere = rowMissingAllHistorical && rowMissingTtm
            val tr = TableRow(this).apply {
                val labelText = if (rowMissingEverywhere && row.allowTagFix) {
                    "$label (${getString(R.string.tag_fix_find_tag)})"
                } else {
                    label
                }
                val labelView = labelCell(labelText).apply {
                    if (rowMissingEverywhere && row.allowTagFix) {
                        setOnClickListener { launchTagFixForMetric(stockData, row.tagFixKey, null) }
                    }
                }
                addView(labelView)
                if (hasTtm) {
                    val mk = row.analystMetricKey
                    val ttmFact = if (mk != null) {
                        EidosAnalystPeriodScope.findForTtmHistoryCell(analystConfirmedFacts, mk)
                    } else null
                    if (ttmFact != null) {
                        addView(
                            dataCell(
                                formatAnalystDisplayForHistoryValue(row.valueKind, ttmFact.valueText),
                                analystConfirmed = true
                            )
                        )
                    } else {
                        val ttmText = if (row.ttmValue == null) {
                            getString(R.string.not_available)
                        } else {
                            formatHistoryValue(row.valueKind, row.ttmValue)
                        }
                        addView(dataCell(ttmText, highlight = true))
                    }
                }
                if (historyMode == HistoryMode.QUARTERLY && quarterlyColumnCount > 0) {
                    val dp8 = (8 * density).toInt()
                    for (i in 0 until quarterlyColumnCount) {
                        val v = quarterlyValues.getOrNull(i)
                        val isMissing = v == null
                        val col = quarterlyColumnContexts.getOrNull(i)
                        val mk = row.analystMetricKey
                        val qFact = if (mk != null && col != null && col.fiscalPeriod != null) {
                            EidosAnalystPeriodScope.findForQuarterlyHistoryCell(
                                analystConfirmedFacts,
                                mk,
                                col.fiscalYear,
                                col.fiscalPeriod,
                            )
                        } else null
                        if (qFact != null) {
                            addView(
                                dataCell(
                                    formatAnalystDisplayForHistoryValue(row.valueKind, qFact.valueText),
                                    analystConfirmed = true
                                )
                            )
                        } else if (isMissing && row.allowTagFix) {
                            if (col != null) {
                                addView(
                                    buildMissingTagFixCellStrip(
                                        stockData,
                                        row.tagFixKey,
                                        col,
                                        primaryColor,
                                        dataMinWidth,
                                        dp12,
                                        dp6,
                                        dp8
                                    )
                                )
                            } else {
                                addView(dataCell(getString(R.string.not_available), highlight = true))
                            }
                        } else {
                            val text = if (isMissing) getString(R.string.not_available)
                            else formatHistoryValue(row.valueKind, v)
                            addView(dataCell(text, highlight = isMissing))
                        }
                    }
                    // Extra analyst-added periods are reserved for analyst-only dynamic rows.
                    for (period in extraQuarterlyLabels) {
                        val mk = row.analystMetricKey
                        val xFact = if (mk != null) {
                            findAnalystConfirmedFactForScopedPeriod(mk, period)
                        } else {
                            null
                        }
                        if (xFact != null) {
                            addView(
                                dataCell(
                                    formatAnalystDisplayForHistoryValue(row.valueKind, xFact.valueText),
                                    analystConfirmed = true
                                )
                            )
                        } else {
                            addView(dataCell(getString(R.string.not_available), highlight = true))
                        }
                    }
                } else {
                    val dp8 = (8 * density).toInt()
                    for (i in 0 until annualColumnCount) {
                        val v = row.annualValues.getOrNull(i)
                        val isMissing = v == null
                        val col = annualColumnContexts.getOrNull(i)
                        val mk = row.analystMetricKey
                        val aFact = if (mk != null && col != null) {
                            EidosAnalystPeriodScope.findForAnnualHistoryCell(
                                analystConfirmedFacts,
                                mk,
                                col.fiscalYear,
                            )
                        } else null
                        if (aFact != null) {
                            addView(
                                dataCell(
                                    formatAnalystDisplayForHistoryValue(row.valueKind, aFact.valueText),
                                    analystConfirmed = true
                                )
                            )
                        } else if (isMissing && row.allowTagFix) {
                            if (col != null) {
                                addView(
                                    buildMissingTagFixCellStrip(
                                        stockData,
                                        row.tagFixKey,
                                        col,
                                        primaryColor,
                                        dataMinWidth,
                                        dp12,
                                        dp6,
                                        dp8
                                    )
                                )
                            } else {
                                addView(dataCell(getString(R.string.not_available), highlight = true))
                            }
                        } else {
                            val text = if (isMissing) getString(R.string.not_available)
                            else formatHistoryValue(row.valueKind, v)
                            addView(dataCell(text, highlight = isMissing))
                        }
                    }
                    // Extra analyst-added periods are reserved for analyst-only dynamic rows.
                    for (year in extraAnnualYears) {
                        val mk = row.analystMetricKey
                        val xFact = if (mk != null) {
                            EidosAnalystPeriodScope.findForAnnualHistoryCell(
                                analystConfirmedFacts,
                                mk,
                                year,
                            )
                        } else {
                            null
                        }
                        if (xFact != null) {
                            addView(
                                dataCell(
                                    formatAnalystDisplayForHistoryValue(row.valueKind, xFact.valueText),
                                    analystConfirmed = true
                                )
                            )
                        } else {
                            addView(dataCell(getString(R.string.not_available), highlight = true))
                        }
                    }
                }
            }
            table.addView(tr)
        }

        populateAnalystOnlyFinancialHistoryTable(
            dynamicHistoryMetricKeys,
            quarterlyColumnCount,
        )
    }

    /** FY year from `FY YYYY`, or calendar year from `Qx YYYY` (for extra annual columns only). */
    private fun canonicalPeriodToFiscalYear(canonical: String): Int? {
        if (canonical.startsWith("FY ")) return canonical.removePrefix("FY ").trim().toIntOrNull()
        val qy = Regex("""Q[1-4] (19|20)\d{2}""")
        if (canonical.matches(qy)) return canonical.takeLast(4).toIntOrNull()
        return null
    }

    private fun findAnalystConfirmedFactForScopedPeriod(
        metricKey: EdgarMetricKey,
        periodLabel: String,
    ): EidosAnalystConfirmedFactEntity? {
        val normalizedPeriod = EidosAnalystPeriodScope.canonicalizePeriodLabel(periodLabel)
        return analystConfirmedFacts.firstOrNull { fact ->
            EidosAnalystMetricKey.matches(fact.metricKey, metricKey) &&
                EidosAnalystPeriodScope.matchesPeriod(fact.periodLabel, listOf(normalizedPeriod))
        }
    }

    private fun findAnalystConfirmedFactForDynamicMetric(
        metricKeyNormalized: String,
        periodLabel: String,
    ): EidosAnalystConfirmedFactEntity? {
        val normalizedPeriod = EidosAnalystPeriodScope.canonicalizePeriodLabel(periodLabel)
        return analystConfirmedFacts.firstOrNull { fact ->
            EidosAnalystMetricKey.normalize(fact.metricKey) == metricKeyNormalized &&
                EidosAnalystPeriodScope.matchesPeriod(fact.periodLabel, listOf(normalizedPeriod))
        }
    }

    private fun analystScopedFactMatchesFiscalYear(fact: EidosAnalystConfirmedFactEntity, yearDigits: String): Boolean {
        val c = EidosAnalystPeriodScope.canonicalizePeriodLabel(fact.periodLabel)
        return when {
            c.startsWith("FY ") && c.removePrefix("FY ").trim() == yearDigits -> true
            c.matches(Regex("""Q[1-4] $yearDigits""")) -> true
            else -> false
        }
    }

    private fun findAnalystConfirmedFactForDynamicFiscalYear(
        metricKeyNormalized: String,
        fiscalYear: Int,
    ): EidosAnalystConfirmedFactEntity? {
        val enumKey = EdgarMetricKey.entries.firstOrNull { it.name == metricKeyNormalized }
        if (enumKey != null) {
            return EidosAnalystPeriodScope.findForAnnualHistoryCell(analystConfirmedFacts, enumKey, fiscalYear)
        }
        val y = fiscalYear.toString()
        return analystConfirmedFacts.firstOrNull { fact ->
            EidosAnalystMetricKey.normalize(fact.metricKey) == metricKeyNormalized &&
                analystScopedFactMatchesFiscalYear(fact, y)
        }
    }

    /**
     * Metrics that are not part of the standard SEC history row set get their own compact table so the
     * primary grid is not widened with empty columns.
     */
    private fun populateAnalystOnlyFinancialHistoryTable(
        dynamicMetricKeys: List<String>,
        quarterlyColumnCount: Int,
    ) {
        val sectionTitle = binding.tvFinancialHistoryAnalystSection
        val analystTable = binding.tableFinancialHistoryAnalyst
        if (dynamicMetricKeys.isEmpty()) {
            sectionTitle.visibility = View.GONE
            analystTable.visibility = View.GONE
            analystTable.removeAllViews()
            return
        }
        val dynFacts = analystConfirmedFacts.filter {
            EidosAnalystMetricKey.normalize(it.metricKey) in dynamicMetricKeys
        }
        val quarterlyPeriodRegex = Regex("""Q[1-4] (19|20)\d{2}""")
        val showTtm = dynFacts.any { fact ->
            EidosAnalystPeriodScope.matchesPeriod(fact.periodLabel, EidosAnalystPeriodScope.ttmLookupKeys())
        }
        val hasScopedPeriod = dynFacts.any {
            EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel).isNotEmpty()
        }
        if (!hasScopedPeriod && !showTtm) {
            sectionTitle.visibility = View.GONE
            analystTable.visibility = View.GONE
            analystTable.removeAllViews()
            return
        }

        sectionTitle.visibility = View.VISIBLE
        analystTable.visibility = View.VISIBLE
        analystTable.removeAllViews()

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
        val analystHistoryColor = ResourcesCompat.getColor(resources, R.color.eidos_analyst_confirmed_value, null)

        fun labelCell(text: String): TextView = TextView(this@FullAnalysisActivity).apply {
            setPadding(dp12, dp6, dp12, dp6)
            setTextColor(secondaryColor)
            setTypeface(typeface, Typeface.BOLD)
            setMinimumWidth(labelMinWidth)
            this.text = text
        }
        fun dataCell(text: String, highlight: Boolean = false, analystConfirmed: Boolean = false): TextView =
            TextView(this@FullAnalysisActivity).apply {
                setPadding(dp12, dp6, dp12, dp6)
                setTextColor(
                    when {
                        analystConfirmed -> analystHistoryColor
                        highlight -> accentColor
                        else -> primaryColor
                    }
                )
                setMinimumWidth(dataMinWidth)
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                this.text = text
            }

        if (historyMode == HistoryMode.QUARTERLY && quarterlyColumnCount > 0) {
            val periodCols = dynFacts
                .map { EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel) }
                .filter { it.matches(quarterlyPeriodRegex) }
                .distinct()
                .sortedWith(
                    compareByDescending<String> { it.takeLast(4).toIntOrNull() ?: 0 }
                        .thenByDescending { it.getOrNull(1)?.digitToIntOrNull() ?: 0 }
                )
            if (periodCols.isEmpty()) {
                val yearCols = dynFacts
                    .map { EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel) }
                    .mapNotNull { canonicalPeriodToFiscalYear(it) }
                    .distinct()
                    .sortedDescending()
                val headerRow = TableRow(this).apply {
                    addView(labelCell(""))
                    if (showTtm) addView(dataCell(getString(R.string.current_ttm_row), highlight = true))
                    for (y in yearCols) {
                        addView(dataCell("FY $y", highlight = true))
                    }
                }
                analystTable.addView(headerRow)
                for (mk in dynamicMetricKeys) {
                    val tr = TableRow(this).apply {
                        addView(labelCell(EidosAnalystMetricKey.humanizeMetricKey(mk)))
                        if (showTtm) {
                            val f = findAnalystConfirmedFactForDynamicMetric(mk, "TTM")
                            addView(
                                if (f != null) dataCell(f.valueText, analystConfirmed = true)
                                else dataCell(getString(R.string.not_available), highlight = true)
                            )
                        }
                        for (y in yearCols) {
                            val f = findAnalystConfirmedFactForDynamicFiscalYear(mk, y)
                            addView(
                                if (f != null) dataCell(f.valueText, analystConfirmed = true)
                                else dataCell(getString(R.string.not_available), highlight = true)
                            )
                        }
                    }
                    analystTable.addView(tr)
                }
                return
            }
            val headerRow = TableRow(this).apply {
                addView(labelCell(""))
                if (showTtm) addView(dataCell(getString(R.string.current_ttm_row), highlight = true))
                for (label in periodCols) {
                    addView(dataCell(label, highlight = true))
                }
            }
            analystTable.addView(headerRow)
            for (mk in dynamicMetricKeys) {
                val tr = TableRow(this).apply {
                    addView(labelCell(EidosAnalystMetricKey.humanizeMetricKey(mk)))
                    if (showTtm) {
                        val f = findAnalystConfirmedFactForDynamicMetric(mk, "TTM")
                        addView(
                            if (f != null) dataCell(f.valueText, analystConfirmed = true)
                            else dataCell(getString(R.string.not_available), highlight = true)
                        )
                    }
                    for (period in periodCols) {
                        val f = findAnalystConfirmedFactForDynamicMetric(mk, period)
                        addView(
                            if (f != null) dataCell(f.valueText, analystConfirmed = true)
                            else dataCell(getString(R.string.not_available), highlight = true)
                        )
                    }
                }
                analystTable.addView(tr)
            }
        } else {
            val yearCols = dynFacts
                .map { EidosAnalystPeriodScope.canonicalizePeriodLabel(it.periodLabel) }
                .mapNotNull { canonicalPeriodToFiscalYear(it) }
                .distinct()
                .sortedDescending()
            val headerRow = TableRow(this).apply {
                addView(labelCell(""))
                if (showTtm) addView(dataCell(getString(R.string.current_ttm_row), highlight = true))
                for (y in yearCols) {
                    addView(dataCell("FY $y", highlight = true))
                }
            }
            analystTable.addView(headerRow)
            for (mk in dynamicMetricKeys) {
                val tr = TableRow(this).apply {
                    addView(labelCell(EidosAnalystMetricKey.humanizeMetricKey(mk)))
                    if (showTtm) {
                        val f = findAnalystConfirmedFactForDynamicMetric(mk, "TTM")
                        addView(
                            if (f != null) dataCell(f.valueText, analystConfirmed = true)
                            else dataCell(getString(R.string.not_available), highlight = true)
                        )
                    }
                    for (y in yearCols) {
                        val f = findAnalystConfirmedFactForDynamicFiscalYear(mk, y)
                        addView(
                            if (f != null) dataCell(f.valueText, analystConfirmed = true)
                            else dataCell(getString(R.string.not_available), highlight = true)
                        )
                    }
                }
                analystTable.addView(tr)
            }
        }
    }

    private data class QuarterlyHistoryTableData(
        val periodLabels: List<String>,
        val valuesByMetric: Map<Int, List<Double?>>,
        val periodEnds: List<String> = emptyList(),
        val columnContexts: List<HistoryColumnContext> = emptyList(),
    )

    private fun buildQuarterlyTableData(maxColumns: Int = 8): QuarterlyHistoryTableData {
        if (quarterlyFacts.isEmpty()) return QuarterlyHistoryTableData(emptyList(), emptyMap())
        val today = LocalDate.now()
        fun parseDate(value: String?): LocalDate? = try {
            if (value.isNullOrBlank()) null else LocalDate.parse(value)
        } catch (_: Exception) {
            null
        }
        fun quarterFromPeriodEnd(periodEnd: String?): String? {
            val month = periodEnd?.takeIf { it.length >= 7 }?.substring(5, 7)?.toIntOrNull() ?: return null
            return when (month) {
                in 1..3 -> "Q1"
                in 4..6 -> "Q2"
                in 7..9 -> "Q3"
                in 10..12 -> "Q4"
                else -> null
            }
        }
        fun normalizeQuarter(fp: String?, periodEnd: String?): String? {
            val normalized = fp?.uppercase()?.trim()
            if (normalized in setOf("Q1", "Q2", "Q3", "Q4")) return normalized
            return quarterFromPeriodEnd(periodEnd)
        }
        fun fiscalYear(row: QuarterlyFinancialFactEntity): Int? {
            return row.fiscalYear ?: row.periodEnd.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
        }
        data class QuarterKey(val fiscalYear: Int, val fiscalQuarter: String)
        data class QuarterBucket(
            val key: QuarterKey,
            val rowsByMetric: Map<String, QuarterlyFinancialFactEntity>,
            val representativePeriodEnd: String
        )

        val releasedRows = quarterlyFacts.filter { row ->
            val filedDate = parseDate(row.filedDate)
            val periodEnd = parseDate(row.periodEnd)
            when {
                filedDate != null -> !filedDate.isAfter(today)
                periodEnd != null -> !periodEnd.isAfter(today)
                else -> false
            }
        }
        if (releasedRows.isEmpty()) return QuarterlyHistoryTableData(emptyList(), emptyMap())

        val relevantMetrics = mapOf(
            R.string.revenue to "REVENUE",
            R.string.metric_gross_profit to "GROSS_PROFIT",
            R.string.net_income to "NET_INCOME",
            R.string.metric_ebitda to "EBITDA",
            R.string.metric_depreciation_and_amortization to "DEPRECIATION",
            R.string.metric_operating_cash_flow to "OPERATING_CASH_FLOW",
            R.string.metric_capex to "CAPEX",
            R.string.metric_free_cash_flow to "FREE_CASH_FLOW",
            R.string.metric_total_debt to "TOTAL_DEBT",
            R.string.metric_outstanding_shares to "SHARES_OUTSTANDING"
        )

        val buckets = releasedRows
            .groupBy { row ->
                val fy = fiscalYear(row)
                val fq = normalizeQuarter(row.fiscalPeriod, row.periodEnd)
                if (fy != null && fq != null) QuarterKey(fy, fq) else null
            }
            .filterKeys { it != null }
            .mapNotNull { (k, rowsForQuarter) ->
                val key = k ?: return@mapNotNull null
                val byMetric = rowsForQuarter
                    .groupBy { it.metricKey }
                    .mapValues { (_, metricRows) ->
                        metricRows.maxWithOrNull(
                            compareBy<QuarterlyFinancialFactEntity>(
                                { it.filedDate ?: "" },
                                { it.periodEnd }
                            )
                        ) ?: metricRows.first()
                    }
                val representative = rowsForQuarter.maxByOrNull { it.periodEnd }?.periodEnd ?: return@mapNotNull null
                QuarterBucket(
                    key = key,
                    rowsByMetric = byMetric,
                    representativePeriodEnd = representative
                )
            }
            .sortedWith(
                compareByDescending<QuarterBucket> { it.key.fiscalYear }
                    .thenByDescending {
                        when (it.key.fiscalQuarter) {
                            "Q4" -> 4
                            "Q3" -> 3
                            "Q2" -> 2
                            else -> 1
                        }
                    }
            )
            .take(maxColumns)

        val rows = relevantMetrics.mapValues { (_, metricKey) ->
            buckets.map { bucket -> bucket.rowsByMetric[metricKey]?.value }
        }.toMutableMap()

        fun marginSeries(numKey: String, denKey: String): List<Double?> {
            val num = buckets.map { b -> b.rowsByMetric[numKey]?.value }
            val den = buckets.map { b -> b.rowsByMetric[denKey]?.value }
            return num.indices.map { i ->
                val n = num.getOrNull(i)
                val d = den.getOrNull(i)
                if (n != null && d != null && abs(d) > 1e-9) n / d else null
            }
        }
        rows[R.string.metric_history_gross_margin_pct] = marginSeries("GROSS_PROFIT", "REVENUE")
        rows[R.string.metric_history_net_margin_pct] = marginSeries("NET_INCOME", "REVENUE")

        return QuarterlyHistoryTableData(
            periodLabels = buckets.map { "${it.key.fiscalQuarter} ${it.key.fiscalYear}" },
            valuesByMetric = rows,
            periodEnds = buckets.map { it.representativePeriodEnd },
            columnContexts = buckets.map { b ->
                HistoryColumnContext(
                    fiscalYear = b.key.fiscalYear,
                    fiscalPeriod = b.key.fiscalQuarter,
                    periodEnd = b.representativePeriodEnd,
                    accessionHint = representativeQuarterlyAccession(b.rowsByMetric)
                )
            }
        )
    }

    private fun formatQuarterLabel(fp: String?, fy: Int?, periodEnd: String): String {
        val normalizedFp = fp?.uppercase()?.takeIf { it.startsWith("Q") && it.length <= 3 }
        if (normalizedFp != null && fy != null) return "$normalizedFp $fy"
        if (periodEnd.length >= 7) return periodEnd.take(7)
        return periodEnd
    }

    private fun loadQuarterlyFacts() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                database.quarterlyFinancialFactDao().getBySymbol(symbol)
            }
            quarterlyFacts = rows
            if (historyMode == HistoryMode.QUARTERLY && rows.isNotEmpty()) {
                populateFinancialHistoryTable(latestStockData)
            }
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

    private fun parseAnalystNumeric(valueText: String): Double? {
        val t = valueText.trim()
        if (t.isEmpty()) return null
        var s = t.replace(",", "")
        var sign = 1.0
        if (s.startsWith("(") && s.endsWith(")")) {
            sign = -1.0
            s = s.substring(1, s.length - 1)
        }
        s = s.replace("$", "").replace("%", "").trim()
        val multiplier = when {
            s.endsWith("B", ignoreCase = true) -> 1_000_000_000.0
            s.endsWith("M", ignoreCase = true) -> 1_000_000.0
            s.endsWith("K", ignoreCase = true) -> 1_000.0
            else -> 1.0
        }
        if (multiplier != 1.0) s = s.dropLast(1).trim()
        val n = s.toDoubleOrNull() ?: return null
        return sign * n * multiplier
    }

    private fun formatAnalystDisplayForHistoryValue(kind: HistoryValueKind, valueText: String): String {
        val numeric = parseAnalystNumeric(valueText) ?: return valueText
        return when (kind) {
            HistoryValueKind.CURRENCY -> formatAbbreviatedCurrency(numeric)
            HistoryValueKind.SHARES -> formatAbbreviatedShares(numeric)
            HistoryValueKind.PERCENT -> {
                val ratio = if (valueText.contains("%")) numeric / 100.0 else numeric
                formatPercent(ratio)
            }
        }
    }

    private fun formatAnalystDisplayForRawMetric(metricKey: EdgarMetricKey, valueText: String): String {
        val numeric = parseAnalystNumeric(valueText) ?: return valueText
        return when (metricKey) {
            EdgarMetricKey.EPS -> formatNumber(numeric)
            EdgarMetricKey.SHARES_OUTSTANDING -> formatAbbreviatedShares(numeric)
            else -> formatAbbreviatedCurrency(numeric)
        }
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