package com.example.stockzilla.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.R
import com.example.stockzilla.data.AiMemoryCacheEntity

class AiMemoryCacheAdapter(
    private val onDeleteClick: (Long) -> Unit
) : ListAdapter<AiMemoryCacheEntity, AiMemoryCacheAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_memory_note, parent, false)
        return ViewHolder(view, onDeleteClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onDeleteClick: (Long) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val scopeView: TextView = itemView.findViewById(R.id.memoryScope)
        private val typeView: TextView = itemView.findViewById(R.id.memoryType)
        private val textView: TextView = itemView.findViewById(R.id.memoryText)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btnDeleteMemory)

        fun bind(item: AiMemoryCacheEntity) {
            val scopeLabel = when (item.scope) {
                "STOCK" -> "Stock"
                "GROUP" -> "Group"
                "USER" -> "User"
                else -> item.scope
            }
            scopeView.text = scopeLabel
            typeView.text = item.noteType
            textView.text = item.noteText

            deleteButton.setOnClickListener {
                onDeleteClick(item.id)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AiMemoryCacheEntity>() {
        override fun areItemsTheSame(
            oldItem: AiMemoryCacheEntity,
            newItem: AiMemoryCacheEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: AiMemoryCacheEntity,
            newItem: AiMemoryCacheEntity
        ): Boolean = oldItem == newItem
    }
}