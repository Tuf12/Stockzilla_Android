package com.example.stockzilla

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.databinding.ActivityIndustryStocksBinding
import kotlinx.coroutines.launch
import java.util.Locale

class IndustryStocksActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDUSTRY = "extra_industry"
        const val EXTRA_SYMBOL = "extra_symbol"
        const val EXTRA_COMPANY_NAME = "extra_company_name"
    }

    private lateinit var binding: ActivityIndustryStocksBinding
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var adapter: IndustryStocksAdapter
    private lateinit var peerRepository: IndustryPeerRepository
    private var ownerSymbol: String = ""
    private var industry: String = ""

    private val addPeerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val symbol = result.data?.getStringExtra(AddPeerActivity.EXTRA_SELECTED_SYMBOL)
            if (!symbol.isNullOrBlank()) {
                lifecycleScope.launch {
                    peerRepository.addPeer(ownerSymbol, symbol.trim().uppercase(Locale.US), "user_added")
                    refreshPeerList()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIndustryStocksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        apiKeyManager = ApiKeyManager(this)
        val db = StockzillaDatabase.getDatabase(this)
        peerRepository = IndustryPeerRepository(db.stockIndustryPeerDao(), db.analyzedStockDao())

        industry = intent.getStringExtra(EXTRA_INDUSTRY).orEmpty()
        ownerSymbol = intent.getStringExtra(EXTRA_SYMBOL)?.trim()?.uppercase(Locale.US).orEmpty()
        val companyName = intent.getStringExtra(EXTRA_COMPANY_NAME)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.industry_peers_title)

        binding.tvIndustrySubtitle.text = if (industry.isNotBlank()) {
            getString(R.string.industry_peers_subtitle, industry)
        } else {
            getString(R.string.industry_peers_subtitle_no_industry)
        }

        binding.tvCurrentStock.isVisible = !companyName.isNullOrBlank()
        if (!companyName.isNullOrBlank()) {
            val label = if (ownerSymbol.isBlank()) companyName else "$companyName ($ownerSymbol)"
            binding.tvCurrentStock.text = getString(R.string.industry_current_stock, label)
        }

        adapter = IndustryStocksAdapter(
            currentSymbol = ownerSymbol,
            onStockClick = { peer -> openStockAnalysis(peer.symbol) },
            onRemove = { peer ->
                lifecycleScope.launch {
                    peerRepository.removePeer(ownerSymbol, peer.symbol)
                    refreshPeerList()
                }
            }
        )
        binding.recyclerViewPeers.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPeers.adapter = adapter

        binding.btnAddStock.setOnClickListener {
            val intent = Intent(this, AddPeerActivity::class.java).apply {
                putExtra(AddPeerActivity.EXTRA_OWNER_SYMBOL, ownerSymbol)
            }
            addPeerLauncher.launch(intent)
        }

        binding.btnAddBySymbol.setOnClickListener { showAddBySymbolDialog() }

        val finnhubKey = apiKeyManager.getFinnhubApiKey()
        if (finnhubKey.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.industry_no_key_can_add_by_symbol), Toast.LENGTH_LONG).show()
        }
        loadIndustryPeers(industry, ownerSymbol, finnhubKey)
    }

    private fun openStockAnalysis(symbol: String?) {
        if (symbol.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.industry_symbol_not_available), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ANALYZE_SYMBOL, symbol.uppercase(Locale.US))
        })
    }

    private fun loadIndustryPeers(industry: String, symbol: String, finnhubKey: String?) {
        binding.progressBar.isVisible = true
        binding.recyclerViewPeers.isVisible = false
        binding.tvEmptyState.isVisible = false
        binding.tvEmptyState.text = getString(R.string.industry_list_empty)

        lifecycleScope.launch {
            val hasSaved = peerRepository.hasSavedPeers(symbol)
            if (hasSaved) {
                val peers = peerRepository.getSavedPeers(symbol)
                val sorted = peers.sortedWith(
                    compareByDescending<IndustryPeer> { it.marketCap ?: Double.MIN_VALUE }
                        .thenBy { it.symbol }
                )
                adapter.submitList(sorted)
                binding.recyclerViewPeers.isVisible = true
                binding.tvEmptyState.isVisible = sorted.isEmpty()
            } else if (!finnhubKey.isNullOrBlank()) {
                val repository = StockRepository(finnhubKey, finnhubKey, StockzillaDatabase.getDatabase(this@IndustryStocksActivity).analyzedStockDao())
                repository.getIndustryPeers(symbol, industry)
                    .onSuccess { peers ->
                        peerRepository.replacePeers(symbol, peers, "initial")
                        val sorted = peers.sortedWith(
                            compareByDescending<IndustryPeer> { it.marketCap ?: Double.MIN_VALUE }
                                .thenBy { it.symbol }
                        )
                        adapter.submitList(sorted)
                        binding.recyclerViewPeers.isVisible = true
                        binding.tvEmptyState.isVisible = sorted.isEmpty()
                    }
                    .onFailure {
                        Toast.makeText(
                            this@IndustryStocksActivity,
                            getString(R.string.industry_list_error),
                            Toast.LENGTH_LONG
                        ).show()
                        binding.tvEmptyState.text = getString(R.string.industry_list_error)
                        binding.tvEmptyState.isVisible = true
                    }
            } else {
                adapter.submitList(emptyList())
                binding.tvEmptyState.isVisible = true
            }
            binding.progressBar.isVisible = false
        }
    }

    private fun showAddBySymbolDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.industry_add_symbol_hint)
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.industry_add_symbol_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.industry_add_symbol_btn) { _, _ ->
                val raw = input.text?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
                if (raw.isEmpty()) {
                    Toast.makeText(this, getString(R.string.industry_add_symbol_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (raw == ownerSymbol) {
                    Toast.makeText(this, getString(R.string.industry_add_symbol_same), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    peerRepository.addPeer(ownerSymbol, raw, "manual")
                    refreshPeerList()
                    Toast.makeText(this@IndustryStocksActivity, getString(R.string.industry_added, raw), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun refreshPeerList() {
        val peers = peerRepository.getSavedPeers(ownerSymbol)
        val sorted = peers.sortedWith(
            compareByDescending<IndustryPeer> { it.marketCap ?: Double.MIN_VALUE }
                .thenBy { it.symbol }
        )
        adapter.submitList(sorted)
        binding.recyclerViewPeers.isVisible = sorted.isNotEmpty()
        binding.tvEmptyState.isVisible = sorted.isEmpty()
    }
}
