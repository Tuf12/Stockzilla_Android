package com.example.stockzilla// com.example.stockzilla.StockDetailsDialog.kt - Dialog to show detailed stock information

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class StockDetailsDialog : DialogFragment() {

    companion object {
        private const val ARG_STOCK_DATA = "stock_data"

        fun show(fragmentManager: FragmentManager, stockData: StockData) {
            val dialog = StockDetailsDialog()
            val args = Bundle()
            // Convert stockData to JSON string for passing
            args.putString(ARG_STOCK_DATA, stockDataToJson(stockData))
            dialog.arguments = args
            dialog.show(fragmentManager, "com.example.stockzilla.StockDetailsDialog")
        }

        private fun stockDataToJson(stockData: StockData): String {
            return com.google.gson.Gson().toJson(stockData)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val stockDataJson = arguments?.getString(ARG_STOCK_DATA)
        val stockData = stockDataJson?.let {
            com.google.gson.Gson().fromJson(it, StockData::class.java)
        }

        val message = buildString {
            stockData?.let { stock ->
                appendLine("Company: ${stock.companyName ?: "Unknown"}")
                appendLine("Ticker: ${stock.symbol}")
                appendLine("Sector: ${stock.sector ?: "Unknown"}")
                appendLine("Industry: ${stock.industry ?: "Unknown"}")
                appendLine()
                appendLine("Price: ${stock.price?.let { "$%.2f".format(it) } ?: "N/A"}")
                appendLine("Market Cap: ${stock.marketCap?.let { formatMarketCap(it) } ?: "N/A"}")
                appendLine()
                appendLine("Financial Metrics:")
                appendLine("• Revenue: ${stock.revenue?.let { formatLargeNumber(it) } ?: "N/A"}")
                appendLine("• Net Income: ${stock.netIncome?.let { formatLargeNumber(it) } ?: "N/A"}")
                appendLine("• EPS: ${stock.eps?.let { "$%.2f".format(it) } ?: "N/A"}")
                appendLine("• P/E Ratio: ${stock.peRatio?.let { "%.2f".format(it) } ?: "N/A"}")
                appendLine("• P/S Ratio: ${stock.psRatio?.let { "%.2f".format(it) } ?: "N/A"}")
                appendLine("• ROE: ${stock.roe?.let { "%.1f%%".format(it * 100) } ?: "N/A"}")
                appendLine("• Debt/Equity: ${stock.debtToEquity?.let { "%.2f".format(it) } ?: "N/A"}")
                appendLine("• Free Cash Flow: ${stock.freeCashFlow?.let { formatLargeNumber(it) } ?: "N/A"}")
            } ?: append("No stock data available")
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("${stockData?.symbol} Details")
            .setMessage(message)
            .setPositiveButton("Close") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Analyze") { dialog, _ ->
                // Trigger analysis from parent activity
                (activity as? MainActivity)?.let { mainActivity ->
                    stockData?.let { mainActivity.analyzeStock(it.symbol) }
                }
                dialog.dismiss()
            }
            .create()
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