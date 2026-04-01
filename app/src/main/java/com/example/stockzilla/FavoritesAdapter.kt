// FavoritesAdapter.kt - RecyclerView adapter for favorites list
package com.example.stockzilla

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.databinding.ItemFavoriteBinding
import com.example.stockzilla.scoring.StockData

class FavoritesAdapter(
    private val onItemClick: (StockData) -> Unit,
    private val onAnalyzeClick: (StockData) -> Unit,
    private val onRemoveClick: (StockData) -> Unit
) : ListAdapter<StockData, FavoritesAdapter.FavoriteViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(
        private val binding: ItemFavoriteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stockData: StockData) {
            binding.apply {
                tvTicker.text = stockData.symbol
                tvCompanyName.text = stockData.companyName ?: "Unknown Company"
                tvPrice.text = stockData.price?.let { "$%.2f".format(it) } ?: "N/A"
                tvSector.text = stockData.sector ?: "Unknown"

                // Set click listeners
                root.setOnClickListener { onItemClick(stockData) }
                btnAnalyze.setOnClickListener { onAnalyzeClick(stockData) }
                btnRemove.setOnClickListener { onRemoveClick(stockData) }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<StockData>() {
        override fun areItemsTheSame(oldItem: StockData, newItem: StockData): Boolean {
            return oldItem.symbol == newItem.symbol
        }

        override fun areContentsTheSame(oldItem: StockData, newItem: StockData): Boolean {
            return oldItem == newItem
        }
    }
}

