// ApiKeySetupDialog.kt - Dialog for API key input and setup
package com.example.stockzilla

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.databinding.DialogApiKeySetupBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ApiKeySetupDialog(
    private val onApiKeySet: (String) -> Unit
) : DialogFragment() {

    private var _binding: DialogApiKeySetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyManager: ApiKeyManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogApiKeySetupBinding.inflate(layoutInflater)
        apiKeyManager = ApiKeyManager(requireContext())

        setupUI()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    private fun setupUI() {
        // Pre-fill with existing API key if available
        apiKeyManager.getApiKey()?.let { existingKey ->
            binding.etApiKey.setText(existingKey)
        }

        // Enable/disable save button based on input
        binding.etApiKey.addTextChangedListener { text ->
            val isValid = !text.isNullOrBlank() &&
                    apiKeyManager.isValidApiKeyFormat(text.toString())
            binding.btnSave.isEnabled = isValid

            if (!text.isNullOrBlank() && !apiKeyManager.isValidApiKeyFormat(text.toString())) {
                binding.tilApiKey.error = "Invalid API key format"
            } else {
                binding.tilApiKey.error = null
            }
        }

        binding.btnSave.setOnClickListener {
            val apiKey = binding.etApiKey.text.toString().trim()
            if (apiKeyManager.isValidApiKeyFormat(apiKey)) {
                validateAndSaveApiKey(apiKey)
            } else {
                binding.tilApiKey.error = "Please enter a valid API key"
            }
        }

        binding.btnGetApiKey.setOnClickListener {
            openApiKeyWebsite()
        }

        binding.btnSkip.setOnClickListener {
            // Allow skipping for demo purposes
            onApiKeySet("demo")
            dismiss()
        }
    }

    private fun validateAndSaveApiKey(apiKey: String) {
        binding.btnSave.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                // Test the API key by making a simple request
                val stockRepository = StockRepository(apiKey)
                val result = stockRepository.getStockData("AAPL")

                result.onSuccess {
                    // API key works!
                    apiKeyManager.saveApiKey(apiKey)
                    apiKeyManager.markApiKeyAsValidated()

                    Toast.makeText(requireContext(),
                        "API key saved successfully!",
                        Toast.LENGTH_SHORT).show()

                    onApiKeySet(apiKey)
                    dismiss()
                }.onFailure { exception ->
                    // API key doesn't work
                    binding.tilApiKey.error = when {
                        exception.message?.contains("401") == true -> "Invalid API key"
                        exception.message?.contains("403") == true -> "API key doesn't have permission"
                        else -> "Failed to validate API key: ${exception.message}"
                    }
                }
            } catch (e: Exception) {
                binding.tilApiKey.error = "Network error: ${e.message}"
            } finally {
                binding.btnSave.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun openApiKeyWebsite() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "https://financialmodelingprep.com/developer/docs".toUri()
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}