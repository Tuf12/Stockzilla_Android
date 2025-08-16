// FavoritesAdapter.kt - RecyclerView adapter for favorites list
package com.example.stockzilla

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.databinding.ItemFavoriteBinding

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

// item_favorite.xml layout
/*
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginVertical="4dp"
    app:cardCornerRadius="8dp"
    app:cardElevation="2dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="12dp">

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">

            <TextView
                android:id="@+id/tvTicker"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="AAPL"
                android:textSize="16sp"
                android:textStyle="bold"
                android:textColor="@color/colorPrimary" />

            <TextView
                android:id="@+id/tvCompanyName"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Apple Inc."
                android:textSize="14sp"
                android:textColor="@color/textSecondary" />

            <TextView
                android:id="@+id/tvSector"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Technology"
                android:textSize="12sp"
                android:textColor="@color/textSecondary" />

        </LinearLayout>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="end">

            <TextView
                android:id="@+id/tvPrice"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="$150.25"
                android:textSize="16sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnAnalyze"
                    android:layout_width="wrap_content"
                    android:layout_height="32dp"
                    android:layout_marginEnd="4dp"
                    android:text="Analyze"
                    android:textSize="12sp"
                    android:minHeight="0dp"
                    style="@style/Widget.Material3.Button.OutlinedButton" />

                <com.google.android.material.button.MaterialButton
                    android:id="@+id/btnRemove"
                    android:layout_width="32dp"
                    android:layout_height="32dp"
                    app:icon="@drawable/ic_delete"
                    app:iconSize="16dp"
                    android:minHeight="0dp"
                    android:insetTop="0dp"
                    android:insetBottom="0dp"
                    style="@style/Widget.Material3.Button.TextButton" />

            </LinearLayout>

        </LinearLayout>

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
*/