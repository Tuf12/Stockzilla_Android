package com.example.stockzilla.feature

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.R
import com.example.stockzilla.data.FinnhubSearchResult
import com.example.stockzilla.databinding.ItemAddPeerBinding

class AddPeerAdapter(
    private val onItemClick: (FinnhubSearchResult) -> Unit
) : ListAdapter<FinnhubSearchResult, AddPeerAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAddPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemAddPeerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FinnhubSearchResult) {
            binding.tvSymbol.text = item.symbol ?: item.displaySymbol ?: ""
            binding.tvDescription.text = item.description?.takeIf { it.isNotBlank() }
                ?: binding.root.context.getString(R.string.not_available)
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<FinnhubSearchResult>() {
        override fun areItemsTheSame(a: FinnhubSearchResult, b: FinnhubSearchResult): Boolean =
            (a.symbol ?: "") == (b.symbol ?: "")

        override fun areContentsTheSame(a: FinnhubSearchResult, b: FinnhubSearchResult): Boolean = a == b
    }
}