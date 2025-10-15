package com.example.stockzilla

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.databinding.ActivityIndustryStocksBinding
import kotlinx.coroutines.launch

class IndustryStocksActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDUSTRY = "extra_industry"
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_COMPANY_NAME = "extra_company_name"
    }

    private lateinit var binding: ActivityIndustryStocksBinding
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var adapter: IndustryStocksAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIndustryStocksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        apiKeyManager = ApiKeyManager(this)

        val industry = intent.getStringExtra(EXTRA_INDUSTRY)
        val currentSymbol = intent.getStringExtra(EXTRA_SYMBOL)
        val companyName = intent.getStringExtra(EXTRA_COMPANY_NAME)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.industry_peers_title)

        binding.tvIndustrySubtitle.text = industry?.let {
            getString(R.string.industry_peers_subtitle, it)
        } ?: ""

        binding.tvCurrentStock.isVisible = !companyName.isNullOrBlank()
        if (!companyName.isNullOrBlank()) {
            val label = if (currentSymbol.isNullOrBlank()) {
                companyName
            } else {
                "$companyName ($currentSymbol)"
            }
            binding.tvCurrentStock.text = getString(R.string.industry_current_stock, label)
        }

        adapter = IndustryStocksAdapter(currentSymbol)
        binding.recyclerViewPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPeers.adapter = adapter

        if (industry.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.industry_not_available_message), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.setup_api_key), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        loadIndustryPeers(industry, apiKey)
    }

    private fun loadIndustryPeers(industry: String, apiKey: String) {
        binding.progressBar.isVisible = true
        binding.recyclerViewPeers.isVisible = false
        binding.tvEmptyState.isVisible = false
        binding.tvEmptyState.text = getString(R.string.industry_list_empty)

        val repository = StockRepository(apiKey)
        lifecycleScope.launch {
            repository.getIndustryPeers(industry)
                .onSuccess { peers ->
                    val sortedPeers = peers.sortedWith(
                        compareByDescending<IndustryPeer> { it.marketCap ?: Double.MIN_VALUE }
                            .thenBy { it.symbol }
                    )
                    adapter.submitList(sortedPeers)
                    binding.recyclerViewPeers.isVisible = sortedPeers.isNotEmpty()
                    binding.tvEmptyState.isVisible = sortedPeers.isEmpty()
                }
                .onFailure {
                    Toast.makeText(
                        this@IndustryStocksActivity,
                        getString(R.string.industry_list_error),
                        Toast.LENGTH_LONG
                    ).show()
                    binding.recyclerViewPeers.isVisible = false
                    binding.tvEmptyState.text = getString(R.string.industry_list_error)
                    binding.tvEmptyState.isVisible = true
                }

            binding.progressBar.isVisible = false
        }
    }
}
