package com.example.stockzilla.ai

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
import com.example.stockzilla.sec.SecFilingDiscoveryCard
import com.example.stockzilla.data.AiMessageEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin

class AiMessageAdapter(
    private val onDiscoveryConfirm: ((symbol: String, accessions: List<String>) -> Unit)? = null,
    private val onDeleteMessage: (AiMessageEntity) -> Unit
) : ListAdapter<AiMessageEntity, RecyclerView.ViewHolder>(DiffCallback) {

    private val gson = Gson()

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.role.lowercase() == "user" -> VIEW_TYPE_USER
            isDiscoveryMessage(item.content) -> VIEW_TYPE_DISCOVERY
            else -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_ai_message_user, parent, false)
                UserViewHolder(view)
            }
            VIEW_TYPE_DISCOVERY -> {
                val view = inflater.inflate(R.layout.item_ai_message_discovery, parent, false)
                DiscoveryViewHolder(view, onDiscoveryConfirm)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_ai_message_assistant, parent, false)
                AssistantViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(item, onDeleteMessage)
            is AssistantViewHolder -> holder.bind(item, onDeleteMessage)
            is DiscoveryViewHolder -> holder.bind(item, gson, onDeleteMessage)
        }
    }

    /**
     * Checks if message content contains SEC discovery data marker.
     * Discovery messages are JSON payloads wrapped in a special marker.
     */
    private fun isDiscoveryMessage(content: String): Boolean {
        return content.contains(DISCOVERY_MARKER_START) && content.contains(DISCOVERY_MARKER_END)
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageView: TextView = itemView.findViewById(R.id.messageText)
        private val copyButton: View = itemView.findViewById(R.id.btnCopyMessage)
        private val deleteButton: View = itemView.findViewById(R.id.btnDeleteMessage)
        private val markwon: Markwon = Markwon.builder(itemView.context)
            .usePlugin(HtmlPlugin.create())
            .build()

        fun bind(item: AiMessageEntity, onDelete: (AiMessageEntity) -> Unit) {
            markwon.setMarkdown(messageView, item.content)
            copyButton.setOnClickListener {
                copyAssistantReplyToClipboard(itemView.context, item.content)
            }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageView: TextView = itemView.findViewById(R.id.messageText)
        private val copyButton: View = itemView.findViewById(R.id.btnCopyMessage)
        private val deleteButton: View = itemView.findViewById(R.id.btnDeleteMessage)
        private val markwon: Markwon = Markwon.builder(itemView.context)
            .usePlugin(HtmlPlugin.create())
            .build()

        fun bind(item: AiMessageEntity, onDelete: (AiMessageEntity) -> Unit) {
            markwon.setMarkdown(messageView, item.content)
            copyButton.setOnClickListener {
                copyAssistantReplyToClipboard(itemView.context, item.content)
            }
            deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    /**
     * ViewHolder for SEC filing discovery cards.
     * Parses the discovery JSON and renders the interactive card.
     */
    class DiscoveryViewHolder(
        itemView: View,
        private val onDiscoveryConfirm: ((symbol: String, accessions: List<String>) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {

        private val discoveryCard: SecFilingDiscoveryCard = itemView.findViewById(R.id.discoveryCard)
        private val copyButton: View = itemView.findViewById(R.id.btnCopyMessage)
        private val deleteButton: View = itemView.findViewById(R.id.btnDeleteMessage)

        fun bind(item: AiMessageEntity, gson: Gson, onDelete: (AiMessageEntity) -> Unit) {
            copyButton.setOnClickListener {
                val text = textForDiscoveryCopy(itemView.context, item.content)
                copyAssistantReplyToClipboard(itemView.context, text)
            }
            deleteButton.setOnClickListener { onDelete(item) }

            val content = item.content
            val startIdx = content.indexOf(DISCOVERY_MARKER_START)
            val endIdx = content.indexOf(DISCOVERY_MARKER_END)

            if (startIdx == -1 || endIdx == -1) {
                discoveryCard.visibility = View.GONE
                return
            }

            val json = content.substring(startIdx + DISCOVERY_MARKER_START.length, endIdx)

            try {
                val type = object : TypeToken<Map<String, Any?>>() {}.type
                val data: Map<String, Any?> = gson.fromJson(json, type)

                val symbol = data["symbol"] as? String ?: return
                val candidates = data["candidates"] as? List<Map<String, Any?>> ?: return

                val candidateList = candidates.map { c ->
                    SecFilingDiscoveryCard.SecFilingCandidate(
                        accessionNumber = c["accessionNumber"] as? String ?: "",
                        formType = c["formType"] as? String ?: "",
                        filingDate = c["filingDate"] as? String ?: "",
                        description = generateDescription(c)
                    )
                }.filter { it.accessionNumber.isNotBlank() }

                discoveryCard.setDiscoveryData(symbol, candidateList)
                discoveryCard.setListener(object : SecFilingDiscoveryCard.DiscoveryListener {
                    override fun onConfirm(selectedAccessions: List<String>) {
                        onDiscoveryConfirm?.invoke(symbol, selectedAccessions)
                        discoveryCard.showProcessingState()
                    }

                    override fun onDecline() {
                        // Card handles its own declined state
                    }
                })
            } catch (e: Exception) {
                discoveryCard.visibility = View.GONE
            }
        }

        private fun generateDescription(candidate: Map<String, Any?>): String {
            val formType = candidate["formType"] as? String ?: ""
            val itemsRaw = candidate["itemsRaw"] as? String

            return when {
                formType == "4" -> "Insider transaction report - shows buys, sells, or option exercises by officers/directors/owners"
                formType == "3" -> "Initial insider ownership statement - filed when becoming a 10% owner, officer, or director"
                formType == "5" -> "Annual insider ownership changes - yearly summary of insider transactions"
                formType in listOf("13F-HR", "13F-HR/A") -> "Quarterly institutional holdings report - shows positions held by hedge funds and institutions"
                formType == "8-K" -> "Current report - material events like earnings, M&A, executive changes, or major contracts"
                formType == "8-K/A" -> "Amended current report - updates or corrections to a previous 8-K filing"
                formType in listOf("SC 13D", "SC 13D/A") -> "Beneficial ownership report (over 5%) - activist investor or major stakeholder disclosure"
                formType in listOf("SC 13G", "SC 13G/A") -> "Passive investor report (over 5%) - indicates passive/non-control ownership stake"
                formType == "144" -> "Notice of proposed sale - restricted or control securities intended to be sold"
                formType in listOf("S-1", "S-1/A") -> "Initial registration statement - IPO or first-time securities offering"
                formType in listOf("S-3", "S-3/A") -> "Simplified registration statement - follow-on offering by established company"
                formType in listOf("S-4", "S-4/A") -> "Registration for M&A transaction - merger, acquisition, or exchange offer"
                formType == "S-8" -> "Employee stock plan registration - equity compensation for employees"
                formType == "EFFECT" -> "Registration becomes effective - securities can now be sold or offered"
                formType in listOf("DEFM14A", "DEFA14A") -> "Merger proxy statement - shareholder vote on acquisition or merger"
                formType == "DEF 14A" -> "Annual proxy statement - director elections, executive pay, shareholder votes"
                formType in listOf("SC TO-T", "SC TO-T/A") -> "Third-party tender offer - outside party offering to buy company shares"
                formType in listOf("SC TO-I", "SC TO-I/A") -> "Issuer tender offer - company offering to buy back its own shares"
                formType == "NT 10-K" -> "Late filing notice - 10-K annual report will be delayed (red flag)"
                formType == "NT 10-Q" -> "Late filing notice - 10-Q quarterly report will be delayed (red flag)"
                itemsRaw != null -> "${formType} filing - Items: $itemsRaw"
                else -> "${formType} SEC filing for review"
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AiMessageEntity>() {
        override fun areItemsTheSame(oldItem: AiMessageEntity, newItem: AiMessageEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: AiMessageEntity,
            newItem: AiMessageEntity
        ): Boolean = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_DISCOVERY = 3

        // Markers for embedding discovery data in message content
        const val DISCOVERY_MARKER_START = "<!--SEC_DISCOVERY_START-->"
        const val DISCOVERY_MARKER_END = "<!--SEC_DISCOVERY_END-->"

        private fun copyAssistantReplyToClipboard(context: Context, text: String) {
            val clip = ClipData.newPlainText("Eidos", text)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(
                context,
                context.getString(R.string.ai_copied_to_clipboard),
                Toast.LENGTH_SHORT
            ).show()
        }

        /** Assistant text before embedded discovery payload; avoids pasting raw JSON. */
        private fun textForDiscoveryCopy(context: Context, content: String): String {
            val startIdx = content.indexOf(DISCOVERY_MARKER_START)
            if (startIdx < 0) return content.trim()
            val before = content.substring(0, startIdx).trim()
            return if (before.isNotEmpty()) {
                before
            } else {
                context.getString(R.string.ai_discovery_copy_fallback)
            }
        }

        /**
         * Wraps discovery data JSON in markers for the adapter to recognize.
         */
        fun wrapDiscoveryData(json: String): String {
            return "$DISCOVERY_MARKER_START$json$DISCOVERY_MARKER_END"
        }
    }
}
