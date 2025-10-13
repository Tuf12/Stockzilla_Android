// MainActivity.kt - Updated to handle API key setup
package com.example.stockzilla

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.databinding.ActivityMainBinding
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: StockViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var apiKeyManager: ApiKeyManager
    private var latestStockData: StockData? = null
    private var latestHealthScore: HealthScore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyManager = ApiKeyManager(this)

        // MainActivity.onCreate()
        supportActionBar?.hide()

        setupUI()
        setupObservers()
        loadFavorites()

        // Check if we need to setup API key
        checkApiKeySetup()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_api_settings -> {
                showApiKeyDialog(forceShow = true)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun checkApiKeySetup() {
        if (!apiKeyManager.hasApiKey() || !apiKeyManager.isApiKeyValidated()) {
            showApiKeyDialog()
        } else {
            // Initialize ViewModel with saved API key
            viewModel.updateApiKey(apiKeyManager.getApiKey()!!)
        }
    }

    private fun showApiKeyDialog(forceShow: Boolean = false) {
        if (forceShow || !apiKeyManager.hasApiKey()) {
            val dialog = ApiKeySetupDialog { apiKey ->
                viewModel.updateApiKey(apiKey)
                if (apiKey != "demo") {
                    Toast.makeText(this, "API key configured successfully!", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        this,
                        "Using demo mode - limited functionality",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            dialog.show(supportFragmentManager, "api_key_setup")
        }
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
            if (text.isNullOrBlank()) {
                clearSearchResult()
                clearSearchResult()
            }
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.btnAnalyze.setOnClickListener {
            val ticker = binding.etSearch.text.toString().uppercase()
            if (ticker.isNotBlank()) {
                if (apiKeyManager.hasApiKey()) {
                    analyzeStock(ticker)
                } else {
                    showApiKeyDialog()
                }
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
                addToFavorites(stockData, healthScore = null)
            }
        }

        binding.btnAddToFavorites.setOnClickListener {
            viewModel.currentStockData.value?.let { stockData ->
                val healthScore = viewModel.healthScore.value?.compositeScore
                addToFavorites(stockData, healthScore)  // Update this call
            }
        }
        binding.btnViewHealthDetails.apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { openHealthScoreDetails() }
        }
    }


    private fun setupObservers() {
        viewModel.currentStockData.observe(this) { stockData ->
            val previousSymbol = latestStockData?.symbol
            latestStockData = stockData
            if (stockData == null || stockData.symbol != previousSymbol) {
                latestHealthScore = null
            }
            updateHealthDetailsAvailability()
            stockData?.let { displayStockData(it) }
        }

        viewModel.healthScore.observe(this) { healthScore ->
            latestHealthScore = healthScore
            updateHealthDetailsAvailability()
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

        // Add this new observer in setupObservers()
        viewModel.isFavorited.observe(this) { isFavorited ->
            updateFavoriteButton(isFavorited)
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
                if (it.contains("401") || it.contains("Unauthorized")) {
                    Toast.makeText(this, "API key issue - please check your key", Toast.LENGTH_LONG)
                        .show()
                    showApiKeyDialog(forceShow = true)
                } else {
                    Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show()
                }
                viewModel.clearError()
            }
        }
    }

    private fun updateFavoriteButton(isFavorited: Boolean) {
        binding.btnAddToFavorites.text = if (isFavorited) {
            "Update Favorite"
        } else {
            "Add to Favorites"
        }
    }

    private fun searchStock(ticker: String) {
        if (!apiKeyManager.hasApiKey()) {
            showApiKeyDialog()
            return
        }

        lifecycleScope.launch {
            viewModel.searchStock(ticker)
        }
    }

    private fun performSearch() {
        val ticker = binding.etSearch.text?.toString()?.trim()?.uppercase().orEmpty()
        if (ticker.isNotBlank()) {
            searchStock(ticker)
        } else {
            Toast.makeText(this, "Please enter a stock ticker", Toast.LENGTH_SHORT).show()
        }
    }


    fun analyzeStock(ticker: String) {
        if (!apiKeyManager.hasApiKey()) {
            showApiKeyDialog()
            return
        }

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

            // Get smart display metrics based on net income
            val displayMetrics = BenchmarkData.getDisplayMetrics(stockData)

            // Key metrics with smart PE/PS display
            tvMarketCap.text = stockData.marketCap?.let { formatMarketCap(it) } ?: "N/A"

            // Update first ratio and its label (PE for positive income, PS for negative income)
            tvPeRatioLabel.text = displayMetrics.primaryLabel
            tvPeRatio.text = displayMetrics.primaryRatio?.let { "%.2f".format(it) } ?: "N/A"

            // Update second position with benchmark average and its label
            tvPsRatioLabel.text = displayMetrics.benchmarkLabel
            tvPsRatio.text = displayMetrics.benchmarkRatio?.let { "%.2f".format(it) } ?: "N/A"

            tvRevenue.text = stockData.revenue?.let { formatLargeNumber(it) } ?: "N/A"
            tvNetIncome.text = stockData.netIncome?.let { formatLargeNumber(it) } ?: "N/A"

            // Show action buttons
            layoutActions.visibility = android.view.View.VISIBLE
            cardStockInfo.visibility = android.view.View.VISIBLE
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
            tvHealthScoreSmall.setTextColor(color)
            tvHealthScoreSmall.text =
                getString(R.string.health_score_format, healthScore.compositeScore)

            // Show recommendation
            val recommendation = when (healthScore.compositeScore) {
                in 7..10 -> "Strong Buy - Excellent financial health"
                in 4..6 -> "Hold/Consider - Mixed signals"
                else -> "Caution - Concerning metrics"
            }
            tvRecommendation.text = recommendation

        }
    }

    private fun addToFavorites(stockData: StockData, healthScore: Int?) {
        lifecycleScope.launch {
            val isFavorited = viewModel.isFavorited.value == true

            if (isFavorited) {
                // Update existing favorite
                viewModel.updateFavorite(stockData, healthScore)
                Toast.makeText(
                    this@MainActivity,
                    "${stockData.symbol} favorite updated with latest data",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                // Add new favorite
                viewModel.addToFavorites(stockData)
                Toast.makeText(
                    this@MainActivity,
                    "${stockData.symbol} added to favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun removeFavorite(stockData: StockData) {
        lifecycleScope.launch {
            viewModel.removeFromFavorites(stockData)
            Toast.makeText(
                this@MainActivity,
                "${stockData.symbol} removed from favorites",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openFullAnalysisLink(ticker: String) {
        val url = "https://stockanalysis.com/stocks/${ticker.lowercase()}/"
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }


    private fun showStockDetails(stockData: StockData) {
        StockDetailsDialog.show(supportFragmentManager, stockData)
    }

    private fun clearSearchResult() {
        binding.layoutActions.visibility = android.view.View.GONE
        binding.cardStockInfo.visibility = android.view.View.GONE
        viewModel.clearCurrentStock()
        latestStockData = null
        latestHealthScore = null
        updateHealthDetailsAvailability()
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

    private fun updateHealthDetailsAvailability() {
        val stockData = latestStockData ?: viewModel.currentStockData.value
        val healthScore = latestHealthScore ?: viewModel.healthScore.value
        val isReady = stockData != null && healthScore != null
        binding.btnViewHealthDetails.isEnabled = isReady
        binding.btnViewHealthDetails.alpha = if (isReady) 1f else 0.5f
    }

    private fun openHealthScoreDetails() {
        val stockData = latestStockData ?: viewModel.currentStockData.value
        val healthScore = latestHealthScore ?: viewModel.healthScore.value

        if (stockData != null && healthScore != null) {
            val intent = Intent(this, HealthScoreDetailsActivity::class.java).apply {
                putExtra(HealthScoreDetailsActivity.EXTRA_STOCK_DATA, stockData)
                putExtra(HealthScoreDetailsActivity.EXTRA_HEALTH_SCORE, healthScore)
            }
            startActivity(intent)
        }
    }

    private fun formatLargeNumber(number: Double): String {
        val sign = if (number < 0) "-" else ""
        val absNumber = kotlin.math.abs(number)

        return when {
            absNumber >= 1_000_000_000 -> "$sign${"%.2f".format(absNumber / 1_000_000_000)}B"
            absNumber >= 1_000_000 -> "$sign${"%.2f".format(absNumber / 1_000_000)}M"
            absNumber >= 1_000 -> "$sign${"%.2f".format(absNumber / 1_000)}K"
            else -> "$sign${"%.0f".format(absNumber)}"
        }
    }

}


class StockViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private var currentApiKey: String = ApiConstants.DEFAULT_DEMO_KEY
    private var stockRepository = StockRepository(currentApiKey)

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

    // LiveData property
    private val _isFavorited = MutableLiveData<Boolean>()
    val isFavorited: LiveData<Boolean> = _isFavorited



    fun updateApiKey(apiKey: String) {
        currentApiKey = apiKey
        stockRepository = StockRepository(apiKey)
    }

    suspend fun searchStock(ticker: String) {
        _loading.value = true
        stockRepository.getStockData(ticker)
            .onSuccess { stockData ->
                _currentStockData.value = stockData
                checkIfFavorited(ticker)  // Add this line
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
                checkIfFavorited(ticker)

                // Auto-update favorite if it exists
                if (favoritesRepository.isFavorite(ticker)) {
                    favoritesRepository.updateFavoriteData(stockData, score.compositeScore)
                    loadFavorites() // Refresh favorites list
                }

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

        // Update isFavorited if this is the current stock
        if (_currentStockData.value?.symbol == stockData.symbol) {
            _isFavorited.value = false
        }

        loadFavorites()
    }


    suspend fun loadFavorites() {
        _favorites.value = favoritesRepository.getAllFavorites()
    }

    suspend fun updateFavorite(stockData: StockData, healthScore: Int? = null) {
        favoritesRepository.updateFavoriteData(stockData, healthScore)
        loadFavorites()
    }

    private suspend fun checkIfFavorited(symbol: String) {
        _isFavorited.value = favoritesRepository.isFavorite(symbol)
    }
    fun clearCurrentStock() {
        _currentStockData.value = null
        _healthScore.value = null
        _isFavorited.value = false  // Add this line
    }

    fun clearError() {
        _error.value = null
    }


}