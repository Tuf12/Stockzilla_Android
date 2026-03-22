package com.example.stockzilla.feature

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.data.FinnhubApi
import com.example.stockzilla.databinding.DialogFinnhubApiKeySetupBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class FinnhubApiKeySetupDialog(
    private val onFinnhubKeySet: (String?) -> Unit
) : DialogFragment() {

    private var _binding: DialogFinnhubApiKeySetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyManager: ApiKeyManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFinnhubApiKeySetupBinding.inflate(layoutInflater)
        apiKeyManager = ApiKeyManager(requireContext())

        setupUI()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setCancelable(true)
            .create()
    }

    private fun setupUI() {
        apiKeyManager.getFinnhubApiKey()?.let { existingKey ->
            binding.etFinnhubApiKey.setText(existingKey)
        }

        apiKeyManager.getAiApiKey()?.let { existingKey ->
            binding.etAiApiKey.setText(existingKey)
        }

        // Keep Save enabled and validate on click so users can save just the AI key.
        binding.btnSaveFinnhub.isEnabled = true

        binding.btnSaveFinnhub.setOnClickListener {
            val finnhubKey = binding.etFinnhubApiKey.text.toString().trim()
            val aiKey = binding.etAiApiKey.text?.toString()?.trim().orEmpty()

            // If Finnhub key is blank but AI key is provided, just save the AI key.
            if (finnhubKey.isBlank()) {
                if (aiKey.isNotBlank()) {
                    apiKeyManager.saveAiApiKey(aiKey)
                    Toast.makeText(requireContext(), "AI API key saved!", Toast.LENGTH_SHORT).show()
                    onFinnhubKeySet(null)
                    dismiss()
                } else {
                    binding.tilFinnhubApiKey.error = "Enter a Finnhub key or an AI API key."
                }
                return@setOnClickListener
            }

            // Finnhub key entered – validate basic format before network check.
            if (apiKeyManager.isValidFinnhubApiKeyFormat(finnhubKey)) {
                validateAndSaveFinnhubKey(finnhubKey, aiKey)
            } else {
                binding.tilFinnhubApiKey.error = "Please enter a valid Finnhub API key"
            }
        }

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnGetFinnhubKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = "https://finnhub.io/register".toUri()
            })
        }
    }

    private fun validateAndSaveFinnhubKey(finnhubKey: String, aiKey: String) {
        binding.btnSaveFinnhub.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val api = Retrofit.Builder()
                            .baseUrl("https://finnhub.io/api/v1/")
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(FinnhubApi::class.java)
                        api.getQuote("AAPL", finnhubKey)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) {
                    apiKeyManager.saveFinnhubApiKey(finnhubKey)
                    apiKeyManager.markFinnhubApiKeyAsValidated()

                    // Save optional AI API key for Grok / LLM assistant.
                    if (aiKey.isNotBlank()) {
                        apiKeyManager.saveAiApiKey(aiKey)
                    }

                    Toast.makeText(requireContext(), "API keys saved!", Toast.LENGTH_SHORT).show()
                    onFinnhubKeySet(finnhubKey)
                    dismiss()
                } else {
                    binding.tilFinnhubApiKey.error = "Failed to validate key. Check key and try again."
                }
            } catch (e: Exception) {
                binding.tilFinnhubApiKey.error = "Network error: ${e.message}"
            } finally {
                binding.btnSaveFinnhub.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}