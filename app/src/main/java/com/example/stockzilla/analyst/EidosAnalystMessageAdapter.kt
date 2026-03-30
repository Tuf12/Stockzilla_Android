package com.example.stockzilla.analyst

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stockzilla.R
import com.example.stockzilla.data.EidosAnalystChatMessageEntity
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

class EidosAnalystMessageAdapter(
    private val onDeleteMessage: (EidosAnalystChatMessageEntity) -> Unit
) : ListAdapter<EidosAnalystChatMessageEntity, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role.lowercase() == "user") VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            UserViewHolder(inflater.inflate(R.layout.item_ai_message_user, parent, false))
        } else {
            AssistantViewHolder(inflater.inflate(R.layout.item_ai_message_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(item, onDeleteMessage)
            is AssistantViewHolder -> holder.bind(item, onDeleteMessage)
        }
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageView: TextView = itemView.findViewById(R.id.messageText)
        private val copyButton: View = itemView.findViewById(R.id.btnCopyMessage)
        private val deleteButton: View = itemView.findViewById(R.id.btnDeleteMessage)
        private val markwon: Markwon = Markwon.builder(itemView.context)
            .usePlugin(HtmlPlugin.create())
            .build()

        fun bind(item: EidosAnalystChatMessageEntity, onDelete: (EidosAnalystChatMessageEntity) -> Unit) {
            markwon.setMarkdown(messageView, item.content)
            copyButton.setOnClickListener {
                copyToClipboard(itemView.context, item.content)
            }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    private class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageView: TextView = itemView.findViewById(R.id.messageText)
        private val copyButton: View = itemView.findViewById(R.id.btnCopyMessage)
        private val deleteButton: View = itemView.findViewById(R.id.btnDeleteMessage)
        private val markwon: Markwon = Markwon.builder(itemView.context)
            .usePlugin(HtmlPlugin.create())
            .build()

        fun bind(item: EidosAnalystChatMessageEntity, onDelete: (EidosAnalystChatMessageEntity) -> Unit) {
            markwon.setMarkdown(messageView, item.content)
            copyButton.setOnClickListener {
                copyToClipboard(itemView.context, item.content)
            }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<EidosAnalystChatMessageEntity>() {
        override fun areItemsTheSame(
            oldItem: EidosAnalystChatMessageEntity,
            newItem: EidosAnalystChatMessageEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: EidosAnalystChatMessageEntity,
            newItem: EidosAnalystChatMessageEntity
        ): Boolean = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2

        private fun copyToClipboard(context: Context, text: String) {
            val clip = ClipData.newPlainText("Eidos Analyst", text)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(
                context,
                context.getString(R.string.ai_copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}