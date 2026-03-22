package com.example.stockzilla.feature

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.R
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.data.ViewedStockRow
import com.example.stockzilla.databinding.FragmentViewedStocksBinding
import kotlinx.coroutines.launch

class ViewedStocksFragment : Fragment() {

    private var _binding: FragmentViewedStocksBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ViewedStocksAdapter
    private var allRows: List<ViewedStockRow> = emptyList()
    private var currentSortMode: ViewedSortMode = ViewedSortMode.RECENT_FIRST

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentViewedStocksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val db = StockzillaDatabase.Companion.getDatabase(requireContext())
        val rawFactsDao = db.edgarRawFactsDao()
        val derivedMetricsDao = db.financialDerivedMetricsDao()
        val scoreSnapshotDao = db.scoreSnapshotDao()
        val peerDao = db.stockIndustryPeerDao()
        adapter = ViewedStocksAdapter(
            onItemClick = { row ->
                (activity as? MainActivity)?.showMainAndAnalyze(row.symbol)
            },
            onRemoveClick = { row ->
                lifecycleScope.launch {
                    rawFactsDao.deleteBySymbol(row.symbol)
                    derivedMetricsDao.deleteBySymbol(row.symbol)
                    scoreSnapshotDao.deleteBySymbol(row.symbol)
                    peerDao.removeAllForOwner(row.symbol)
                    refreshList()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.viewed_stock_removed, row.symbol),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        binding.rvViewedStocks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvViewedStocks.adapter = adapter

        setupSortControls()

        lifecycleScope.launch {
            val list = rawFactsDao.getViewedStocksOrderByLastUpdated()
            allRows = list
            updateList(scrollToTop = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        view?.let { v ->
            lifecycleScope.launch {
                val list = StockzillaDatabase.Companion.getDatabase(requireContext()).edgarRawFactsDao().getViewedStocksOrderByLastUpdated()
                allRows = list
                // When returning to this tab after analyzing/searching a stock,
                // always snap to the top so the newest entry is immediately visible.
                updateList(scrollToTop = true)
            }
        }
    }

    private fun setupSortControls() {
        val sortOptions = listOf(
            getString(R.string.viewed_sort_recent_first),
            getString(R.string.viewed_sort_oldest_first),
            getString(R.string.viewed_sort_price_high_low),
            getString(R.string.viewed_sort_price_low_high)
        )
        val sortAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            sortOptions
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spViewedSort.adapter = sortAdapter
        binding.spViewedSort.setSelection(0, false)
        binding.spViewedSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSortMode = when (position) {
                    1 -> ViewedSortMode.OLDEST_FIRST
                    2 -> ViewedSortMode.PRICE_HIGH_LOW
                    3 -> ViewedSortMode.PRICE_LOW_HIGH
                    else -> ViewedSortMode.RECENT_FIRST
                }
                updateList(scrollToTop = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // no-op
            }
        }
    }

    private fun updateList(scrollToTop: Boolean) {
        val base = allRows
        binding.tvViewedEmpty.isVisible = base.isEmpty()

        val sorted = when (currentSortMode) {
            ViewedSortMode.RECENT_FIRST -> base.sortedByDescending { it.lastUpdated }
            ViewedSortMode.OLDEST_FIRST -> base.sortedBy { it.lastUpdated }
            ViewedSortMode.PRICE_HIGH_LOW ->
                base.sortedWith(compareByDescending<ViewedStockRow> { it.price ?: Double.MIN_VALUE }.thenBy { it.symbol })
            ViewedSortMode.PRICE_LOW_HIGH ->
                base.sortedWith(compareBy<ViewedStockRow> { it.price ?: Double.MAX_VALUE }.thenBy { it.symbol })
        }

        adapter.submitList(sorted.map { ViewedListItem.Stock(it) }) {
            if (scrollToTop && sorted.isNotEmpty()) {
                val lm = binding.rvViewedStocks.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(0, 0)
                    ?: binding.rvViewedStocks.scrollToPosition(0)
            }
        }
    }

    private enum class ViewedSortMode {
        RECENT_FIRST,
        OLDEST_FIRST,
        PRICE_HIGH_LOW,
        PRICE_LOW_HIGH
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}