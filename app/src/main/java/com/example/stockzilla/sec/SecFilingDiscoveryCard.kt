package com.example.stockzilla.sec

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.stockzilla.R

/**
 * A card component for displaying SEC filing discovery candidates in the chat UI.
 * Allows users to select forms and confirm/decline analysis.
 */
class SecFilingDiscoveryCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    private val container: LinearLayout
    private val titleText: TextView
    private val subtitleText: TextView
    private val candidatesContainer: LinearLayout
    private val confirmButton: Button
    private val declineButton: Button
    private val statusText: TextView

    private val selectedAccessions = mutableSetOf<String>()
    private var candidates: List<SecFilingCandidate> = emptyList()
    private var listener: DiscoveryListener? = null

    data class SecFilingCandidate(
        val accessionNumber: String,
        val formType: String,
        val filingDate: String,
        val description: String
    )

    interface DiscoveryListener {
        fun onConfirm(selectedAccessions: List<String>)
        fun onDecline()
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.card_sec_filing_discovery, this, true)

        container = findViewById(R.id.discoveryContainer)
        titleText = findViewById(R.id.discoveryTitle)
        subtitleText = findViewById(R.id.discoverySubtitle)
        candidatesContainer = findViewById(R.id.candidatesContainer)
        confirmButton = findViewById(R.id.confirmButton)
        declineButton = findViewById(R.id.declineButton)
        statusText = findViewById(R.id.statusText)

        radius = context.resources.getDimension(R.dimen.card_corner_radius)
        cardElevation = context.resources.getDimension(R.dimen.card_elevation)
        setCardBackgroundColor(context.getColor(R.color.colorSurface))
        setContentPadding(
            context.resources.getDimensionPixelSize(R.dimen.card_padding),
            context.resources.getDimensionPixelSize(R.dimen.card_padding),
            context.resources.getDimensionPixelSize(R.dimen.card_padding),
            context.resources.getDimensionPixelSize(R.dimen.card_padding)
        )

        confirmButton.setOnClickListener {
            if (selectedAccessions.isNotEmpty()) {
                listener?.onConfirm(selectedAccessions.toList())
                showProcessingState()
            }
        }

        declineButton.setOnClickListener {
            listener?.onDecline()
            showDeclinedState()
        }
    }

    fun setListener(listener: DiscoveryListener) {
        this.listener = listener
    }

    fun setDiscoveryData(symbol: String, candidates: List<SecFilingCandidate>) {
        this.candidates = candidates
        selectedAccessions.clear()

        titleText.text = context.getString(R.string.sec_discovery_title, symbol)
        subtitleText.text = context.getString(R.string.sec_discovery_subtitle, candidates.size)

        candidatesContainer.removeAllViews()

        candidates.forEach { candidate ->
            val itemView = LayoutInflater.from(context)
                .inflate(R.layout.item_sec_filing_candidate, candidatesContainer, false)

            val checkBox = itemView.findViewById<CheckBox>(R.id.candidateCheckBox)
            val formTypeText = itemView.findViewById<TextView>(R.id.formTypeText)
            val dateText = itemView.findViewById<TextView>(R.id.dateText)
            val accessionText = itemView.findViewById<TextView>(R.id.accessionText)
            val descriptionText = itemView.findViewById<TextView>(R.id.descriptionText)

            formTypeText.text = candidate.formType
            dateText.text = candidate.filingDate
            accessionText.text = candidate.accessionNumber
            descriptionText.text = candidate.description

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAccessions.add(candidate.accessionNumber)
                } else {
                    selectedAccessions.remove(candidate.accessionNumber)
                }
                updateConfirmButtonState()
            }

            candidatesContainer.addView(itemView)
        }

        updateConfirmButtonState()
        statusText.visibility = GONE
    }

    private fun updateConfirmButtonState() {
        confirmButton.isEnabled = selectedAccessions.isNotEmpty()
        confirmButton.text = if (selectedAccessions.isEmpty()) {
            context.getString(R.string.sec_discovery_confirm_none)
        } else {
            context.getString(R.string.sec_discovery_confirm_selected, selectedAccessions.size)
        }
    }

    internal fun showProcessingState() {
        confirmButton.isEnabled = false
        declineButton.isEnabled = false
        statusText.visibility = VISIBLE
        statusText.text = context.getString(R.string.sec_discovery_processing)

        // Disable all checkboxes
        for (i in 0 until candidatesContainer.childCount) {
            val itemView = candidatesContainer.getChildAt(i)
            itemView.findViewById<CheckBox>(R.id.candidateCheckBox)?.isEnabled = false
        }
    }

    private fun showDeclinedState() {
        confirmButton.isEnabled = false
        declineButton.isEnabled = false
        statusText.visibility = VISIBLE
        statusText.text = context.getString(R.string.sec_discovery_declined)

        // Disable all checkboxes
        for (i in 0 until candidatesContainer.childCount) {
            val itemView = candidatesContainer.getChildAt(i)
            itemView.findViewById<CheckBox>(R.id.candidateCheckBox)?.isEnabled = false
        }
    }

    fun showCompletedState(savedCount: Int) {
        confirmButton.visibility = GONE
        declineButton.visibility = GONE
        statusText.visibility = VISIBLE
        statusText.text = context.getString(R.string.sec_discovery_completed, savedCount)
    }

    fun showErrorState(error: String) {
        confirmButton.isEnabled = true
        declineButton.isEnabled = true
        statusText.visibility = VISIBLE
        statusText.text = context.getString(R.string.sec_discovery_error, error)
    }
}