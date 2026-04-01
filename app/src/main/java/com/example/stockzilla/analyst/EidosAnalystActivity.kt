package com.example.stockzilla.analyst

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.R
import com.example.stockzilla.databinding.ActivityEidosAnalystBinding
import com.example.stockzilla.feature.FullAnalysisActivity
import com.example.stockzilla.scoring.StockData

/**
 * Full Analysis–scoped Eidos analyst chat (filing-grounded metrics). Entry from
 * [com.example.stockzilla.feature.FullAnalysisActivity]; persisted in `eidos_analyst_chat_messages`, not main assistant tables.
 */
class EidosAnalystActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEidosAnalystBinding
    private val viewModel: EidosAnalystViewModel by viewModels()
    /** Shown when Eidos calls [analyst_present_metric_proposal] (numeric value confirmation). */
    private var proposalDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEidosAnalystBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomInputSection) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            var bottom = maxOf(nav, ime)
            if (bottom == 0) {
                val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
                bottom = if (resId > 0) {
                    resources.getDimensionPixelSize(resId)
                } else {
                    (48 * resources.displayMetrics.density).toInt()
                }
            }
            v.updatePadding(bottom = bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        @Suppress("DEPRECATION")
        val stockData = intent.getSerializableExtra(FullAnalysisActivity.Companion.EXTRA_STOCK_DATA) as? StockData
        if (stockData == null) {
            finish()
            return
        }

        val symbol = stockData.symbol.trim().uppercase()
        supportActionBar?.title = getString(R.string.eidos_analyst_title_symbol, symbol)

        viewModel.bindStock(stockData)

        val adapter = EidosAnalystMessageAdapter(
            onDeleteMessage = { viewModel.deleteMessage(it.id) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        viewModel.messages.observe(this) { list ->
            adapter.submitList(list) {
                if (list.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(list.size - 1)
                }
            }
            binding.emptyMessagesText.isVisible = list.isEmpty()
        }

        viewModel.loading.observe(this) { loading ->
            binding.progressBar.isVisible = loading == true
            binding.btnSend.isEnabled = loading != true
            binding.editMessage.isEnabled = loading != true
        }

        viewModel.error.observe(this) { err ->
            binding.errorText.isVisible = !err.isNullOrBlank()
            binding.errorText.text = err.orEmpty()
        }

        viewModel.requiresApiKey.observe(this) { needs ->
            binding.apiKeyHint.isVisible = needs == true
        }

        viewModel.secFilingConsentRequested.observe(this) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sec_filing_extraction_consent_title)
                .setMessage(R.string.sec_filing_extraction_consent_message)
                .setPositiveButton(R.string.sec_filing_extraction_allow) { _, _ ->
                    viewModel.onSecFilingConsentGranted()
                }
                .setNegativeButton(R.string.sec_filing_extraction_not_now, null)
                .show()
        }

        viewModel.proposal.observe(this) { p ->
            proposalDialog?.dismiss()
            proposalDialog = null
            if (p == null) return@observe
            proposalDialog = EidosAnalystProposalSheetUi.show(
                this,
                p,
                onAccept = { selections -> viewModel.acceptProposal(selections) },
                onDecline = { viewModel.declineProposal() },
                onClosed = { viewModel.onProposalDialogClosed() }
            )
        }

        binding.btnSend.setOnClickListener { sendFromInput() }
        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromInput()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshApiKeyState()
    }

    private fun sendFromInput() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        binding.editMessage.setText("")
        binding.editMessage.setSelection(0)
        viewModel.sendMessage(text)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}