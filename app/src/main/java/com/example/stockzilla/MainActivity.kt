// MainActivity.kt - Main stock search and analysis screen
package com.example.stockzilla

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import com.example.stockzilla.databinding.ActivityMainBinding
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData

class MainActivity : AppCompatActivity() {

    private val viewModel: StockViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var favoritesAdapter: FavoritesAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupObservers()
        loadFavorites()
    }

    private fun setupUI() {
        // Setup favorites RecyclerView
        favoritesAdapter = FavoritesAdapter(
            onItemClick = { stockData -> showStockDetails(stockData) },
            onAnalyzeClick = { stockData -> analyzeStock(stockData) },
            onRemoveClick = { stockData -> removeFavorite(stockData) }
        )

        binding.rvFavorites.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter
        }

        // Setup search functionality
        binding.etSearch.addTextChangedListener { text ->
            searchJob?.cancel()
            if (!text.isNullOrBlank() && text.length >= 2) {
                searchJob = lifecycleScope.launch {
                    delay(500) // Debounce
                    searchStock(text.toString().uppercase())
                }
            } else {
                clearSearchResult()
            }
        }

        binding.btnAnalyze.setOnClickListener {
            val ticker = binding.etSearch.text.toString().uppercase()
            if (ticker.isNotBlank()) {
                analyzeStock(ticker)
            } else {
                Toast.makeText(this, "Please enter a stock ticker", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnViewFullAnalysis.setOnClickListener {
            viewModel.currentStockData.value?.let { stockData ->
                openFullAnalysisLink(stockData.symbol)
            }
        }

        binding.btnAddToFavorites.setOnClickListener {
            viewModel.currentStockData.value?.let { stockData ->
                addToFavorites(stockData)
            }
        }
    }

    private fun setupObservers() {
        viewModel.currentStockData.observe(this) { stockData ->
            stockData?.let { displayStockData(it) }
        }

        viewModel.healthScore.observe(this) { healthScore ->
            healthScore?.let { displayHealthScore(it) }
        }

        viewModel.favorites.observe(this) { favorites ->
            favoritesAdapter.submitList(favorites)
            binding.tvNoFavorites.visibility = if (favorites.isEmpty()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    private fun searchStock(ticker: String) {
        lifecycleScope.launch {
            viewModel.searchStock(ticker)
        }
    }

    fun analyzeStock(ticker: String) {
        lifecycleScope.launch {
            viewModel.analyzeStock(ticker)
        }
    }

    private fun analyzeStock(stockData: StockData) {
        analyzeStock(stockData.symbol)
    }

    private fun displayStockData(stockData: StockData) {
        binding.apply {
            // Basic info
            tvCompanyName.text = stockData.companyName ?: "Unknown Company"
            tvTicker.text = stockData.symbol
            tvPrice.text = stockData.price?.let { "$%.2f".format(it) } ?: "N/A"
            tvSector.text = stockData.sector ?: "Unknown"

            // Key metrics
            tvMarketCap.text = stockData.marketCap?.let { formatMarketCap(it) } ?: "N/A"
            tvPeRatio.text = stockData.peRatio?.let { "%.2f".format(it) } ?: "N/A"
            tvPsRatio.text = stockData.psRatio?.let { "%.2f".format(it) } ?: "N/A"
            tvRevenue.text = stockData.revenue?.let { formatLargeNumber(it) } ?: "N/A"
            tvNetIncome.text = stockData.netIncome?.let { formatLargeNumber(it) } ?: "N/A"

            // Show action buttons
            layoutActions.visibility = android.view.View.VISIBLE
        }
    }

    private fun displayHealthScore(healthScore: HealthScore) {
        binding.apply {
            tvHealthScore.text = getString(R.string.health_score_format, healthScore.compositeScore)


            // Color code the score
            val color = when (healthScore.compositeScore) {
                in 7..10 -> getColor(R.color.scoreGood)
                in 4..6 -> getColor(R.color.scoreMedium)
                else -> getColor(R.color.scorePoor)
            }
            tvHealthScore.setTextColor(color)

            // Show recommendation
            val recommendation = when (healthScore.compositeScore) {
                in 7..10 -> "Strong Buy - Excellent financial health"
                in 4..6 -> "Hold/Consider - Mixed signals"
                else -> "Caution - Concerning metrics"
            }
            tvRecommendation.text = recommendation

            // Show breakdown
            val breakdown = healthScore.breakdown.joinToString("\n") { metric ->
                "${metric.metric}: ${metric.value?.let { "%.2f".format(it) } ?: "N/A"}"
            }
            tvScoreBreakdown.text = breakdown
        }
    }

    private fun addToFavorites(stockData: StockData) {
        lifecycleScope.launch {
            viewModel.addToFavorites(stockData)
            Toast.makeText(this@MainActivity,
                "${stockData.symbol} added to favorites",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFavorite(stockData: StockData) {
        lifecycleScope.launch {
            viewModel.removeFromFavorites(stockData)
            Toast.makeText(this@MainActivity,
                "${stockData.symbol} removed from favorites",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFullAnalysisLink(ticker: String) {
        val url = "https://finance.yahoo.com/quote/$ticker"
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }

    private fun showStockDetails(stockData: StockData) {
        // Show detailed view in a dialog or bottom sheet
        StockDetailsDialog.show(supportFragmentManager, stockData)
    }

    private fun clearSearchResult() {
        binding.layoutActions.visibility = android.view.View.GONE
        viewModel.clearCurrentStock()
    }

    private fun loadFavorites() {
        lifecycleScope.launch {
            viewModel.loadFavorites()
        }
    }

    private fun formatMarketCap(marketCap: Double): String {
        return when {
            marketCap >= 1_000_000_000_000 -> "$%.2fT".format(marketCap / 1_000_000_000_000)
            marketCap >= 1_000_000_000 -> "$%.2fB".format(marketCap / 1_000_000_000)
            marketCap >= 1_000_000 -> "$%.2fM".format(marketCap / 1_000_000)
            else -> "$%.0f".format(marketCap)
        }
    }

    private fun formatLargeNumber(number: Double): String {
        return when {
            number >= 1_000_000_000 -> "$%.2fB".format(number / 1_000_000_000)
            number >= 1_000_000 -> "$%.2fM".format(number / 1_000_000)
            number >= 1_000 -> "$%.2fK".format(number / 1_000)
            else -> "$%.0f".format(number)
        }
    }
}



class StockViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val stockRepository = StockRepository("YOUR_FMP_API_KEY") // Replace with actual key

    // Initialize database and repository properly
    private val database = StockzillaDatabase.getDatabase(application)
    private val favoritesRepository = FavoritesRepository(database.favoritesDao())
    private val financialAnalyzer = FinancialHealthAnalyzer()

    private val _currentStockData = MutableLiveData<StockData?>()
    val currentStockData: LiveData<StockData?> = _currentStockData

    private val _healthScore = MutableLiveData<HealthScore?>()
    val healthScore: LiveData<HealthScore?> = _healthScore

    private val _favorites = MutableLiveData<List<StockData>>()
    val favorites: LiveData<List<StockData>> = _favorites

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    suspend fun searchStock(ticker: String) {
        _loading.value = true
        stockRepository.getStockData(ticker)
            .onSuccess { stockData ->
                _currentStockData.value = stockData
                _loading.value = false
            }
            .onFailure { exception ->
                _error.value = exception.message
                _loading.value = false
            }
    }

    suspend fun analyzeStock(ticker: String) {
        _loading.value = true
        stockRepository.getStockData(ticker)
            .onSuccess { stockData ->
                _currentStockData.value = stockData
                val score = financialAnalyzer.calculateCompositeScore(stockData)
                _healthScore.value = score
                _loading.value = false
            }
            .onFailure { exception ->
                _error.value = exception.message
                _loading.value = false
            }
    }

    suspend fun addToFavorites(stockData: StockData) {
        favoritesRepository.addFavorite(stockData)
        loadFavorites()
    }

    suspend fun removeFromFavorites(stockData: StockData) {
        favoritesRepository.removeFavorite(stockData.symbol)
        loadFavorites()
    }

    suspend fun loadFavorites() {
        _favorites.value = favoritesRepository.getAllFavorites()
    }

    fun clearCurrentStock() {
        _currentStockData.value = null
        _healthScore.value = null
    }

    fun clearError() {
        _error.value = null
    }
}