package com.example.stockzilla.feature

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.R
import com.example.stockzilla.data.StockRepository
import com.example.stockzilla.databinding.ActivityAddPeerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AddPeerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OWNER_SYMBOL = "extra_owner_symbol"
        const val EXTRA_SELECTED_SYMBOL = "extra_selected_symbol"
    }

    private lateinit var binding: ActivityAddPeerBinding
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPeerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val apiKey = ApiKeyManager(this).getFinnhubApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.setup_finnhub_api_key), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val repository = StockRepository(apiKey, apiKey)
        val adapter = AddPeerAdapter { item ->
            val symbol = item.symbol?.trim()?.uppercase() ?: return@AddPeerAdapter
            setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_SYMBOL, symbol))
            finish()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(binding.etSearch.text?.toString(), repository, adapter)
                true
            } else false
        }

        binding.btnSearch.setOnClickListener {
            doSearch(binding.etSearch.text?.toString(), repository, adapter)
        }
    }

    private fun doSearch(query: String?, repository: StockRepository, adapter: AddPeerAdapter) {
        val q = query?.trim().orEmpty()
        if (q.length < 2) {
            Toast.makeText(this, getString(R.string.add_peer_empty), Toast.LENGTH_SHORT).show()
            return
        }
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            binding.progressBar.isVisible = true
            binding.tvEmpty.isVisible = false
            binding.recyclerView.isVisible = false
            delay(150)
            repository.searchSymbols(q)
                .onSuccess { list ->
                    adapter.submitList(list)
                    binding.recyclerView.isVisible = list.isNotEmpty()
                    binding.tvEmpty.isVisible = list.isEmpty()
                    binding.tvEmpty.text = if (list.isEmpty()) getString(R.string.add_peer_empty) else null
                }
                .onFailure {
                    Toast.makeText(this@AddPeerActivity, it.message ?: "Search failed", Toast.LENGTH_SHORT).show()
                    binding.tvEmpty.isVisible = true
                    binding.recyclerView.isVisible = false
                }
            binding.progressBar.isVisible = false
        }
    }
}