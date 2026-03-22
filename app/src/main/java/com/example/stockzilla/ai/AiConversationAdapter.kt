package com.example.stockzilla.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.R
import com.example.stockzilla.data.AiConversationEntity

class AiConversationAdapter(
    private val onClick: (AiConversationEntity) -> Unit,
    private val onLongClick: (AiConversationEntity) -> Unit
) : ListAdapter<AiConversationEntity, AiConversationAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_conversation, parent, false)
        return ViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (AiConversationEntity) -> Unit,
        private val onLongClick: (AiConversationEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val titleView: TextView = itemView.findViewById(R.id.conversationTitle)
        private var currentItem: AiConversationEntity? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let(onClick)
            }
            itemView.setOnLongClickListener {
                currentItem?.let(onLongClick)
                true
            }
        }

        fun bind(item: AiConversationEntity) {
            currentItem = item
            titleView.text = item.title
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AiConversationEntity>() {
        override fun areItemsTheSame(
            oldItem: AiConversationEntity,
            newItem: AiConversationEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: AiConversationEntity,
            newItem: AiConversationEntity
        ): Boolean = oldItem == newItem
    }
}