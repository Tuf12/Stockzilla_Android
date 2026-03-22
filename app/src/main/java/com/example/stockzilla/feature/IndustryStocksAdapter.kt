package com.example.stockzilla.feature

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.data.IndustryPeer
import com.example.stockzilla.R
import com.example.stockzilla.databinding.ItemIndustryStockBinding

enum class IndustryPeersMode { Discover, MyGroup }

class IndustryStocksAdapter(
    private val currentSymbol: String?,
    private val onStockClick: (IndustryPeer) -> Unit,
    mode: IndustryPeersMode,
    private val onRemove: ((IndustryPeer) -> Unit)? = null,
    private val onAddToGroup: ((IndustryPeer) -> Unit)? = null,
    savedPeerSymbols: Set<String> = emptySet()
) : ListAdapter<IndustryPeer, IndustryStocksAdapter.ViewHolder>(DiffCallback) {

    var mode: IndustryPeersMode = mode
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var savedPeerSymbols: Set<String> = savedPeerSymbols
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIndustryStockBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemIndustryStockBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: IndustryPeer) {
            val context = binding.root.context
            binding.tvSymbol.text = item.symbol
            binding.tvCompanyName.text = item.companyName ?: context.getString(R.string.not_available)
            binding.tvPrice.text = item.price?.let { context.getString(R.string.price_format, it) }
                ?: context.getString(R.string.price_unavailable)

            val details = listOfNotNull(item.sector, item.industry)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = " • ")
                ?: context.getString(R.string.not_available)
            binding.tvSector.text = details

            val isCurrent = currentSymbol?.equals(item.symbol, ignoreCase = true) == true
            binding.tvCurrentBadge.isVisible = isCurrent
            binding.root.strokeWidth = if (isCurrent) 4 else 0
            binding.root.setOnClickListener { onStockClick(item) }

            val inMyGroup = item.symbol.uppercase() in savedPeerSymbols
            when (mode) {
                IndustryPeersMode.Discover -> {
                    binding.btnAddToGroup.isVisible = true
                    binding.btnRemove.isVisible = false
                    binding.btnAddToGroup.isEnabled = !inMyGroup
                    binding.btnAddToGroup.setOnClickListener { if (!inMyGroup) onAddToGroup?.invoke(item) }
                    binding.tvInMyGroupBadge.isVisible = inMyGroup
                }
                IndustryPeersMode.MyGroup -> {
                    binding.btnAddToGroup.isVisible = false
                    binding.btnRemove.isVisible = true
                    binding.tvInMyGroupBadge.isVisible = false
                    binding.btnRemove.setOnClickListener { onRemove?.invoke(item) }
                }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<IndustryPeer>() {
        override fun areItemsTheSame(oldItem: IndustryPeer, newItem: IndustryPeer): Boolean =
            oldItem.symbol == newItem.symbol

        override fun areContentsTheSame(oldItem: IndustryPeer, newItem: IndustryPeer): Boolean =
            oldItem == newItem
    }
}