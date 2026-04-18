package com.example.stockzilla.analyst

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.stockzilla.R
import com.example.stockzilla.data.DEFAULT_GROK_MODEL
import com.example.stockzilla.data.EidosAnalystAuditEventEntity
import com.example.stockzilla.data.EidosAnalystChatMessageEntity
import com.example.stockzilla.data.EidosAnalystConfirmedFactEntity
import com.example.stockzilla.data.EidosAnalystMemoryNoteEntity
import com.example.stockzilla.data.GrokApiClient
import com.example.stockzilla.data.GrokChatMessage
import com.example.stockzilla.data.GrokChatRequest
import com.example.stockzilla.data.GrokChatResponse
import com.example.stockzilla.data.SecEdgarService
import com.example.stockzilla.data.StockRepository
import com.example.stockzilla.data.CompanyProfileAboutWriter
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.ai.AiStockContextBuilder
import com.example.stockzilla.scoring.FinancialHealthAnalyzer
import com.google.gson.Gson
import com.example.stockzilla.feature.ApiKeyManager
import com.example.stockzilla.util.ApiConstants
import com.example.stockzilla.feature.MAX_HISTORY_MESSAGES
import com.example.stockzilla.scoring.StockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Max assistant steps that include tool_calls before we stop chaining.
 * Search → multiple chunks → FS → proposal can burn many steps; ending early leaves proposal uncalled.
 */
private const val ANALYST_TOOL_MAX_ROUNDS = 18

class EidosAnalystViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StockzillaDatabase.getDatabase(application)
    private val dao = database.eidosAnalystChatDao()
    private val analystMemoryDao = database.eidosAnalystMemoryDao()
    private val confirmedFactDao = database.eidosAnalystConfirmedFactDao()
    private val auditDao = database.eidosAnalystAuditDao()
    private val apiKeyManager = ApiKeyManager(application)
    private val grokClient = GrokApiClient(apiKeyProvider = { apiKeyManager.getAiApiKey() })
    private val gson = Gson()
    private val secEdgar = SecEdgarService.getInstance()
    private val financialHealthAnalyzer = FinancialHealthAnalyzer()
    private val stockContextBuilder = AiStockContextBuilder(
        rawFactsDao = database.edgarRawFactsDao(),
        derivedDao = database.financialDerivedMetricsDao(),
        scoreSnapshotDao = database.scoreSnapshotDao(),
        newsSummariesDao = database.newsSummariesDao(),
        financialHealthAnalyzer = financialHealthAnalyzer,
        companyProfileDao = database.companyProfileDao()
    )
    private val analystTools = EidosAnalystToolExecutor(
        secEdgar,
        gson,
        readAppFinancialContext = {
            val ctx = stockContextBuilder.buildContextMap(symbol)
            if (ctx == null) {
                gson.toJson(
                    mapOf(
                        "error" to "No fundamentals in app database for this symbol. Open Full Analysis and refresh fundamentals first.",
                        "symbol" to symbol
                    )
                )
            } else {
                gson.toJson(ctx)
            }
        },
        writeAnalystMemoryNote = { noteText, noteType ->
            val sym = symbol.trim().uppercase()
            if (sym.isNotBlank()) {
                val now = System.currentTimeMillis()
                analystMemoryDao.insert(
                    EidosAnalystMemoryNoteEntity(
                        symbol = sym,
                        noteType = noteType,
                        noteText = noteText,
                        createdAt = now,
                        updatedAt = now,
                        source = "AI_GENERATED"
                    )
                )
            }
        },
        upsertFullAnalysisAbout = { aboutText, replaceExisting ->
            CompanyProfileAboutWriter.upsertJson(
                database.companyProfileDao(),
                gson,
                symbol.trim().uppercase(),
                aboutText,
                replaceExisting
            )
        }
    )

    private fun persistenceStockRepository(): StockRepository = StockRepository(
        apiKey = ApiConstants.DEFAULT_DEMO_KEY,
        finnhubApiKey = apiKeyManager.getFinnhubApiKey(),
        rawFactsDao = database.edgarRawFactsDao(),
        derivedMetricsDao = database.financialDerivedMetricsDao(),
        scoreSnapshotDao = database.scoreSnapshotDao(),
        symbolTagOverrideDao = database.symbolTagOverrideDao(),
        quarterlyFinancialFactDao = database.quarterlyFinancialFactDao(),
        eidosAnalystConfirmedFactDao = confirmedFactDao,
    )

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

    private val _proposal = MutableLiveData<EidosAnalystMetricProposal?>(null)
    val proposal: LiveData<EidosAnalystMetricProposal?> = _proposal

    /** Posted when a filing tool returns CONSENT_REQUIRED — show approve/decline UI. */
    private val _secFilingConsentRequested = MutableLiveData<Unit>()
    val secFilingConsentRequested: LiveData<Unit> = _secFilingConsentRequested

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
            val grokMessages = withContext(Dispatchers.IO) {
                buildGrokMessages(capped)
            }

            val result = withContext(Dispatchers.IO) {
                runAnalystGrokToolLoop(grokMessages)
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

    /**
     * Runs Grok with analyst filing tools until the model returns a final assistant message (or max rounds).
     */
    private suspend fun runAnalystGrokToolLoop(
        initialGrokMessages: List<GrokChatMessage>
    ): Result<GrokChatResponse> {
        var grokMessages = initialGrokMessages.toMutableList()
        var request = GrokChatRequest(
            model = DEFAULT_GROK_MODEL,
            messages = grokMessages,
            temperature = 0.4,
            max_tokens = 4096,
            tools = analystTools.buildAnalystGrokTools(),
            tool_choice = "auto"
        )
        var chatResult = grokClient.sendChat(request)
        var loopCount = 0
        while (chatResult.isSuccess && loopCount < ANALYST_TOOL_MAX_ROUNDS) {
            loopCount++
            val response = chatResult.getOrNull() ?: break
            val message = response.choices?.firstOrNull()?.message ?: break
            val toolCalls = message.tool_calls.orEmpty()
            if (toolCalls.isEmpty()) break
            // Always run tool calls when present so Eidos can chain search → multiple chunks → FS → proposal
            // (do not stop just because the model added brief text alongside tool_calls).
            grokMessages.add(
                GrokChatMessage(
                    role = "assistant",
                    content = message.content,
                    tool_calls = toolCalls
                )
            )
            val allowSec = apiKeyManager.hasSecFilingExtractionConsent()
            val toolResults = analystTools.executeToolCalls(
                cik,
                toolCalls,
                allowSecFilingExtraction = allowSec
            ) { proposal ->
                withContext(Dispatchers.Main) {
                    presentProposal(proposal)
                }
            }
            if (toolResults.any { (_, json) -> json.contains("\"CONSENT_REQUIRED\"") }) {
                _secFilingConsentRequested.postValue(Unit)
            }
            for ((callId, resultContent) in toolResults) {
                grokMessages.add(
                    GrokChatMessage(
                        role = "tool",
                        content = resultContent,
                        tool_call_id = callId
                    )
                )
            }
            request = GrokChatRequest(
                model = DEFAULT_GROK_MODEL,
                messages = grokMessages,
                temperature = 0.3,
                max_tokens = 4096,
                tools = analystTools.buildAnalystGrokTools(),
                tool_choice = "auto"
            )
            chatResult = grokClient.sendChat(request)
        }
        return chatResult
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

    /**
     * Called when Eidos invokes `analyst_present_metric_proposal` with filing-grounded candidates.
     */
    fun presentProposal(proposal: EidosAnalystMetricProposal) {
        if (!bound || !proposalMatchesBoundStock(proposal)) return
        _proposal.value = proposal
    }

    /** Symbol in JSON may be ticker or (IPO) company name matching [companyName] / [issuerName]. */
    private fun proposalMatchesBoundStock(proposal: EidosAnalystMetricProposal): Boolean {
        val ps = proposal.symbol.trim()
        if (ps.equals(symbol, ignoreCase = true)) return true
        companyName?.trim()?.takeIf { it.isNotEmpty() }?.let { cn ->
            if (ps.equals(cn, ignoreCase = true)) return true
        }
        proposal.issuerName?.trim()?.takeIf { it.isNotEmpty() }?.let { iss ->
            if (ps.equals(iss, ignoreCase = true)) return true
            companyName?.trim()?.takeIf { it.isNotEmpty() }?.let { cn ->
                if (iss.equals(cn, ignoreCase = true)) return true
            }
        }
        return false
    }

    fun clearProposal() {
        _proposal.value = null
    }

    /** User closed the sheet (swipe/back) without Accept/Decline. */
    fun onProposalDialogClosed() {
        if (_proposal.value != null) {
            clearProposal()
        }
    }

    /**
     * User approved downloading/reading SEC filing text. Retries the last analyst turn (removes the
     * assistant line that was generated without filing access, then re-runs Grok with consent on).
     */
    fun onSecFilingConsentGranted() {
        if (!bound || symbol.isBlank()) return
        if (_requiresApiKey.value == true) return
        apiKeyManager.setSecFilingExtractionConsent(true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteLastAssistantForSymbol(symbol)
            }
            loadMessages()
            _loading.value = true
            _error.value = null
            val history = _messages.value.orEmpty()
            val capped = if (history.size > MAX_HISTORY_MESSAGES) {
                history.takeLast(MAX_HISTORY_MESSAGES)
            } else {
                history
            }
            val grokMessages = withContext(Dispatchers.IO) {
                buildGrokMessages(capped)
            }
            val result = withContext(Dispatchers.IO) {
                runAnalystGrokToolLoop(grokMessages)
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

    fun acceptProposal(selectionsByLineId: Map<String, String>) {
        if (!bound || symbol.isBlank()) return
        val p = _proposal.value ?: return
        val lines = p.effectiveLines()
        if (selectionsByLineId.size != lines.size) return
        val resolved = ArrayList<Triple<EidosAnalystProposalLine, EidosAnalystMetricCandidate, String>>()
        for (line in lines) {
            val candId = selectionsByLineId[line.lineId] ?: return
            val candidate = line.candidates.firstOrNull { it.id == candId } ?: return
            val embeddedPeriod = EidosAnalystMetricKey.inferPeriodFromMetricKey(line.metricKey)
            val periodLabel = EidosAnalystPeriodScope.canonicalizePeriodLabel(
                if (line.periodLabel.isNullOrBlank()) embeddedPeriod else line.periodLabel
            )
            resolved.add(Triple(line, candidate, periodLabel))
        }
        _proposal.value = null
        val app = getApplication<Application>()
        val chatLine = if (resolved.size == 1) {
            val (line, candidate, _) = resolved[0]
            app.getString(
                R.string.eidos_analyst_proposal_accepted_stub_chat,
                line.metricKey,
                candidate.valueDisplay
            )
        } else {
            val summary = resolved.joinToString("; ") { (line, cand, pl) ->
                val pfx = if (pl.isEmpty()) line.metricKey else "${line.metricKey} ($pl)"
                "$pfx → ${cand.valueDisplay}"
            }
            app.getString(R.string.eidos_analyst_proposal_accepted_batch_stub, resolved.size, summary)
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val filing = p.filing
            val proposalIdPersisted = EidosAnalystMetricProposal.stableProposalId(p.proposalId)
            withContext(Dispatchers.IO) {
                for ((line, candidate, periodLabel) in resolved) {
                    val fact = EidosAnalystConfirmedFactEntity(
                        symbol = symbol,
                        metricKey = EidosAnalystMetricKey.normalize(line.metricKey),
                        periodLabel = periodLabel,
                        valueText = EidosAnalystStockDataMerge.persistedFactValueText(
                            candidate.valueDisplay,
                            candidate.detail
                        ),
                        filingFormType = filing?.formType,
                        accessionNumber = filing?.accessionNumber?.takeIf { it.isNotBlank() },
                        filedDate = filing?.filedDate,
                        viewerUrl = filing?.viewerUrl,
                        primaryDocumentUrl = filing?.primaryDocumentUrl,
                        sourceSnippet = p.sourceSnippet,
                        proposalId = proposalIdPersisted,
                        candidateId = candidate.id,
                        candidateLabel = candidate.label,
                        confirmedAtMs = now,
                    )
                    confirmedFactDao.upsert(fact)
                }
                val audit = EidosAnalystAuditEventEntity(
                    symbol = symbol,
                    eventType = EidosAnalystAuditEventType.PROPOSAL_ACCEPTED,
                    proposalId = proposalIdPersisted,
                    metricKey = resolved.joinToString(",") { it.first.metricKey },
                    candidateId = resolved.joinToString(",") { it.second.id },
                    detail = gson.toJson(
                        resolved.map { (line, cand, pl) ->
                            mapOf(
                                "metricKey" to line.metricKey,
                                "periodLabel" to pl,
                                "valueText" to EidosAnalystStockDataMerge.persistedFactValueText(
                                    cand.valueDisplay,
                                    cand.detail
                                ),
                                "candidateId" to cand.id,
                            )
                        }
                    ),
                    createdAtMs = now,
                )
                auditDao.insert(audit)
                dao.insert(
                    EidosAnalystChatMessageEntity(
                        symbol = symbol,
                        role = "assistant",
                        content = chatLine,
                        timestampMs = now
                    )
                )
                val sym = symbol.trim().uppercase(Locale.US)
                val repo = persistenceStockRepository()
                repo.rePersistFundamentalsAfterAnalystAccept(sym)
                val rawRow = database.edgarRawFactsDao().getBySymbol(sym)
                val derivedRow = database.financialDerivedMetricsDao().getBySymbol(sym)
                if (rawRow != null) {
                    val forScore = rawRow.toStockData(derivedRow).withGrowthFromHistory()
                    val score = financialHealthAnalyzer.calculateCompositeScore(forScore, null)
                    repo.saveScoreSnapshot(sym, score)
                }
            }
            loadMessages()
        }
    }

    fun declineProposal() {
        if (!bound || symbol.isBlank()) return
        val p = _proposal.value ?: return
        _proposal.value = null
        val line = getApplication<Application>().getString(R.string.eidos_analyst_proposal_declined_stub_chat)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val audit = EidosAnalystAuditEventEntity(
                symbol = symbol,
                eventType = EidosAnalystAuditEventType.PROPOSAL_DECLINED,
                proposalId = EidosAnalystMetricProposal.stableProposalId(p.proposalId),
                metricKey = p.effectiveLines().joinToString(",") { it.metricKey },
                candidateId = null,
                detail = null,
                createdAtMs = now,
            )
            val row = EidosAnalystChatMessageEntity(
                symbol = symbol,
                role = "assistant",
                content = line,
                timestampMs = now
            )
            withContext(Dispatchers.IO) {
                auditDao.insert(audit)
                dao.insert(row)
            }
            loadMessages()
        }
    }

    private fun buildAnalystSystemPrompt(memoryNotes: List<EidosAnalystMemoryNoteEntity>): String = buildString {
        append("You are Eidos, a research analyst in Stockzilla.")
        append("The user is analyzing ticker ")
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
        append("Help correct missing or wrong fundamentals using SEC primary filing text; interpret filings and gaps in structured data when asked. ")
        append("For any filing-backed figure the user could accept, you must **invoke** the **analyst_present_metric_proposal** tool with `proposal_json`—a real **tool_calls** entry, not only assistant text. Describing a \"proposal\" or listing candidates in chat does **not** open Accept/Decline and does not persist; optional short chat alongside the tool call is fine. Skip the tool when there is nothing to confirm. ")
        append("Ground numbers you would propose in filing text from your tools (search / chunk / financial-statements)—not guesses from XBRL alone. If the filing does not support a number, say so. ")
        append("The Full Analysis screen has an **About** section for a plain-language company narrative. When the user asks you to draft or save it, call **analyst_set_full_analysis_about** with the final text; use replace_existing true only if they explicitly want to replace existing About content. ")
        
        if (memoryNotes.isNotEmpty()) {
            append("\n\n**Analyst memory cache (this symbol only; separate from main assistant `ai_memory_cache`):**\n")
            for (n in memoryNotes) {
                append("- ")
                val nt = n.noteType.trim()
                if (nt.isNotEmpty() && !nt.equals("FREEFORM", ignoreCase = true)) {
                    append("[$nt] ")
                }
                append(n.noteText.trim())
                append('\n')
            }
        }
    }

    private suspend fun buildGrokMessages(history: List<EidosAnalystChatMessageEntity>): List<GrokChatMessage> {
        val memoryNotes = withContext(Dispatchers.IO) {
            analystMemoryDao.getNotesForSymbol(symbol)
        }
        val out = ArrayList<GrokChatMessage>(history.size + 1)
        out.add(GrokChatMessage(role = "system", content = buildAnalystSystemPrompt(memoryNotes)))
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