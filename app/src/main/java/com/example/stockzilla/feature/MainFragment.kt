package com.example.stockzilla.feature

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.FavoritesAdapter
import com.example.stockzilla.news.AllNewsBottomSheet
import com.example.stockzilla.news.NewsAdapter
import com.example.stockzilla.news.NewsDetailBottomSheet
import com.example.stockzilla.stock.NewsAnalysisState
import com.example.stockzilla.R
import com.example.stockzilla.stock.StockDetailsDialog
import com.example.stockzilla.stock.StockViewModel
import com.example.stockzilla.ai.AiAssistantActivity
import com.example.stockzilla.ai.AiAssistantViewModel
import com.example.stockzilla.databinding.FragmentMainBinding
import com.example.stockzilla.scoring.Benchmark
import com.example.stockzilla.scoring.BenchmarkData
import com.example.stockzilla.scoring.HealthScore
import com.example.stockzilla.scoring.StockData
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels()
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var newsAdapter: NewsAdapter
    private var latestStockData: StockData? = null
    private var latestHealthScore: HealthScore? = null
    private var isLoadingStock: Boolean = false

    private val lastRefreshedFormatter: DateFormat =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupObservers()
        loadFavorites()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.consumePendingAnalyzeSymbol()?.let { symbol ->
            binding.etSearch.setText(symbol)
            binding.etSearch.setSelection(symbol.length)
            analyzeStock(symbol)
        }
    }

    fun analyzeStock(symbol: String) {
        lifecycleScope.launch {
            viewModel.analyzeStock(symbol)
        }
    }

    private fun setupUI() {
        favoritesAdapter = FavoritesAdapter(
            onItemClick = { showStockDetails(it) },
            onAnalyzeClick = { analyzeStock(it.symbol) },
            onRemoveClick = { removeFavorite(it) }
        )
        binding.rvFavorites.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = favoritesAdapter
        }

        newsAdapter = NewsAdapter { summary ->
            NewsDetailBottomSheet.newInstance(summary)
                .show(parentFragmentManager, "NewsDetail")
        }
        binding.rvNews.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = newsAdapter
        }

        binding.btnAnalyzeNews.setOnClickListener {
            val symbol = viewModel.currentStockData.value?.symbol ?: return@setOnClickListener
            viewModel.analyzeRecentNews(symbol)
        }

        binding.tvSeeAllNews.setOnClickListener {
            val symbol = viewModel.currentStockData.value?.symbol ?: return@setOnClickListener
            AllNewsBottomSheet.newInstance(symbol)
                .show(parentFragmentManager, "AllNews")
        }

        binding.etSearch.addTextChangedListener { text ->
            if (text.isNullOrBlank()) clearSearchResult()
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        binding.btnSearchIndustry.apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { openIndustryPeers() }
        }

        binding.btnAnalyze.setOnClickListener {
            val ticker = binding.etSearch.text.toString().uppercase(Locale.US)
            if (ticker.isNotBlank()) analyzeStock(ticker)
            else Toast.makeText(requireContext(), "Please enter a stock ticker", Toast.LENGTH_SHORT).show()
        }

        binding.btnAiAssistant.setOnClickListener {
            val symbol = viewModel.currentStockData.value?.symbol
            val openMode = if (symbol.isNullOrBlank()) {
                AiAssistantViewModel.OpenMode.FORCE_GENERAL_IF_NO_SYMBOL
            } else {
                AiAssistantViewModel.OpenMode.LAST_CHAT
            }
            AiAssistantActivity.Companion.start(requireContext(), symbol, openMode)
        }

        binding.btnViewFullAnalysis.setOnClickListener {
            viewModel.currentStockData.value?.let { openFullAnalysisScreen(it) }
        }
        binding.btnViewOnStockAnalysis.setOnClickListener {
            viewModel.currentStockData.value?.let { openFullAnalysisLink(it.symbol) }
        }
        binding.btnAddToFavorites.setOnClickListener {
            viewModel.currentStockData.value?.let { stockData ->
                addToFavorites(stockData, viewModel.healthScore.value?.compositeScore)
            }
        }
        binding.btnUpdateData.apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                lifecycleScope.launch { viewModel.refreshCurrentStock() }
            }
        }
        binding.btnViewHealthDetails.apply {
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { openHealthScoreDetails() }
        }
        updateRefreshButtonState()
    }

    private fun setupObservers() {
        viewModel.currentStockData.observe(viewLifecycleOwner) { stockData ->
            val previousSymbol = latestStockData?.symbol
            latestStockData = stockData
            if (stockData == null || stockData.symbol != previousSymbol) latestHealthScore = null
            updateHealthDetailsAvailability()
            updateIndustryButtonAvailability(stockData)
            updateRefreshButtonState()
            stockData?.let { displayStockData(it, viewModel.currentBenchmark.value) }
        }
        viewModel.currentBenchmark.observe(viewLifecycleOwner) { benchmark ->
            latestStockData?.let { displayStockData(it, benchmark) }
        }
        viewModel.healthScore.observe(viewLifecycleOwner) { healthScore ->
            latestHealthScore = healthScore
            updateHealthDetailsAvailability()
            healthScore?.let { displayHealthScore(it) }
        }
        viewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            favoritesAdapter.submitList(favorites)
            binding.tvNoFavorites.isVisible = favorites.isEmpty()
        }
        viewModel.isFavorited.observe(viewLifecycleOwner) { updateFavoriteButton(it) }
        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            isLoadingStock = isLoading
            updateRefreshButtonState()
            binding.progressBar.isVisible = isLoading
        }
        viewModel.lastRefreshed.observe(viewLifecycleOwner) { updateLastRefreshed(it) }
        viewModel.resolvedSymbol.observe(viewLifecycleOwner) { symbol ->
            symbol?.let {
                val currentText = binding.etSearch.text?.toString()
                if (currentText != it) {
                    binding.etSearch.setText(it)
                    binding.etSearch.setSelection(it.length)
                }
            }
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                if (it.contains("401") || it.contains("403") || it.contains("Unauthorized") || it.contains("Forbidden")) {
                    Toast.makeText(requireContext(), "API key issue (e.g. expired or invalid) – update your key via the menu", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Error: $it", Toast.LENGTH_LONG).show()
                }
                viewModel.clearError()
            }
        }
        viewModel.requestAnalyzeSymbol.observe(viewLifecycleOwner) { symbol ->
            symbol?.let {
                binding.etSearch.setText(it)
                binding.etSearch.setSelection(it.length)
                analyzeStock(it)
                viewModel.clearRequestAnalyzeSymbol()
            }
        }

        viewModel.recentNews.observe(viewLifecycleOwner) { newsList ->
            if (newsList.isNullOrEmpty()) {
                binding.layoutNews.visibility = View.GONE
            } else {
                newsAdapter.submitList(newsList)
                binding.layoutNews.visibility = View.VISIBLE
            }
        }

        viewModel.newsAnalysisState.observe(viewLifecycleOwner) { state ->
            when (state) {
                NewsAnalysisState.LOADING -> {
                    binding.btnAnalyzeNews.isEnabled = false
                    binding.btnAnalyzeNews.text = getString(R.string.analyzing_news)
                }
                NewsAnalysisState.DONE, NewsAnalysisState.ERROR, NewsAnalysisState.IDLE -> {
                    binding.btnAnalyzeNews.isEnabled = true
                    updateAnalyzeNewsButtonText()
                }
                null -> { /* no-op */ }
            }
        }

        viewModel.newFilingsCount.observe(viewLifecycleOwner) {
            if (viewModel.newsAnalysisState.value != NewsAnalysisState.LOADING) {
                updateAnalyzeNewsButtonText()
            }
        }
    }

    private fun updateAnalyzeNewsButtonText() {
        val count = viewModel.newFilingsCount.value ?: 0
        binding.btnAnalyzeNews.text = if (count > 0) {
            getString(R.string.analyze_news_new, count)
        } else {
            getString(R.string.analyze_news)
        }
    }

    private fun updateRefreshButtonState() {
        val hasStock = latestStockData != null
        val enabled = hasStock && !isLoadingStock
        binding.btnUpdateData.isEnabled = enabled
        binding.btnUpdateData.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateLastRefreshed(timestamp: Long?) {
        if (timestamp == null) {
            binding.tvLastRefreshed.visibility = View.GONE
        } else {
            binding.tvLastRefreshed.visibility = View.VISIBLE
            binding.tvLastRefreshed.text = getString(
                R.string.last_refreshed_format, lastRefreshedFormatter.format(
                    Date(timestamp)
                ))
        }
    }

    private fun updateFavoriteButton(isFavorited: Boolean) {
        binding.btnAddToFavorites.text = if (isFavorited) "Update Favorite" else "Add to Favorites"
    }

    private fun performSearch() {
        val ticker = binding.etSearch.text?.toString()?.trim()?.uppercase().orEmpty()
        if (ticker.isNotBlank()) {
            lifecycleScope.launch { viewModel.searchStock(ticker) }
        } else {
            Toast.makeText(requireContext(), "Please enter a stock ticker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayStockData(stockData: StockData, benchmark: Benchmark? = null) {
        binding.apply {
            tvCompanyName.text = stockData.companyName ?: "Unknown Company"
            tvTicker.text = stockData.symbol
            tvPrice.text = stockData.price?.let { "$%.2f".format(it) } ?: getString(R.string.not_available)
            tvSector.text = stockData.sector ?: "Unknown"

            val displayMetrics = BenchmarkData.getDisplayMetrics(stockData, benchmark)
            tvMarketCap.text = stockData.marketCap?.let { formatMarketCap(it) } ?: "N/A"
            tvPeRatioLabel.text = displayMetrics.primaryLabel
            tvPeRatio.text = displayMetrics.primaryRatio?.let { "%.2f".format(it) } ?: "N/A"
            tvPsRatioLabel.text = displayMetrics.benchmarkLabel
            tvPsRatio.text = displayMetrics.benchmarkRatio?.let { "%.2f".format(it) } ?: "N/A"

            val fairValueResult = BenchmarkData.calculateFairValue(stockData, benchmark)
            if (fairValueResult != null) {
                tvFairValuePrice.text = getString(R.string.fair_value_price_short, "%.2f".format(fairValueResult.impliedPrice))
                tvFairValueMethod.text = when (fairValueResult.multipleLabel) {
                    "P/S Ratio" -> getString(R.string.via_avg_ps)
                    else -> getString(R.string.via_avg_pe)
                }
            } else {
                tvFairValuePrice.text = getString(R.string.fair_value_unavailable)
                tvFairValueMethod.text = ""
            }
            tvFairValuePrice.setOnClickListener { openHealthScoreDetails() }

            val ttmSuffix = if (stockData.hasTtm) " " + getString(R.string.label_ttm) else " " + getString(
                R.string.label_annual)
            tvRevenueLabel.text = getString(R.string.revenue) + ttmSuffix
            tvRevenue.text = stockData.revenueDisplay?.let { formatLargeNumber(it) } ?: "N/A"
            tvNetIncomeLabel.text = getString(R.string.net_income) + ttmSuffix
            tvNetIncome.text = stockData.netIncomeDisplay?.let { formatLargeNumber(it) } ?: "N/A"
            tvFreeCashFlow.text = stockData.freeCashFlowDisplay?.let { formatLargeNumber(it) } ?: getString(
                R.string.placeholder_dash)
            tvRevenueGrowth.text = stockData.revenueGrowth?.let { "%.1f%%".format(it * 100) } ?: getString(
                R.string.placeholder_dash)

            layoutActions.visibility = View.VISIBLE
            cardStockInfo.visibility = View.VISIBLE
            btnAnalyzeNews.visibility = View.VISIBLE
        }
    }

    private fun displayHealthScore(healthScore: HealthScore) {
        binding.apply {
            tvHealthScore.text = getString(R.string.health_score_format, healthScore.compositeScore)
            val compositeColor = getScoreColor(healthScore.compositeScore)
            tvHealthScore.setTextColor(compositeColor)
            tvCompositeLabel.setTextColor(compositeColor)
            tvHealthScoreTile.text = getString(R.string.health_score_format, healthScore.compositeScore)
            tvRecommendation.text = when (healthScore.compositeScore) {
                in 7..10 -> "Strong Buy - Excellent financial health"
                in 4..6 -> "Hold/Consider - Mixed signals"
                else -> "Caution - Concerning metrics"
            }
        }
    }

    private fun getScoreColor(score: Int): Int = when (score) {
        in 7..10 -> requireContext().getColor(R.color.scoreGood)
        in 4..6 -> requireContext().getColor(R.color.scoreMedium)
        else -> requireContext().getColor(R.color.scorePoor)
    }

    private fun formatMarketCap(marketCap: Double): String = when {
        marketCap >= 1_000_000_000_000 -> "$%.2fT".format(marketCap / 1_000_000_000_000)
        marketCap >= 1_000_000_000 -> "$%.2fB".format(marketCap / 1_000_000_000)
        marketCap >= 1_000_000 -> "$%.2fM".format(marketCap / 1_000_000)
        else -> "$%.0f".format(marketCap)
    }

    private fun formatLargeNumber(number: Double): String {
        val sign = if (number < 0) "-" else ""
        val absNumber = abs(number)
        return when {
            absNumber >= 1_000_000_000 -> "$sign${"%.2f".format(absNumber / 1_000_000_000)}B"
            absNumber >= 1_000_000 -> "$sign${"%.2f".format(absNumber / 1_000_000)}M"
            absNumber >= 1_000 -> "$sign${"%.2f".format(absNumber / 1_000)}K"
            else -> "$sign${"%.0f".format(absNumber)}"
        }
    }

    private fun addToFavorites(stockData: StockData, healthScore: Int?) {
        lifecycleScope.launch {
            if (viewModel.isFavorited.value == true) {
                viewModel.updateFavorite(stockData, healthScore)
                Toast.makeText(requireContext(), "${stockData.symbol} favorite updated with latest data", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addToFavorites(stockData)
                Toast.makeText(requireContext(), "${stockData.symbol} added to favorites", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFavorite(stockData: StockData) {
        lifecycleScope.launch {
            viewModel.removeFromFavorites(stockData)
            Toast.makeText(requireContext(), "${stockData.symbol} removed from favorites", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFullAnalysisScreen(stockData: StockData) {
        startActivity(Intent(requireContext(), FullAnalysisActivity::class.java).apply {
            putExtra(FullAnalysisActivity.EXTRA_STOCK_DATA, stockData)
        })
    }

    private fun openFullAnalysisLink(ticker: String) {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://stockanalysis.com/stocks/${ticker.lowercase()}/".toUri()
            )
        )
    }

    private fun showStockDetails(stockData: StockData) {
        StockDetailsDialog.Companion.show(parentFragmentManager, stockData)
    }

    private fun clearSearchResult() {
        binding.layoutActions.visibility = View.GONE
        binding.cardStockInfo.visibility = View.GONE
        binding.btnAnalyzeNews.visibility = View.GONE
        binding.layoutNews.visibility = View.GONE
        viewModel.clearCurrentStock()
        latestStockData = null
        latestHealthScore = null
        updateHealthDetailsAvailability()
        updateIndustryButtonAvailability(null)
        updateRefreshButtonState()
    }

    private fun loadFavorites() {
        lifecycleScope.launch { viewModel.loadFavorites() }
    }

    private fun updateHealthDetailsAvailability() {
        val stockData = latestStockData ?: viewModel.currentStockData.value
        val healthScore = latestHealthScore ?: viewModel.healthScore.value
        val isReady = stockData != null && healthScore != null
        binding.btnViewHealthDetails.isEnabled = isReady
        binding.btnViewHealthDetails.alpha = if (isReady) 1f else 0.5f
    }

    private fun updateIndustryButtonAvailability(stockData: StockData?) {
        val hasStock = stockData != null
        binding.btnSearchIndustry.isEnabled = hasStock
        binding.btnSearchIndustry.alpha = if (hasStock) 1f else 0.5f
    }

    private fun openIndustryPeers() {
        val stockData = latestStockData ?: viewModel.currentStockData.value
        if (stockData == null) {
            Toast.makeText(requireContext(), getString(R.string.industry_missing_message), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(requireContext(), IndustryStocksActivity::class.java).apply {
            putExtra(IndustryStocksActivity.EXTRA_INDUSTRY, stockData.industry.orEmpty())
            putExtra(IndustryStocksActivity.EXTRA_SYMBOL, stockData.symbol)
            putExtra(IndustryStocksActivity.EXTRA_COMPANY_NAME, stockData.companyName ?: stockData.symbol)
        })
    }

    private fun openHealthScoreDetails() {
        val stockData = latestStockData ?: viewModel.currentStockData.value
        val healthScore = latestHealthScore ?: viewModel.healthScore.value
        if (stockData != null && healthScore != null) {
            startActivity(Intent(requireContext(), HealthScoreDetailsActivity::class.java).apply {
                putExtra(HealthScoreDetailsActivity.EXTRA_STOCK_DATA, stockData)
                putExtra(HealthScoreDetailsActivity.EXTRA_HEALTH_SCORE, healthScore)
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}