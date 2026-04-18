package com.example.stockzilla.feature

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class ApiKeyManager(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("stockzilla_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val FINNHUB_API_KEY_PREF = "finnhub_api_key"
        private const val FINNHUB_API_KEY_VALIDATED_PREF = "finnhub_api_key_validated"
        private const val AI_API_KEY_PREF = "ai_api_key"
        /** User opted in to letting Eidos download/read SEC filing document text (forms, not just XBRL API). */
        private const val SEC_FILING_EXTRACTION_CONSENT_PREF = "sec_filing_extraction_consent"

        private const val FRED_API_KEY_PREF = "gov_fred_api_key"
        private const val BLS_API_KEY_PREF = "gov_bls_api_key"
        private const val EIA_API_KEY_PREF = "gov_eia_api_key"
        private const val CENSUS_API_KEY_PREF = "gov_census_api_key"
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

    // --- AI API key (Grok/Anthropic/OpenAI-style BYOK) ---
    fun saveAiApiKey(apiKey: String) {
        sharedPreferences.edit {
            putString(AI_API_KEY_PREF, apiKey)
        }
    }

    fun getAiApiKey(): String? = sharedPreferences.getString(AI_API_KEY_PREF, null)

    fun hasAiApiKey(): Boolean = !getAiApiKey().isNullOrBlank()

    /** Whether the user allowed Eidos to fetch SEC EDGAR filing documents (HTML/text) for analysis. Default false. */
    fun hasSecFilingExtractionConsent(): Boolean =
        sharedPreferences.getBoolean(SEC_FILING_EXTRACTION_CONSENT_PREF, false)

    fun setSecFilingExtractionConsent(allowed: Boolean) {
        sharedPreferences.edit { putBoolean(SEC_FILING_EXTRACTION_CONSENT_PREF, allowed) }
    }

    // --- Government data APIs (Phase 7 — GOV_DATA_NEWS) ---
    fun saveFredApiKey(key: String) {
        sharedPreferences.edit {
            val t = key.trim()
            if (t.isEmpty()) remove(FRED_API_KEY_PREF) else putString(FRED_API_KEY_PREF, t)
        }
    }

    fun getFredApiKey(): String? = sharedPreferences.getString(FRED_API_KEY_PREF, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasFredApiKey(): Boolean = !getFredApiKey().isNullOrBlank()

    fun saveBlsApiKey(key: String) {
        sharedPreferences.edit {
            val t = key.trim()
            if (t.isEmpty()) remove(BLS_API_KEY_PREF) else putString(BLS_API_KEY_PREF, t)
        }
    }

    fun getBlsApiKey(): String? = sharedPreferences.getString(BLS_API_KEY_PREF, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasBlsApiKey(): Boolean = !getBlsApiKey().isNullOrBlank()

    fun saveEiaApiKey(key: String) {
        sharedPreferences.edit {
            val t = key.trim()
            if (t.isEmpty()) remove(EIA_API_KEY_PREF) else putString(EIA_API_KEY_PREF, t)
        }
    }

    fun getEiaApiKey(): String? = sharedPreferences.getString(EIA_API_KEY_PREF, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasEiaApiKey(): Boolean = !getEiaApiKey().isNullOrBlank()

    fun saveCensusApiKey(key: String) {
        sharedPreferences.edit {
            val t = key.trim()
            if (t.isEmpty()) remove(CENSUS_API_KEY_PREF) else putString(CENSUS_API_KEY_PREF, t)
        }
    }

    fun getCensusApiKey(): String? = sharedPreferences.getString(CENSUS_API_KEY_PREF, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun hasCensusApiKey(): Boolean = !getCensusApiKey().isNullOrBlank()
}