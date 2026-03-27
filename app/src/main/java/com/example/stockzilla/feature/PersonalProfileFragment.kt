package com.example.stockzilla.feature

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.R
import com.example.stockzilla.data.StockRepository
import com.example.stockzilla.data.FinancialDerivedMetricsDao
import com.example.stockzilla.data.FinancialDerivedMetricsEntity
import com.example.stockzilla.data.PortfolioCashFlowDao
import com.example.stockzilla.data.PortfolioCashFlowEntity
import com.example.stockzilla.data.PortfolioValueSnapshotDao
import com.example.stockzilla.data.PortfolioValueSnapshotEntity
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.data.UserStockListDao
import com.example.stockzilla.data.UserStockListItemEntity
import com.example.stockzilla.databinding.FragmentPersonalProfileBinding
import com.example.stockzilla.util.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class PersonalProfileFragment : Fragment() {

    private var _binding: FragmentPersonalProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var userListDao: UserStockListDao
    private lateinit var derivedMetricsDao: FinancialDerivedMetricsDao
    private lateinit var portfolioSnapshotDao: PortfolioValueSnapshotDao
    private lateinit var portfolioCashFlowDao: PortfolioCashFlowDao
    private lateinit var holdingsAdapter: PersonalStockAdapter
    private lateinit var watchlistAdapter: PersonalStockAdapter
    private var stockRepository: StockRepository? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonalProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val db = StockzillaDatabase.Companion.getDatabase(requireContext())
        userListDao = db.userStockListDao()
        derivedMetricsDao = db.financialDerivedMetricsDao()
        portfolioSnapshotDao = db.portfolioValueSnapshotDao()
        portfolioCashFlowDao = db.portfolioCashFlowDao()
        val finnhubKey = ApiKeyManager(requireContext()).getFinnhubApiKey()
        if (!finnhubKey.isNullOrBlank()) {
            stockRepository = StockRepository(
                ApiConstants.DEFAULT_DEMO_KEY,
                finnhubKey,
                db.edgarRawFactsDao(),
                db.financialDerivedMetricsDao(),
                db.scoreSnapshotDao(),
                db.symbolTagOverrideDao()
            )
        }

        val onStockClick: (UserStockListItemEntity) -> Unit = { item ->
            (activity as? MainActivity)?.showMainAndAnalyze(item.symbol)
        }

        holdingsAdapter = PersonalStockAdapter(
            onItemClick = onStockClick,
            onRemoveClick = { item ->
                lifecycleScope.launch {
                    userListDao.deleteById(item.id)
                    loadLists()
                }
            },
            onEditClick = { item ->
                showEditHoldingDialog(item)
            }
        )
        watchlistAdapter = PersonalStockAdapter(
            onItemClick = onStockClick,
            onRemoveClick = { item ->
                lifecycleScope.launch {
                    userListDao.deleteById(item.id)
                    loadLists()
                }
            }
        )

        binding.rvHoldings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHoldings.adapter = holdingsAdapter
        binding.rvWatchlist.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWatchlist.adapter = watchlistAdapter

        binding.btnAddHolding.setOnClickListener { showAddDialog("holding") }
        binding.btnAddWatchlist.setOnClickListener { showAddDialog("watchlist") }
        binding.btnRefreshWatchlist.setOnClickListener { refreshWatchlistPrices() }
        binding.btnCash.setOnClickListener { showCashDialog() }
        binding.btnViewPortfolioChart.setOnClickListener {
            startActivity(Intent(requireContext(), PortfolioChartActivity::class.java))
        }

        loadLists()
    }

    override fun onResume() {
        super.onResume()
        loadLists()
    }

    private fun loadLists() {
        lifecycleScope.launch {
            val holdings = userListDao.getAllHoldings()
            val watchlist = userListDao.getAllWatchlist()

            val holdingsWithPrices = fetchPricesForHoldings(holdings)
            holdingsAdapter.submitList(holdingsWithPrices) {
                // Force re-measure so parent scroll container picks up full list height.
                binding.rvHoldings.post { binding.rvHoldings.requestLayout() }
            }
            binding.tvHoldingsEmpty.isVisible = holdings.isEmpty()

            val watchlistWithPrices = fetchCachedPricesForWatchlist(watchlist)
            watchlistAdapter.submitList(watchlistWithPrices) {
                binding.rvWatchlist.post { binding.rvWatchlist.requestLayout() }
            }
            binding.tvWatchlistEmpty.isVisible = watchlist.isEmpty()

            val totalValue = holdingsWithPrices.sumOf { it.currentValue ?: 0.0 }
            val totalCost = holdingsWithPrices.sumOf { it.costBasis ?: 0.0 }

            updatePortfolioSummary(totalValue, totalCost)
            if (totalValue > 0) {
                savePortfolioSnapshotAndUpdateDayChange(totalValue)
            }
        }
    }

    private fun savePortfolioSnapshotAndUpdateDayChange(totalValue: Double) {
        lifecycleScope.launch {
            val todayMs = startOfDayMs(0)
            val yesterdayMs = startOfDayMs(-1)
            val yesterday = portfolioSnapshotDao.getByDate(yesterdayMs)
            val dayChange = if (yesterday != null) totalValue - yesterday.value else null
            portfolioSnapshotDao.insertOrReplace(
                PortfolioValueSnapshotEntity(
                    dateMs = todayMs,
                    value = totalValue,
                    dayChange = dayChange,
                    recordedAt = System.currentTimeMillis()
                )
            )
            withContext(Dispatchers.Main) {
                updateDayChangeUi(dayChange, yesterday?.value)
            }
        }
    }

    private fun startOfDayMs(dayOffset: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    private fun updateDayChangeUi(dayChange: Double?, yesterdayValue: Double?) {
        if (dayChange == null) {
            binding.tvPortfolioDayChange.isVisible = false
            return
        }
        binding.tvPortfolioDayChange.isVisible = true
        val pct = if (yesterdayValue != null && yesterdayValue > 0) (dayChange / yesterdayValue) * 100.0 else null
        val sign = if (dayChange >= 0) "+" else ""
        binding.tvPortfolioDayChange.text = if (pct != null) {
            getString(R.string.portfolio_day_change_format, "$sign${formatMoney(dayChange)}", "%.1f".format(pct))
        } else {
            "$sign${formatMoney(dayChange)}"
        }
        binding.tvPortfolioDayChange.setTextColor(
            ContextCompat.getColor(requireContext(), if (dayChange >= 0) R.color.scoreGood else R.color.scorePoor)
        )
    }

    private fun showCashDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.cash_amount_hint)
            setPadding(48, 24, 48, 24)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.cash_dialog_title))
            .setView(input)
            .setPositiveButton(getString(R.string.cash_add)) { _, _ ->
                handleCashInput(input, isAdd = true)
            }
            .setNegativeButton(getString(R.string.cash_withdraw)) { _, _ ->
                handleCashInput(input, isAdd = false)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleCashInput(input: EditText, isAdd: Boolean) {
        val text = input.text?.toString()?.trim().orEmpty()
        val amount = text.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Toast.makeText(requireContext(), getString(R.string.cash_invalid_amount), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            portfolioCashFlowDao.insert(
                PortfolioCashFlowEntity(
                    amount = amount,
                    type = if (isAdd) "ADD" else "WITHDRAW",
                    createdAt = System.currentTimeMillis()
                )
            )
            withContext(Dispatchers.Main) {
                val formatted = formatMoney(amount)
                val msgRes = if (isAdd) R.string.cash_saved_add else R.string.cash_saved_withdraw
                Toast.makeText(requireContext(), getString(msgRes, formatted), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private suspend fun fetchPricesForHoldings(holdings: List<UserStockListItemEntity>): List<HoldingDisplayItem> {
        val repo = stockRepository ?: return holdings.map { HoldingDisplayItem(it, null) }
        return coroutineScope {
            holdings.map { entity ->
                async {
                    val price = repo.getLatestQuotePrice(entity.symbol).getOrNull()
                    HoldingDisplayItem(entity, price)
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchCachedPricesForWatchlist(watchlist: List<UserStockListItemEntity>): List<HoldingDisplayItem> {
        if (watchlist.isEmpty()) return emptyList()
        val symbols = watchlist.map { it.symbol }
        val priceBySymbol = derivedMetricsDao.getPricesBySymbols(symbols)
            .associate { row -> row.symbol.uppercase(Locale.US) to row.price }
        return watchlist.map { item ->
            val cachedPrice = priceBySymbol[item.symbol.uppercase(Locale.US)]
            HoldingDisplayItem(item, cachedPrice)
        }
    }

    private fun refreshWatchlistPrices() {
        val repo = stockRepository
        if (repo == null) {
            Toast.makeText(requireContext(), getString(R.string.watchlist_refresh_key_missing), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val watchlist = userListDao.getAllWatchlist()
            if (watchlist.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.watchlist_nothing_to_refresh), Toast.LENGTH_SHORT).show()
                return@launch
            }

            binding.btnRefreshWatchlist.isEnabled = false
            try {
                val uniqueSymbols = watchlist.map { it.symbol.uppercase(Locale.US) }.distinct()
                var updatedCount = 0
                for (symbol in uniqueSymbols) {
                    val latest = repo.getLatestQuotePrice(symbol).getOrNull() ?: continue
                    upsertCachedPrice(symbol, latest)
                    updatedCount++
                }
                loadLists()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.watchlist_refreshed_count, updatedCount, uniqueSymbols.size),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.btnRefreshWatchlist.isEnabled = true
            }
        }
    }

    private suspend fun upsertCachedPrice(symbol: String, price: Double) {
        val now = System.currentTimeMillis()
        val existing = derivedMetricsDao.getBySymbol(symbol)
        val updated = if (existing != null) {
            existing.copy(price = price, lastUpdated = now)
        } else {
            FinancialDerivedMetricsEntity(
                symbol = symbol,
                marketCapTier = null,
                price = price,
                marketCap = null,
                peRatio = null,
                psRatio = null,
                pbRatio = null,
                roe = null,
                debtToEquity = null,
                currentRatio = null,
                netMargin = null,
                ebitdaMargin = null,
                grossMargin = null,
                fcfMargin = null,
                revenueGrowth = null,
                averageRevenueGrowth = null,
                averageNetIncomeGrowth = null,
                netIncomeGrowth = null,
                fcfGrowth = null,
                averageFcfGrowth = null,
                analyzedAt = now,
                lastUpdated = now
            )
        }
        derivedMetricsDao.upsert(updated)
    }

    private fun updatePortfolioSummary(totalValue: Double, totalCost: Double) {
        val hasValue = totalValue > 0.0 || totalCost > 0.0
        binding.cardPortfolioSummary.isVisible = hasValue
        if (!hasValue) return
        binding.tvPortfolioValue.text = if (totalValue > 0) formatMoney(totalValue) else getString(R.string.price_unavailable_short)
        val gainDollars = if (totalCost > 0) totalValue - totalCost else null
        val gainPct = if (totalCost > 0 && gainDollars != null) (gainDollars / totalCost) * 100.0 else null
        if (gainDollars != null && gainPct != null) {
            binding.tvPortfolioGainLoss.visibility = View.VISIBLE
            val sign = if (gainDollars >= 0) "+" else ""
            binding.tvPortfolioGainLoss.text = getString(
                if (gainDollars >= 0) R.string.gain_format else R.string.loss_format,
                "$sign${formatMoney(gainDollars)}",
                "%.1f".format(gainPct)
            )
            binding.tvPortfolioGainLoss.setTextColor(
                ContextCompat.getColor(requireContext(), if (gainDollars >= 0) R.color.scoreGood else R.color.scorePoor)
            )
        } else {
            binding.tvPortfolioGainLoss.visibility = View.GONE
        }
    }

    private fun formatMoney(value: Double): String = "$%.2f".format(value)

    private fun showAddDialog(listType: String) {
        val title = if (listType == "holding") getString(R.string.add_holding) else getString(R.string.add_to_watchlist)
        val editSymbol = EditText(requireContext()).apply {
            hint = getString(R.string.symbol_hint)
            setPadding(48, 32, 48, 32)
        }
        val editShares = EditText(requireContext()).apply {
            hint = getString(R.string.shares_optional)
            setPadding(48, 16, 48, 16)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val editAvgCost = EditText(requireContext()).apply {
            hint = getString(R.string.avg_cost_optional)
            setPadding(48, 16, 48, 16)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(editSymbol)
            if (listType == "holding") {
                addView(editShares)
                addView(editAvgCost)
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(getString(R.string.add_symbol)) { _, _ ->
                val symbol = editSymbol.text.toString().trim().uppercase(Locale.US)
                if (symbol.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.industry_add_symbol_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val shares = if (listType == "holding") editShares.text.toString().toDoubleOrNull() else null
                val avgCost = if (listType == "holding") editAvgCost.text.toString().toDoubleOrNull() else null
                lifecycleScope.launch {
                    userListDao.insert(
                        UserStockListItemEntity(
                            symbol = symbol,
                            listType = listType,
                            shares = shares,
                            avgCost = avgCost
                        )
                    )
                    loadLists()
                    Toast.makeText(requireContext(), getString(R.string.industry_added, symbol), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditHoldingDialog(item: UserStockListItemEntity) {
        if (item.listType != "holding") return
        val editShares = EditText(requireContext()).apply {
            hint = getString(R.string.shares_optional)
            setPadding(48, 16, 48, 16)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(item.shares?.toString().orEmpty())
        }
        val editAvgCost = EditText(requireContext()).apply {
            hint = getString(R.string.avg_cost_optional)
            setPadding(48, 16, 48, 16)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(item.avgCost?.toString().orEmpty())
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(editShares)
            addView(editAvgCost)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("${getString(R.string.edit_holding)} (${item.symbol})")
            .setView(layout)
            .setPositiveButton(getString(R.string.save_changes), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sharesText = editShares.text.toString().trim()
                val avgCostText = editAvgCost.text.toString().trim()
                val shares = sharesText.toDoubleOrNull()
                val avgCost = avgCostText.toDoubleOrNull()
                if ((sharesText.isNotEmpty() && shares == null) || (avgCostText.isNotEmpty() && avgCost == null)) {
                    Toast.makeText(requireContext(), getString(R.string.shares_or_cost_invalid), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    userListDao.updateHoldingValues(
                        id = item.id,
                        shares = shares,
                        avgCost = avgCost
                    )
                    loadLists()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}