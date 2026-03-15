// MainActivity.kt - Hosts ViewPager2 (Profile | Main | Viewed) and toolbar
package com.example.stockzilla

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.stockzilla.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ANALYZE_SYMBOL = "extra_analyze_symbol"
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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.setCurrentItem(1, false) // Start on Main (center page)

        checkApiKeySetup()
        handleAnalyzeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnalyzeIntent(intent)
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
                AiAssistantActivity.start(this, null)
                true
            }
            R.id.action_ai_assistant -> {
                val currentSymbol = viewModel.resolvedSymbol.value
                AiAssistantActivity.start(this, currentSymbol)
                true
            }
            R.id.action_diagnostic_log -> {
                DiagnosticLogActivity.start(this)
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
