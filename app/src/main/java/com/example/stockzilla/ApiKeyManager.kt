// ApiKeyManager.kt - Handles API key storage and validation
package com.example.stockzilla

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ApiKeyManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("stockzilla_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val FINNHUB_API_KEY_PREF = "finnhub_api_key"
        private const val FINNHUB_API_KEY_VALIDATED_PREF = "finnhub_api_key_validated"
    }

    // --- Finnhub API key only (FMP removed) ---
    fun saveFinnhubApiKey(apiKey: String) {
        sharedPreferences.edit {
            putString(FINNHUB_API_KEY_PREF, apiKey)
            putBoolean(FINNHUB_API_KEY_VALIDATED_PREF, false)
        }
    }

    fun getFinnhubApiKey(): String? = sharedPreferences.getString(FINNHUB_API_KEY_PREF, null)

    fun hasFinnhubApiKey(): Boolean = !getFinnhubApiKey().isNullOrBlank()

    fun markFinnhubApiKeyAsValidated() {
        sharedPreferences.edit { putBoolean(FINNHUB_API_KEY_VALIDATED_PREF, true) }
    }

    fun isFinnhubApiKeyValidated(): Boolean =
        sharedPreferences.getBoolean(FINNHUB_API_KEY_VALIDATED_PREF, false)

    fun isValidFinnhubApiKeyFormat(apiKey: String): Boolean {
        return apiKey.length >= 20 && apiKey.matches(Regex("[a-zA-Z0-9]+"))
    }
}