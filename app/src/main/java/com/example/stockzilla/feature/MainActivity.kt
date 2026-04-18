package com.example.stockzilla.feature

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.R
import com.example.stockzilla.gov.GovNewsNotificationResend
import com.example.stockzilla.gov.GovNewsWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.stockzilla.stock.StockViewModel
import com.example.stockzilla.ai.AiAssistantActivity
import com.example.stockzilla.ai.AiAssistantViewModel
import com.example.stockzilla.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ANALYZE_SYMBOL = "extra_analyze_symbol"
    }

    private var pendingGovNewsHighlightItemId: Long? = null

    private val requestPostNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Poll may have finished before Allow; replay FLAGGED alerts, then run an on-demand poll.
            lifecycleScope.launch(Dispatchers.IO) {
                GovNewsNotificationResend.resendRecentFlagged(applicationContext)
                GovNewsWorkScheduler.enqueueOneTime(applicationContext)
                GovNewsWorkScheduler.enqueueSummarizeOneTime(applicationContext)
            }
        }
    }

    private val viewModel: StockViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyManager: ApiKeyManager

    /** When set, MainFragment will consume this in onResume and run analyze(symbol). */
    var pendingAnalyzeSymbol: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyManager = ApiKeyManager(this)
        DiagnosticsLogger.init(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.setCurrentItem(1, false) // Start on Main (center page)

        checkApiKeySetup()
        handleAnalyzeIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPostNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        GovNewsWorkScheduler.schedulePeriodic(applicationContext)
        GovNewsWorkScheduler.schedulePeriodicSummarize(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnalyzeIntent(intent)
        handleGovNewsIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_finnhub_settings -> {
                showFinnhubApiKeyDialog()
                true
            }
            R.id.action_ai_chat -> {
                // Open the assistant without binding the conversation to the current stock.
                // This lets the user keep a general or existing chat thread while analyzing stocks separately.
                AiAssistantActivity.Companion.start(this, null)
                true
            }
            R.id.action_ai_assistant -> {
                val currentSymbol = viewModel.resolvedSymbol.value
                val openMode = if (currentSymbol.isNullOrBlank()) {
                    AiAssistantViewModel.OpenMode.FORCE_GENERAL_IF_NO_SYMBOL
                } else {
                    AiAssistantViewModel.OpenMode.LAST_CHAT
                }
                AiAssistantActivity.Companion.start(this, currentSymbol, openMode)
                true
            }
            R.id.action_diagnostic_log -> {
                DiagnosticLogActivity.start(this)
                true
            }
            R.id.action_gov_data_api_keys -> {
                GovDataApiKeysDialog().show(supportFragmentManager, "gov_data_api_keys")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkApiKeySetup() {
        viewModel.updateFinnhubApiKey(apiKeyManager.getFinnhubApiKey())
    }

    private fun showFinnhubApiKeyDialog() {
        FinnhubApiKeySetupDialog { key ->
            viewModel.updateFinnhubApiKey(key)
        }.show(supportFragmentManager, "finnhub_api_key_setup")
    }

    private fun handleAnalyzeIntent(intent: Intent?) {
        val symbol = intent?.getStringExtra(EXTRA_ANALYZE_SYMBOL)?.takeIf { it.isNotBlank() } ?: return
        val formatted = symbol.trim().uppercase(Locale.US)
        pendingAnalyzeSymbol = formatted
        binding.viewPager.setCurrentItem(1, true)
        intent.removeExtra(EXTRA_ANALYZE_SYMBOL)
    }

    private fun handleGovNewsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(GovNewsIntents.EXTRA_MAIN_OPEN_GOV_TAB, false) != true) return
        binding.viewPager.setCurrentItem(3, true)
        val hid = intent.getLongExtra(GovNewsIntents.EXTRA_MAIN_HIGHLIGHT_ITEM_ID, -1L)
        if (hid >= 0L) pendingGovNewsHighlightItemId = hid
        intent.removeExtra(GovNewsIntents.EXTRA_MAIN_OPEN_GOV_TAB)
        intent.removeExtra(GovNewsIntents.EXTRA_MAIN_HIGHLIGHT_ITEM_ID)
    }

    fun consumePendingGovNewsHighlightItemId(): Long? {
        val id = pendingGovNewsHighlightItemId
        pendingGovNewsHighlightItemId = null
        return id
    }

    /** Call from PersonalProfile or ViewedStocks when user taps a stock. Switches to Main and analyzes. */
    fun showMainAndAnalyze(symbol: String) {
        pendingAnalyzeSymbol = symbol.trim().uppercase(Locale.US)
        binding.viewPager.setCurrentItem(1, true)
    }

    /** Called by MainFragment in onResume. Returns and clears any pending symbol. */
    fun consumePendingAnalyzeSymbol(): String? {
        val s = pendingAnalyzeSymbol
        pendingAnalyzeSymbol = null
        return s
    }

    /** Called from StockDetailsDialog when user taps Analyze (may already be on Main tab). */
    fun analyzeStock(symbol: String) {
        viewModel.setRequestAnalyzeSymbol(symbol.trim().uppercase(Locale.US))
    }
}