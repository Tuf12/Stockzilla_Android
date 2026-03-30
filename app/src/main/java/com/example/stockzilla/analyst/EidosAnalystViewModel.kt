package com.example.stockzilla.analyst

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.stockzilla.R
import com.example.stockzilla.data.DEFAULT_GROK_MODEL
import com.example.stockzilla.data.EidosAnalystChatMessageEntity
import com.example.stockzilla.data.GrokApiClient
import com.example.stockzilla.data.GrokChatMessage
import com.example.stockzilla.data.GrokChatRequest
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.feature.ApiKeyManager
import com.example.stockzilla.feature.MAX_HISTORY_MESSAGES
import com.example.stockzilla.scoring.StockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EidosAnalystViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = StockzillaDatabase.Companion.getDatabase(application).eidosAnalystChatDao()
    private val apiKeyManager = ApiKeyManager(application)
    private val grokClient = GrokApiClient(apiKeyProvider = { apiKeyManager.getAiApiKey() })

    private var symbol: String = ""
    private var companyName: String? = null
    private var cik: String? = null
    private var bound = false

    private val _messages = MutableLiveData<List<EidosAnalystChatMessageEntity>>(emptyList())
    val messages: LiveData<List<EidosAnalystChatMessageEntity>> = _messages

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _requiresApiKey = MutableLiveData(!apiKeyManager.hasAiApiKey())
    val requiresApiKey: LiveData<Boolean> = _requiresApiKey

    fun refreshApiKeyState() {
        _requiresApiKey.value = !apiKeyManager.hasAiApiKey()
    }

    /**
     * Call once from the activity with the stock under analysis.
     */
    fun bindStock(stock: StockData) {
        if (bound) return
        bound = true
        symbol = stock.symbol.trim().uppercase()
        companyName = stock.companyName
        cik = stock.cik
        _requiresApiKey.value = !apiKeyManager.hasAiApiKey()
        viewModelScope.launch {
            loadMessages()
        }
    }

    private suspend fun loadMessages() {
        val list = withContext(Dispatchers.IO) {
            dao.getMessagesForSymbol(symbol)
        }
        _messages.value = list
    }

    fun sendMessage(text: String) {
        if (!bound || symbol.isBlank()) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_requiresApiKey.value == true) {
            _error.value = getApplication<Application>().getString(R.string.ai_assistant_missing_key_hint)
            return
        }

        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val now = System.currentTimeMillis()
            val userRow = EidosAnalystChatMessageEntity(
                symbol = symbol,
                role = "user",
                content = trimmed,
                timestampMs = now
            )
            withContext(Dispatchers.IO) {
                dao.insert(userRow)
            }
            loadMessages()

            val history = _messages.value.orEmpty()
            val capped = if (history.size > MAX_HISTORY_MESSAGES) {
                history.takeLast(MAX_HISTORY_MESSAGES)
            } else {
                history
            }
            val grokMessages = buildGrokMessages(capped)

            val result = withContext(Dispatchers.IO) {
                grokClient.sendChat(
                    GrokChatRequest(
                        model = DEFAULT_GROK_MODEL,
                        messages = grokMessages,
                        temperature = 0.4,
                        max_tokens = 4096
                    )
                )
            }

            result.fold(
                onSuccess = { resp ->
                    val assistantText = resp.choices?.firstOrNull()?.message?.content?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: "I wasn't able to generate a response."
                    val assistantRow = EidosAnalystChatMessageEntity(
                        symbol = symbol,
                        role = "assistant",
                        content = assistantText,
                        timestampMs = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        dao.insert(assistantRow)
                    }
                    loadMessages()
                },
                onFailure = { e ->
                    _error.value = e.message ?: "AI request failed."
                }
            )
            _loading.value = false
        }
    }

    fun deleteMessage(id: Long) {
        if (!bound || symbol.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteById(id)
            }
            loadMessages()
        }
    }

    private fun buildAnalystSystemPrompt(): String = buildString {
        append("You are Eidos, acting as a research analyst in Stockzilla. ")
        append("The user is analyzing the public company with ticker ")
        append(symbol)
        append(". ")
        companyName?.takeIf { it.isNotBlank() }?.let { name ->
            append("Company name: ")
            append(name)
            append(". ")
        }
        cik?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
            append("SEC CIK: ")
            append(c)
            append(". ")
        }
        append("Help them interpret SEC filings, fundamentals, and gaps in structured data. ")
        append("Be precise. Do not state specific numbers as if taken from a filing unless you are clearly grounded in filing text the user or tools provided; when unsure, say so. ")
        append("This mode is separate from the app's general Eidos assistant and from the \"Find tag (Eidos)\" company-facts mapping flow. ")
        append("Stay anchored to this symbol while allowing natural discussion.")
    }

    private fun buildGrokMessages(history: List<EidosAnalystChatMessageEntity>): List<GrokChatMessage> {
        val out = ArrayList<GrokChatMessage>(history.size + 1)
        out.add(GrokChatMessage(role = "system", content = buildAnalystSystemPrompt()))
        for (m in history) {
            val role = m.role.lowercase().let { r ->
                when (r) {
                    "user", "assistant" -> r
                    else -> "user"
                }
            }
            out.add(GrokChatMessage(role = role, content = m.content))
        }
        return out
    }
}