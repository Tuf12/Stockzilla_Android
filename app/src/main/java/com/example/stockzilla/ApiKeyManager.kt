// ApiKeyManager.kt - Handles API key storage and validation
package com.example.stockzilla

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ApiKeyManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("stockzilla_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val API_KEY_PREF = "fmp_api_key"
        private const val API_KEY_VALIDATED_PREF = "api_key_validated"
    }

    fun saveApiKey(apiKey: String) {
        sharedPreferences.edit {
            putString(API_KEY_PREF, apiKey)
                .putBoolean(API_KEY_VALIDATED_PREF, false) // Reset validation when new key is saved
        }
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString(API_KEY_PREF, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    fun markApiKeyAsValidated() {
        sharedPreferences.edit {
            putBoolean(API_KEY_VALIDATED_PREF, true)
        }
    }

    fun isApiKeyValidated(): Boolean {
        return sharedPreferences.getBoolean(API_KEY_VALIDATED_PREF, false)
    }

    fun isValidApiKeyFormat(apiKey: String): Boolean {
        // FMP API keys are typically 32 characters long, alphanumeric
        return apiKey.length >= 20 && apiKey.matches(Regex("[a-zA-Z0-9]+"))
    }
}