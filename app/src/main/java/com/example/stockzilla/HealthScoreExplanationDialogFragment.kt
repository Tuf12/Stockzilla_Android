package com.example.stockzilla

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.example.stockzilla.databinding.DialogHealthScoreExplanationBinding
import com.example.stockzilla.databinding.ItemHealthScoreDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

@Suppress("DEPRECATION")
class HealthScoreExplanationDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogHealthScoreExplanationBinding.inflate(layoutInflater)
        val args = requireArguments()

        val title = args.getString(ARG_TITLE).orEmpty()
        val summary = args.getString(ARG_SUMMARY)
        val emptyMessage = args.getString(ARG_EMPTY_MESSAGE)
        @Suppress("UNCHECKED_CAST")
        val details = args.getSerializable(ARG_DETAILS) as? ArrayList<HealthScoreDetail>

        binding.tvSectionTitle.text = title
        binding.tvSectionSummary.isVisible = !summary.isNullOrBlank()
        binding.tvSectionSummary.text = summary

        if (details.isNullOrEmpty()) {
            binding.tvEmptyState.isVisible = true
            binding.tvEmptyState.text = emptyMessage ?: getString(R.string.health_score_no_details_available)
        } else {
            binding.tvEmptyState.isVisible = false
            details.forEach { detail ->
                val itemBinding = ItemHealthScoreDetailBinding.inflate(layoutInflater, binding.detailContainer, false)
                itemBinding.tvDetailLabel.text = detail.label
                itemBinding.tvDetailValue.text = getString(R.string.health_score_detail_value, detail.value)

                if (!detail.weight.isNullOrBlank()) {
                    itemBinding.tvDetailWeight.isVisible = true
                    itemBinding.tvDetailWeight.text = getString(R.string.health_score_detail_weight, detail.weight)
                } else {
                    itemBinding.tvDetailWeight.isVisible = false
                }

                if (!detail.normalized.isNullOrBlank()) {
                    itemBinding.tvDetailNormalized.isVisible = true
                    itemBinding.tvDetailNormalized.text = getString(R.string.health_score_detail_normalized, detail.normalized)
                } else {
                    itemBinding.tvDetailNormalized.isVisible = false
                }

                if (!detail.rationale.isNullOrBlank()) {
                    itemBinding.tvDetailRationale.isVisible = true
                    itemBinding.tvDetailRationale.text = detail.rationale
                } else {
                    itemBinding.tvDetailRationale.isVisible = false
                }

                binding.detailContainer.addView(itemBinding.root)
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUMMARY = "arg_summary"
        private const val ARG_DETAILS = "arg_details"
        private const val ARG_EMPTY_MESSAGE = "arg_empty_message"

        const val TAG = "health_score_explanation"

        fun newInstance(
            title: String,
            summary: String?,
            details: List<HealthScoreDetail>,
            emptyMessage: String
        ): HealthScoreExplanationDialogFragment {
            val fragment = HealthScoreExplanationDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_SUMMARY, summary)
                putString(ARG_EMPTY_MESSAGE, emptyMessage)
                putSerializable(ARG_DETAILS, ArrayList(details))
            }
            return fragment
        }
    }
}