package com.example.stockzilla.ai

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.stockzilla.data.AiConversationEntity
import com.example.stockzilla.data.AiMemoryCacheEntity
import com.example.stockzilla.data.AiMessageEntity
import com.example.stockzilla.data.DEFAULT_GROK_MODEL
import com.example.stockzilla.data.GrokApiClient
import com.example.stockzilla.data.GrokChatChoiceMessage
import com.example.stockzilla.data.GrokChatMessage
import com.example.stockzilla.data.GrokChatRequest
import com.example.stockzilla.data.GrokChatResponse
import com.example.stockzilla.data.GrokTool
import com.example.stockzilla.data.GrokToolCall
import com.example.stockzilla.data.GrokToolFunction
import com.example.stockzilla.data.NewsRepository
import com.example.stockzilla.data.SecEdgarService
import com.example.stockzilla.data.StockRepository
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.data.SymbolTagOverrideEntity
import com.example.stockzilla.feature.ApiKeyManager
import com.example.stockzilla.feature.DiagnosticsLogger
import com.example.stockzilla.scoring.FinancialHealthAnalyzer
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.sec.EdgarMetricKey
import com.example.stockzilla.sec.EdgarTaxonomy
import com.example.stockzilla.sec.NewsAnalyzer
import com.example.stockzilla.sec.SecFilingMeta
import com.example.stockzilla.sec.TagOverrideResolver
import com.example.stockzilla.util.ApiConstants
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs

private fun Boolean?.isNullOrFalse(): Boolean = this == null || this == false


class AiAssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StockzillaDatabase.Companion.getDatabase(application)
    private val aiConversationDao = database.aiConversationDao()
    private val aiMessageDao = database.aiMessageDao()
    private val rawFactsDao = database.edgarRawFactsDao()
    private val derivedDao = database.financialDerivedMetricsDao()
    private val scoreSnapshotDao = database.scoreSnapshotDao()
    private val aiMemoryCacheDao = database.aiMemoryCacheDao()
    private val favoritesDao = database.favoritesDao()
    private val userStockListDao = database.userStockListDao()
    private val newsSummariesDao = database.newsSummariesDao()
    private val newsMetadataDao = database.newsMetadataDao()
    private val symbolTagOverrideDao = database.symbolTagOverrideDao()

    private val apiKeyManager = ApiKeyManager(application)
    private val secEdgarService = SecEdgarService.Companion.getInstance()
    private val newsRepository by lazy {
        NewsRepository(newsMetadataDao, newsSummariesDao, gson)
    }
    private val newsAnalyzer by lazy {
        NewsAnalyzer(grokClient, gson)
    }
    private val grokClient = GrokApiClient(apiKeyProvider = { apiKeyManager.getAiApiKey() })
    private val gson = Gson()
    private val financialHealthAnalyzer = FinancialHealthAnalyzer()
    private val stockContextBuilder = AiStockContextBuilder(
        rawFactsDao = rawFactsDao,
        derivedDao = derivedDao,
        scoreSnapshotDao = scoreSnapshotDao,
        newsSummariesDao = newsSummariesDao,
        financialHealthAnalyzer = financialHealthAnalyzer
    )
    private val draftsPrefs = application.getSharedPreferences("ai_assistant_drafts", Context.MODE_PRIVATE)
    private val keyLastSelectedConversationId = "last_selected_conversation_id"

    private val stockRepository: StockRepository by lazy {
        StockRepository(
            apiKey = ApiConstants.DEFAULT_DEMO_KEY,
            finnhubApiKey = apiKeyManager.getFinnhubApiKey(),
            rawFactsDao = rawFactsDao,
            derivedMetricsDao = derivedDao,
            scoreSnapshotDao = scoreSnapshotDao,
            symbolTagOverrideDao = symbolTagOverrideDao,
            quarterlyFinancialFactDao = database.quarterlyFinancialFactDao()
        )
    }

    private val _tagFixCompletedStock = MutableLiveData<StockData?>()
    val tagFixCompletedStock: LiveData<StockData?> = _tagFixCompletedStock

    fun clearTagFixCompletedStock() {
        _tagFixCompletedStock.value = null
    }

    private val _conversations = MutableLiveData<List<AiConversationEntity>>(emptyList())
    val conversations: LiveData<List<AiConversationEntity>> = _conversations

    private val _messages = MutableLiveData<List<AiMessageEntity>>(emptyList())
    val messages: LiveData<List<AiMessageEntity>> = _messages

    private val _selectedConversationId = MutableLiveData<Long?>(null)
    val selectedConversationId: LiveData<Long?> = _selectedConversationId

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _requiresApiKey = MutableLiveData<Boolean>(!apiKeyManager.hasAiApiKey())
    val requiresApiKey: LiveData<Boolean> = _requiresApiKey

    /** Fired when Eidos needs user approval before downloading SEC filing document text. */
    private val _secFilingConsentRequested = MutableLiveData<Unit>()
    val secFilingConsentRequested: LiveData<Unit> = _secFilingConsentRequested

    /** SEC filing discovery: retry [saveAndAnalyzeFilings] after the user approves in the dialog. */
    private var pendingDiscoveryRetry: Pair<String, List<String>>? = null

    // Persisted per-conversation draft text so users can leave the chat UI,
    // navigate elsewhere, and return without losing what they were typing.
    private val _draftText = MutableLiveData<String>("")
    val draftText: LiveData<String> = _draftText

    private var initialSymbol: String? = null
    enum class OpenMode {
        /** When a stock symbol is provided, always open that stock's chat. */
        LAST_CHAT,
        /**
         * When no symbol is provided, always open (or create) the pinned "General" conversation.
         * This ensures the main-page Eidos button doesn't jump to the last chat.
         */
        FORCE_GENERAL_IF_NO_SYMBOL
    }

    private var openMode: OpenMode = OpenMode.LAST_CHAT
    private val userScopeKey = "user"

    fun setInitialSymbol(symbol: String?) {
        initialSymbol = symbol?.trim()?.uppercase()
    }

    fun setOpenMode(mode: OpenMode) {
        openMode = mode
    }

    fun loadConversations() {
        viewModelScope.launch {
            try {
                Log.i(
                    "EidosNav",
                    "loadConversations start: openMode=$openMode initialSymbol=$initialSymbol selectedId=${_selectedConversationId.value}"
                )
                val list = aiConversationDao.getAllOrderedByUpdated()
                _conversations.value = list

                // When launched with a specific stock symbol, always open that stock's conversation.
                val symbol = initialSymbol?.takeIf { it.isNotBlank() }
                val targetId = if (symbol != null) {
                    val existingForSymbol = aiConversationDao.getBySymbol(symbol).firstOrNull()
                    if (existingForSymbol != null) {
                        existingForSymbol.id
                    } else {
                        createConversationIfNeeded()
                    }
                } else {
                    // No symbol: either force General or open the last chat used.
                    if (openMode == OpenMode.FORCE_GENERAL_IF_NO_SYMBOL) {
                        createConversationIfNeeded()
                    } else {
                        val lastId = draftsPrefs.getLong(keyLastSelectedConversationId, -1)
                        if (lastId >= 0 && list.any { it.id == lastId }) lastId else createConversationIfNeeded()
                    }
                }

                _selectedConversationId.value = targetId
                persistLastSelectedConversationId(targetId)
                Log.i("EidosNav", "loadConversations selectedId=$targetId")
                loadMessagesForConversation(targetId)
                loadDraftForConversation(targetId)
            } catch (t: Throwable) {
                Log.e("EidosNav", "loadConversations failed", t)
                _error.value = t.message ?: "Failed to load conversations."
            }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            _selectedConversationId.value = null
            _messages.value = emptyList()
            val id = createConversationIfNeeded()
            loadDraftForConversation(id)
        }
    }

    fun selectConversation(id: Long) {
        if (_selectedConversationId.value == id) return
        _selectedConversationId.value = id
        persistLastSelectedConversationId(id)
        viewModelScope.launch {
            loadMessagesForConversation(id)
            loadDraftForConversation(id)
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            val conversationId = _selectedConversationId.value ?: return@launch
            aiMessageDao.deleteById(messageId)
            _messages.value = aiMessageDao.getMessagesForConversation(conversationId)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            aiConversationDao.deleteById(id)
            if (_selectedConversationId.value == id) {
                _selectedConversationId.value = null
                _messages.value = emptyList()
            }
            val list = aiConversationDao.getAllOrderedByUpdated()
            _conversations.value = list
            // Auto-select the first remaining conversation, if any.
            if (list.isNotEmpty() && _selectedConversationId.value == null) {
                val firstId = list.first().id
                _selectedConversationId.value = firstId
                persistLastSelectedConversationId(firstId)
                loadMessagesForConversation(firstId)
                loadDraftForConversation(firstId)
            }
        }
    }

    fun renameConversation(id: Long, newTitle: String) {
        viewModelScope.launch {
            val trimmed = newTitle.trim()
            if (trimmed.isEmpty()) return@launch

            // Do not allow renaming the General chat (symbol == null).
            val convo = aiConversationDao.getById(id) ?: return@launch
            if (convo.symbol.isNullOrBlank()) return@launch

            aiConversationDao.renameConversation(
                id = id,
                title = trimmed,
                updatedAt = System.currentTimeMillis()
            )
            _conversations.value = aiConversationDao.getAllOrderedByUpdated()
        }
    }

    fun updateDraft(text: String) {
        _draftText.value = text
        val id = _selectedConversationId.value
        if (id != null) {
            saveDraftForConversation(id, text)
        }
    }

    private fun saveDraftForConversation(id: Long, text: String) {
        draftsPrefs.edit().putString("draft_$id", text).apply()
    }

    private fun loadDraftForConversation(id: Long) {
        val saved = draftsPrefs.getString("draft_$id", "") ?: ""
        _draftText.value = saved
    }

    private fun persistLastSelectedConversationId(id: Long) {
        draftsPrefs.edit().putLong(keyLastSelectedConversationId, id).apply()
    }

    /**
     * Auto-prompt Eidos to call [set_symbol_tag_override] after Full Analysis "Find tag" flow.
     * Does not read or write [AiMessageEntity] rows — no chat spam; Grok runs as a standalone tool round-trip.
     */
    fun runTagFixBootstrap(
        symbol: String,
        metricKey: String,
        factsIndexJson: String,
        fiscalYear: Int? = null,
        fiscalPeriod: String? = null,
        periodEnd: String? = null,
        accessionNumber: String? = null,
        filingViewerUrl: String? = null,
        cik: String? = null,
        suggestedScopeKey: String? = null
    ) {
        if (!_requiresApiKey.value.isNullOrFalse()) {
            DiagnosticsLogger.log(
                symbol.trim().uppercase(),
                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_BOOTSTRAP_SKIP",
                "API key missing — Eidos tag fix not started"
            )
            _error.value = "Add an AI API key in settings to use the assistant."
            return
        }
        val symU = symbol.trim().uppercase()
        DiagnosticsLogger.log(
            symU,
            "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_BOOTSTRAP_START",
            "metricKey=$metricKey — sending auto-prompt to Eidos (set_symbol_tag_override)",
            buildString {
                append("fy=").append(fiscalYear?.toString() ?: "null")
                append(" fp=").append(fiscalPeriod ?: "null")
                append(" periodEnd=").append(periodEnd ?: "null")
                append(" scopeKey=").append(suggestedScopeKey ?: "")
                append(" accession=").append(accessionNumber ?: "")
                append(" cik=").append(cik ?: "")
                append(" factsIndexChars=").append(factsIndexJson.length)
            }
        )
        val prompt = buildString {
            append("Stockzilla could not extract metric ")
            append(metricKey)
            append(" for ")
            append(symbol)
            append(" using standard XBRL tags. ")
            append("You MUST call set_symbol_tag_override with: symbol=\"")
            append(symbol)
            append("\", metricKey=\"")
            append(metricKey)
            append("\", taxonomy= one of us-gaap | ifrs-full | dei, tag= the local XBRL concept name. ")
            append("If the same tag works for all quarters, omit scopeKey or use scopeKey=\"\". ")
            append("If only one fiscal quarter needs a different tag, set scopeKey to FYyyyy:Qn (e.g. FY2024:Q2).\n")
            if (fiscalYear != null || fiscalPeriod != null || periodEnd != null) {
                append("Context: fiscalYear=")
                append(fiscalYear?.toString() ?: "null")
                append(", fiscalPeriod=")
                append(fiscalPeriod ?: "null")
                append(", periodEnd=")
                append(periodEnd ?: "null")
                append('\n')
            }
            val filingParts = buildList {
                if (!cik.isNullOrBlank()) add("cik=$cik")
                if (!accessionNumber.isNullOrBlank()) add("accession=$accessionNumber")
                if (!filingViewerUrl.isNullOrBlank()) add("filingViewer=$filingViewerUrl")
            }
            if (filingParts.isNotEmpty()) {
                append("Filing (single SEC filing reference, not tag lists): ")
                append(filingParts.joinToString(", "))
                append('\n')
            }
            if (!suggestedScopeKey.isNullOrBlank()) {
                append("Suggested scopeKey for this cell: ")
                append(suggestedScopeKey)
                append('\n')
            }
            append("Pick the consolidated filing total for that metric, not a segment.\n")
        }
        val fullUserContentForApi = buildString {
            append(prompt)
            append("\nCompanyfacts local tag names:\n")
            append(factsIndexJson)
        }
        runTagFixGrokWithoutChat(fullUserContentForApi, symbol.trim().uppercase())
    }

    /**
     * Runs Grok + tools for tag override only. Does not read or write chat messages.
     */
    private fun runTagFixGrokWithoutChat(fullUserContent: String, contextSymbol: String) {
        viewModelScope.launch {
            if (!_requiresApiKey.value.isNullOrFalse()) {
                _error.value = "Add an AI API key in settings to use the assistant."
                return@launch
            }
            _loading.value = true
            _error.value = null
            try {
                val contextJson = buildContextPacket(contextSymbol)
                val grokMessages = buildStandaloneGrokMessages(contextJson, fullUserContent)
                val outcome = runGrokToolLoop(grokMessages, contextSymbol)
                outcome.chatResult.fold(
                    onSuccess = { },
                    onFailure = { e -> _error.value = e.message ?: "AI request failed." }
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun sendMessage(text: String) {
        if (!_requiresApiKey.value.isNullOrFalse()) {
            _error.value = "Add an AI API key in settings to use the assistant."
            return
        }

        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            // Clear the draft for the active conversation now that the message is being sent.
            updateDraft("")

            val conversationId = createConversationIfNeeded()
            val conversation = aiConversationDao.getById(conversationId)
            // Important: keep a stable title for the General conversation.
            // General has symbol == null/blank, but `initialSymbol` can be a stock ticker; we must not let that rename General.
            val conversationSymbol = conversation?.symbol?.takeIf { it.isNotBlank() }
            val contextSymbol = conversationSymbol ?: initialSymbol?.takeIf { it.isNotBlank() }

            val now = System.currentTimeMillis()
            val userMessageEntity = AiMessageEntity(
                conversationId = conversationId,
                role = "user",
                content = text,
                timestamp = now
            )
            aiMessageDao.insert(userMessageEntity)

            val contextJson = buildContextPacket(contextSymbol)
            val history = aiMessageDao.getMessagesForConversation(conversationId)
            val initialGrokMessages = buildGrokMessages(history, contextJson)
            val outcome = runGrokToolLoop(initialGrokMessages, contextSymbol)
            val chatResult = outcome.chatResult
            val lastResponse = outcome.lastResponse
            val lastDiscoveryResult = outcome.lastDiscoveryResult

            chatResult.fold(
                onSuccess = {
                    var assistantText = lastResponse?.content
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: "I wasn't able to generate a response."

                    // If there was a discovery result with candidates, append it to the message
                    // so the UI can render the discovery card
                    if (lastDiscoveryResult != null) {
                        assistantText = "$assistantText\n\n$lastDiscoveryResult"
                    }

                    val assistantEntity = AiMessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = assistantText,
                        timestamp = System.currentTimeMillis()
                    )
                    aiMessageDao.insert(assistantEntity)
                    aiConversationDao.renameConversation(
                        id = conversationId,
                        title = buildConversationTitle(conversationSymbol, text),
                        updatedAt = System.currentTimeMillis()
                    )
                    _messages.value = aiMessageDao.getMessagesForConversation(conversationId)
                    _conversations.value = aiConversationDao.getAllOrderedByUpdated()
                },
                onFailure = { e ->
                    _error.value = e.message ?: "AI request failed."
                }
            )

            _loading.value = false
        }
    }

    private suspend fun createConversationIfNeeded(): Long {
        // If we have an initial symbol, reuse any existing conversation for that symbol to
        // guarantee exactly one conversation per stock.
        val symbol = initialSymbol?.takeIf { it.isNotBlank() }
        if (symbol != null) {
            val existingForSymbol = aiConversationDao.getBySymbol(symbol).firstOrNull()
            if (existingForSymbol != null) {
                Log.i("EidosNav", "createConversationIfNeeded: reuse stock=$symbol id=${existingForSymbol.id}")
                _selectedConversationId.value = existingForSymbol.id
                persistLastSelectedConversationId(existingForSymbol.id)
                // Ensure the drawer list reflects the selected conversation (it may have been empty initially).
                _conversations.value = aiConversationDao.getAllOrderedByUpdated()
                return existingForSymbol.id
            }
            Log.i("EidosNav", "createConversationIfNeeded: creating stock=$symbol conversation")
        } else {
            // No stock symbol in context: treat this as the single persistent General chat.
            val existingGeneral = aiConversationDao.getGeneralConversations().firstOrNull()
            if (existingGeneral != null) {
                Log.i("EidosNav", "createConversationIfNeeded: reuse General id=${existingGeneral.id}")
                _selectedConversationId.value = existingGeneral.id
                persistLastSelectedConversationId(existingGeneral.id)
                // Ensure the drawer list reflects General immediately, even if the first load was empty.
                _conversations.value = aiConversationDao.getAllOrderedByUpdated()
                return existingGeneral.id
            }
            Log.i("EidosNav", "createConversationIfNeeded: creating General conversation")
        }

        val now = System.currentTimeMillis()
        val title = buildConversationTitle(initialSymbol, null)
        val entity = AiConversationEntity(
            symbol = initialSymbol,
            title = title,
            createdAt = now,
            updatedAt = now
        )
        val id = aiConversationDao.insert(entity)
        Log.i("EidosNav", "createConversationIfNeeded: inserted id=$id title=$title symbol=$initialSymbol")
        _selectedConversationId.value = id
        persistLastSelectedConversationId(id)
        _conversations.value = aiConversationDao.getAllOrderedByUpdated()
        _messages.value = emptyList()
        return id
    }

    private suspend fun loadMessagesForConversation(id: Long) {
        val messages = aiMessageDao.getMessagesForConversation(id)
        Log.i(
            "EidosNav",
            "loadMessagesForConversation id=$id messageCount=${messages.size}"
        )
        _messages.value = messages
    }

    /**
     * Loads full stock context for a symbol from DB (raw, derived, snapshot, stockData, healthScore).
     * Returns null if the symbol is not in the DB. Used by get_stock_data tool and by primary context.
     */
    private suspend fun buildOneStockContext(symbol: String): Map<String, Any?>? =
        stockContextBuilder.buildContextMap(symbol)

    private suspend fun buildContextPacket(symbol: String?): String? {
        // Load Memory Cache notes first so they are always available,
        // even when no specific stock symbol is in context (e.g. General chat).
        val userNotes = aiMemoryCacheDao
            .getNotesForScope(scope = "USER", scopeKey = userScopeKey)
            .map {
                mapOf(
                    "noteType" to it.noteType,
                    "noteText" to it.noteText,
                    "updatedAt" to it.updatedAt
                )
            }

        val stockSymbol = symbol?.takeIf { it.isNotBlank() }
        val stockNotes = if (stockSymbol != null) {
            aiMemoryCacheDao
                .getNotesForScope(scope = "STOCK", scopeKey = stockSymbol)
                .map {
                    mapOf(
                        "noteType" to it.noteType,
                        "noteText" to it.noteText,
                        "updatedAt" to it.updatedAt
                    )
                }
        } else {
            emptyList()
        }

        // Group-level notes are stubbed for now; wired once peer-group IDs are available.
        val groupNotes = emptyList<Map<String, Any?>>()

        val memorySection = mapOf(
            "userNotes" to userNotes,
            "stockNotes" to stockNotes,
            "groupNotes" to groupNotes
        )

        // General chat: memory only. Eidos uses tools (get_stock_data, get_portfolio_overview, etc.) for any symbol or list it needs.
        if (stockSymbol == null) {
            val baseContext = mutableMapOf<String, Any?>(
                "memory" to memorySection
            )
            return gson.toJson(baseContext as Map<String, Any?>)
        }

        val contextMap = buildOneStockContext(stockSymbol)
        if (contextMap == null) {
            val baseContext = mutableMapOf<String, Any?>(
                "symbol" to stockSymbol,
                "memory" to memorySection
            )
            return gson.toJson(baseContext as Map<String, Any?>)
        }
        val context = contextMap.toMutableMap()
        context["memory"] = memorySection

        return gson.toJson(context as Map<String, Any?>)
    }

    private fun buildEidosTools(): List<GrokTool> {
        val memoryParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "scope" to mapOf(
                    "type" to "string",
                    "enum" to listOf("USER", "STOCK", "GROUP")
                ),
                "scopeKey" to mapOf(
                    "type" to "string",
                    "description" to "Symbol for STOCK, groupId for GROUP, or \"user\" for USER"
                ),
                "noteText" to mapOf(
                    "type" to "string",
                    "description" to "The concise memory note text"
                )
            ),
            "required" to listOf("scope", "scopeKey", "noteText")
        )

        val writeMemoryFn = GrokToolFunction(
            name = "write_memory_note",
            description = "Save a long-term memory note about the user, a specific stock, or a peer group.",
            parameters = memoryParams
        )

        val getStockDataParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol" to mapOf(
                    "type" to "string",
                    "description" to "Stock ticker symbol (e.g. AAPL, MSFT)"
                ),
                "fetch_if_missing" to mapOf(
                    "type" to "boolean",
                    "description" to "If true and symbol is not in the database, fetch from SEC EDGAR and save; default true"
                )
            ),
            "required" to listOf("symbol")
        )

        val getStockDataFn = GrokToolFunction(
            name = "get_stock_data",
            description = "Return full app database context for a stock symbol (raw facts, derived metrics, score snapshot, health score). If the symbol is not in the database and fetch_if_missing is true, fetch from SEC EDGAR, save to the database, then return the new data.",
            parameters = getStockDataParams
        )

        val portfolioParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>()
        )

        val getPortfolioOverviewFn = GrokToolFunction(
            name = "get_portfolio_overview",
            description = "Return the user's full portfolio overview: holdings with shares, average cost, current price, market value, cost basis, and gain/loss.",
            parameters = portfolioParams
        )

        val getWatchlistFn = GrokToolFunction(
            name = "get_watchlist",
            description = "Return the user's watchlist as a list of symbols (and any available light metadata).",
            parameters = portfolioParams
        )

        val getFavoritesFn = GrokToolFunction(
            name = "get_favorites",
            description = "Return the user's favorited stocks as a list of symbols (and any available light metadata).",
            parameters = portfolioParams
        )

        val listAnalyzedStocksFn = GrokToolFunction(
            name = "list_analyzed_stocks",
            description = "Return the list of stocks that have full fundamentals in the app database (all symbols in edgar_raw_facts), with basic identity metadata.",
            parameters = portfolioParams
        )

        // SEC Form Discovery Tools
        val secSearchFilingsParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol" to mapOf(
                    "type" to "string",
                    "description" to "Stock ticker symbol (e.g. AAPL, MSFT)"
                ),
                "formTypes" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Optional list of form types to filter by (e.g. ['4', '13F-HR', '8-K', 'SC 13D', 'S-1']). If not provided, searches all news-form types."
                ),
                "lookbackDays" to mapOf(
                    "type" to "integer",
                    "description" to "Number of days to look back for filings. Default is 90 days. Can be increased up to 365 for older filings."
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "Maximum number of filings to return. Default is 20. Can be up to 50."
                )
            ),
            "required" to listOf("symbol")
        )

        val secSearchFilingsFn = GrokToolFunction(
            name = "sec_search_filings",
            description = "Search SEC EDGAR for filings (Form 4, 13F, 8-K, etc.) for a given stock symbol. Returns up to 20 candidate filings from the last 90 days (by default), excluding those already in the app's database. This is read-only and makes no database changes. User can request older filings by increasing lookbackDays.",
            parameters = secSearchFilingsParams
        )

        val secSaveFilingsParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol" to mapOf(
                    "type" to "string",
                    "description" to "Stock ticker symbol"
                ),
                "accessionNumbers" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "List of accession numbers to save (from sec_search_filings results)"
                )
            ),
            "required" to listOf("symbol", "accessionNumbers")
        )

        val secSaveFilingsFn = GrokToolFunction(
            name = "sec_save_filings_metadata",
            description = "Save selected SEC filing metadata to the app's database (requires user approval). Sets analysisStatus=PENDING. Only saves filings that are not already in the database.",
            parameters = secSaveFilingsParams
        )

        val secAnalyzeFilingsParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol" to mapOf(
                    "type" to "string",
                    "description" to "Stock ticker symbol"
                ),
                "accessionNumbers" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "List of accession numbers to analyze (must be already saved with PENDING status)"
                )
            ),
            "required" to listOf("symbol", "accessionNumbers")
        )

        val secAnalyzeFilingsFn = GrokToolFunction(
            name = "sec_analyze_saved_filings",
            description = "Run AI analysis on saved SEC filings. Fetches document content, analyzes with Eidos, and saves results to news_summaries. Updates analysisStatus to COMPLETED.",
            parameters = secAnalyzeFilingsParams
        )

        val setTagOverrideParams: Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "symbol" to mapOf("type" to "string", "description" to "Ticker symbol, e.g. AAPL"),
                "metricKey" to mapOf(
                    "type" to "string",
                    "description" to "One of: ${EdgarMetricKey.entries.joinToString { it.name }}"
                ),
                "taxonomy" to mapOf(
                    "type" to "string",
                    "description" to "facts subtree key: us-gaap, ifrs-full, or dei",
                    "enum" to listOf(EdgarTaxonomy.US_GAAP, EdgarTaxonomy.IFRS_FULL, EdgarTaxonomy.DEI)
                ),
                "tag" to mapOf("type" to "string", "description" to "Local XBRL concept name in that taxonomy"),
                "scopeKey" to mapOf(
                    "type" to "string",
                    "description" to "Empty string = all periods. Quarterly scope: FY2024:Q2 (fiscal year + quarter). Use when the tag differs by quarter."
                )
            ),
            "required" to listOf("symbol", "metricKey", "taxonomy", "tag")
        )
        val setTagOverrideFn = GrokToolFunction(
            name = "set_symbol_tag_override",
            description = "Save a per-symbol XBRL tag mapping when standard tags failed (evolving tagging). Optional scopeKey for quarter-specific tags. Persists to Room and refreshes fundamentals from SEC.",
            parameters = setTagOverrideParams
        )

        return listOf(
            GrokTool(function = writeMemoryFn),
            GrokTool(function = getStockDataFn),
            GrokTool(function = getPortfolioOverviewFn),
            GrokTool(function = getWatchlistFn),
            GrokTool(function = getFavoritesFn),
            GrokTool(function = listAnalyzedStocksFn),
            GrokTool(function = secSearchFilingsFn),
            GrokTool(function = secSaveFilingsFn),
            GrokTool(function = secAnalyzeFilingsFn),
            GrokTool(function = setTagOverrideFn)
        )
    }

    /**
     * Runs the get_stock_data tool: returns full stock context JSON from DB, or fetches and persists
     * when fetchIfMissing is true. Does not change the selected conversation or UI.
     */
    private suspend fun getStockDataForTool(symbol: String, fetchIfMissing: Boolean): String {
        val ctx = buildOneStockContext(symbol)
        if (ctx != null) return gson.toJson(ctx)
        if (!fetchIfMissing) {
            return "Symbol $symbol not in database. Call get_stock_data with fetch_if_missing: true to add it."
        }
        val result = stockRepository.getStockData(symbol, forceFromEdgar = true)
        return result.fold(
            onSuccess = { stockData ->
                val dataForScoring = stockData.withGrowthFromHistory()
                val score = financialHealthAnalyzer.calculateCompositeScore(dataForScoring)
                stockRepository.saveScoreSnapshot(symbol, score)
                val sicCode = stockRepository.getSicCode(symbol)
                val naicsCode = stockRepository.getNaicsCode(symbol)
                val lastFilingDate = stockRepository.getLatestFilingDate(symbol)
                stockRepository.saveAnalyzedStock(dataForScoring, sicCode, naicsCode, lastFilingDate)
                val ctx2 = buildOneStockContext(symbol)
                if (ctx2 != null) gson.toJson(ctx2) else "Saved but failed to reload context."
            },
            onFailure = { "Failed to add symbol: ${it.message}" }
        )
    }

    private suspend fun getPortfolioOverviewForTool(): String {
        val holdings = userStockListDao.getAllHoldings()
        val watchlist = userStockListDao.getAllWatchlist()
        val favorites = favoritesDao.getAllFavorites()

        val holdingSymbols = holdings.map { it.symbol }.distinct()
        val priceRows = if (holdingSymbols.isNotEmpty()) {
            derivedDao.getPricesBySymbols(holdingSymbols)
        } else {
            emptyList()
        }
        val priceBySymbol = priceRows.associateBy { it.symbol }

        val holdingsSummary = holdings.map { item ->
            val shares = item.shares
            val avgCost = item.avgCost
            val price = priceBySymbol[item.symbol]?.price
            val marketValue = if (price != null && shares != null) price * shares else null
            val costBasis = if (avgCost != null && shares != null) avgCost * shares else null
            val gainPct = if (marketValue != null && costBasis != null && abs(costBasis) > 1e-9) {
                (marketValue - costBasis) / costBasis
            } else {
                null
            }

            mapOf(
                "symbol" to item.symbol,
                "shares" to shares,
                "avgCost" to avgCost,
                "price" to price,
                "marketValue" to marketValue,
                "costBasis" to costBasis,
                "gainPct" to gainPct
            )
        }

        val watchlistSymbols = watchlist.map { it.symbol }
        val favoriteSymbols = favorites.map { it.symbol }

        val payload = mapOf(
            "holdings" to holdingsSummary,
            "watchlist" to watchlistSymbols,
            "favorites" to favoriteSymbols
        )

        return gson.toJson(payload)
    }

    private suspend fun getWatchlistForTool(): String {
        val watchlist = userStockListDao.getAllWatchlist()
        val symbols = watchlist.map { it.symbol }
        return gson.toJson(symbols)
    }

    private suspend fun getFavoritesForTool(): String {
        val favorites = favoritesDao.getAllFavorites()
        val symbols = favorites.map { it.symbol }
        return gson.toJson(symbols)
    }

    private suspend fun listAnalyzedStocksForTool(): String {
        val rows = rawFactsDao.getViewedStocksOrderByLastUpdated()
        return gson.toJson(rows)
    }

    /**
     * Executes tool calls (e.g. write_memory_note, get_stock_data), persists to DB, and returns one result string per
     * tool call for the API follow-up request. Order matches [toolCalls]; id is from each [GrokToolCall].
     * [currentContextSymbol] is the symbol for the active conversation (if any), used when the model
     * writes a STOCK note without an explicit scopeKey. get_stock_data does not change the selected conversation.
     */
    private suspend fun handleToolCalls(
        toolCalls: List<GrokToolCall>,
        currentContextSymbol: String? = null
    ): List<Pair<String, String>> {
        val nowTs = System.currentTimeMillis()
        val symbol = currentContextSymbol?.takeIf { it.isNotBlank() } ?: initialSymbol?.takeIf { it.isNotBlank() }
        val entities = mutableListOf<AiMemoryCacheEntity>()
        val results = mutableListOf<Pair<String, String>>()

        for (call in toolCalls) {
            val callId = call.id ?: "unknown"
            val fn = call.function

            when (fn.name) {
                "get_stock_data" -> {
                    val args = try {
                        gson.fromJson(fn.arguments, GetStockDataArgs::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    val sym = args?.symbol?.trim()?.uppercase()
                    if (sym.isNullOrBlank()) {
                        results.add(callId to "get_stock_data requires a non-empty symbol.")
                        continue
                    }
                    val fetchIfMissing = args?.fetch_if_missing != false
                    val resultJson = getStockDataForTool(sym, fetchIfMissing)
                    results.add(callId to resultJson)
                    continue
                }
                "get_portfolio_overview" -> {
                    val json = getPortfolioOverviewForTool()
                    results.add(callId to json)
                    continue
                }
                "get_watchlist" -> {
                    val json = getWatchlistForTool()
                    results.add(callId to json)
                    continue
                }
                "get_favorites" -> {
                    val json = getFavoritesForTool()
                    results.add(callId to json)
                    continue
                }
                "list_analyzed_stocks" -> {
                    val json = listAnalyzedStocksForTool()
                    results.add(callId to json)
                    continue
                }
                "sec_search_filings" -> {
                    val args = try {
                        gson.fromJson(fn.arguments, SecSearchFilingsArgs::class.java)
                    } catch (e: Exception) { null }
                    val sym = args?.symbol?.trim()?.uppercase()
                    if (sym.isNullOrBlank()) {
                        results.add(callId to "sec_search_filings requires a non-empty symbol.")
                        continue
                    }
                    val resultJson = secSearchFilingsForTool(sym, args.formTypes, args.lookbackDays, args.limit)
                    results.add(callId to resultJson)
                    continue
                }
                "sec_save_filings_metadata" -> {
                    val args = try {
                        gson.fromJson(fn.arguments, SecSaveFilingsArgs::class.java)
                    } catch (e: Exception) { null }
                    val sym = args?.symbol?.trim()?.uppercase()
                    if (sym.isNullOrBlank()) {
                        results.add(callId to "sec_save_filings_metadata requires a non-empty symbol.")
                        continue
                    }
                    if (args.accessionNumbers.isNullOrEmpty()) {
                        results.add(callId to "sec_save_filings_metadata requires at least one accession number.")
                        continue
                    }
                    val resultJson = secSaveFilingsMetadataForTool(sym, args.accessionNumbers)
                    results.add(callId to resultJson)
                    continue
                }
                "sec_analyze_saved_filings" -> {
                    if (!apiKeyManager.hasSecFilingExtractionConsent()) {
                        results.add(callId to secFilingExtractionConsentRequiredJson())
                        _secFilingConsentRequested.postValue(Unit)
                        continue
                    }
                    val args = try {
                        gson.fromJson(fn.arguments, SecAnalyzeFilingsArgs::class.java)
                    } catch (e: Exception) { null }
                    val sym = args?.symbol?.trim()?.uppercase()
                    if (sym.isNullOrBlank()) {
                        results.add(callId to "sec_analyze_saved_filings requires a non-empty symbol.")
                        continue
                    }
                    if (args?.accessionNumbers.isNullOrEmpty()) {
                        results.add(callId to "sec_analyze_saved_filings requires at least one accession number.")
                        continue
                    }
                    val resultJson = secAnalyzeSavedFilingsForTool(sym, args!!.accessionNumbers!!)
                    results.add(callId to resultJson)
                    continue
                }
                "set_symbol_tag_override" -> {
                    val rawArgsTrunc = fn.arguments?.take(900)?.replace("\n", " ") ?: ""
                    DiagnosticsLogger.log(
                        symbol,
                        "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_TOOL_CALL",
                        "Eidos invoked set_symbol_tag_override",
                        rawArgsTrunc
                    )
                    val args = try {
                        gson.fromJson(fn.arguments, SetSymbolTagOverrideArgs::class.java)
                    } catch (_: Exception) {
                        null
                    }
                    val sym = args?.symbol?.trim()?.uppercase()
                    val mk = args?.metricKey?.trim()
                    val tax = args?.taxonomy?.trim()
                    val tag = args?.tag?.trim()
                    if (sym.isNullOrBlank() || mk.isNullOrBlank() || tax.isNullOrBlank() || tag.isNullOrBlank()) {
                        DiagnosticsLogger.log(
                            sym,
                            "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_OVERRIDE_REJECT",
                            "Missing symbol, metricKey, taxonomy, or tag in tool args",
                            rawArgsTrunc.take(400)
                        )
                        results.add(callId to """{"ok":false,"error":"Missing symbol, metricKey, taxonomy, or tag"}""")
                        continue
                    }
                    val key = EdgarMetricKey.fromStorage(mk)
                    if (key == null) {
                        DiagnosticsLogger.log(
                            sym,
                            "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_OVERRIDE_REJECT",
                            "Unknown metricKey from Eidos: $mk",
                            "valid=${EdgarMetricKey.entries.joinToString { it.name }}"
                        )
                        results.add(
                            callId to """{"ok":false,"error":"Unknown metricKey. Use: ${EdgarMetricKey.entries.joinToString { it.name }}"}"""
                        )
                        continue
                    }
                    if (tax !in setOf(EdgarTaxonomy.US_GAAP, EdgarTaxonomy.IFRS_FULL, EdgarTaxonomy.DEI)) {
                        DiagnosticsLogger.log(
                            sym,
                            "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_OVERRIDE_REJECT",
                            "Invalid taxonomy from Eidos: $tax",
                            "must be us-gaap, ifrs-full, or dei"
                        )
                        results.add(callId to """{"ok":false,"error":"taxonomy must be us-gaap, ifrs-full, or dei"}""")
                        continue
                    }
                    val scopeKey = args?.scopeKey?.trim().orEmpty()
                    if (!TagOverrideResolver.isValidScopeKey(scopeKey)) {
                        DiagnosticsLogger.log(
                            sym,
                            "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_OVERRIDE_REJECT",
                            "Invalid scopeKey from Eidos: \"$scopeKey\"",
                            "use empty or FYyyyy:Qn"
                        )
                        results.add(
                            callId to """{"ok":false,"error":"scopeKey must be empty or like FY2024:Q2"}"""
                        )
                        continue
                    }
                    symbolTagOverrideDao.upsert(
                        SymbolTagOverrideEntity(
                            symbol = sym,
                            metricKey = key.name,
                            scopeKey = scopeKey,
                            taxonomy = tax,
                            tag = tag,
                            updatedAt = System.currentTimeMillis(),
                            source = "eidos"
                        )
                    )
                    DiagnosticsLogger.log(
                        symbol = sym,
                        category = "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_OVERRIDE_OK",
                        message = "Room upsert done; refreshing fundamentals from EDGAR",
                        detail = "metricKey=$mk scopeKey=$scopeKey taxonomy=$tax tag=$tag"
                    )
                    val refresh = stockRepository.getStockData(sym, forceFromEdgar = true)
                    val payload = refresh.fold(
                        onSuccess = { stockData ->
                            val dataForScoring = stockData.withGrowthFromHistory()
                            val score = financialHealthAnalyzer.calculateCompositeScore(dataForScoring)
                            stockRepository.saveScoreSnapshot(sym, score)
                            val sicCode = stockRepository.getSicCode(sym)
                            val naicsCode = stockRepository.getNaicsCode(sym)
                            val lastFilingDate = stockRepository.getLatestFilingDate(sym)
                            stockRepository.saveAnalyzedStock(dataForScoring, sicCode, naicsCode, lastFilingDate)
                            DiagnosticsLogger.log(
                                sym,
                                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_REFRESH_OK",
                                "Fundamentals refreshed after tag override",
                                "revenue=${stockData.revenue} revenueTtm=${stockData.revenueTtm}"
                            )
                            // Main thread so Full Analysis observers run before sendMessage clears loading.
                            withContext(Dispatchers.Main.immediate) {
                                _tagFixCompletedStock.value = stockData
                            }
                            """{"ok":true,"message":"Override saved and fundamentals refreshed."}"""
                        },
                        onFailure = { e ->
                            DiagnosticsLogger.log(
                                sym,
                                "${DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX}_REFRESH_FAIL",
                                "EDGAR refresh failed after override saved",
                                e.message ?: "unknown"
                            )
                            """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}"""
                        }
                    )
                    results.add(callId to payload)
                    continue
                }
                "write_memory_note" -> { /* fall through to existing logic */ }
                else -> {
                    results.add(callId to "Unknown tool.")
                    continue
                }
            }

            val args = try {
                gson.fromJson(fn.arguments, WriteMemoryNoteArgs::class.java)
            } catch (e: Exception) {
                null
            }
            if (args == null) {
                results.add(callId to "Invalid arguments.")
                continue
            }

            val rawScope = args.scope?.uppercase() ?: if (symbol != null) "STOCK" else "USER"
            val scopeKeyResult: String?
            val scopeKeyError: String?
            when (rawScope) {
                "STOCK" -> {
                    scopeKeyResult = args.scopeKey?.takeIf { it.isNotBlank() } ?: symbol
                    scopeKeyError = if (scopeKeyResult == null) "Missing scopeKey for STOCK." else null
                }
                "USER" -> {
                    scopeKeyResult = args.scopeKey?.takeIf { it.isNotBlank() } ?: userScopeKey
                    scopeKeyError = null
                }
                "GROUP" -> {
                    scopeKeyResult = args.scopeKey?.takeIf { it.isNotBlank() }
                    scopeKeyError = if (scopeKeyResult == null) "Missing scopeKey for GROUP." else null
                }
                else -> {
                    scopeKeyResult = null
                    scopeKeyError = "Invalid scope."
                }
            }
            if (scopeKeyError != null) {
                results.add(callId to scopeKeyError)
                continue
            }
            val scopeKey = scopeKeyResult!!

            val noteText = args.noteText?.trim().orEmpty()
            if (noteText.isBlank()) {
                results.add(callId to "Empty note text.")
                continue
            }

            val noteType = "FREEFORM"
            entities.add(
                AiMemoryCacheEntity(
                    scope = rawScope,
                    scopeKey = scopeKey,
                    noteType = noteType,
                    noteText = noteText,
                    createdAt = nowTs,
                    updatedAt = nowTs,
                    source = "AI_GENERATED"
                )
            )
            results.add(callId to "Saved.")
        }

        if (entities.isNotEmpty()) {
            aiMemoryCacheDao.upsertAll(entities)
        }
        return results
    }

    private data class GrokToolLoopOutcome(
        val lastResponse: GrokChatChoiceMessage?,
        val lastDiscoveryResult: String?,
        val chatResult: Result<GrokChatResponse>
    )

    private fun eidosSystemPrompt(): String = """
            You are Eidos, the stock research assistant inside Stockzilla.
                        
            Formatting rules: 
            - Markdown Tables are Very difficult to read, use other formatting for tables. 
            
            Tool Call Options:
            - Memory Cache: write_memory_note(scope, scopeKey, noteText)
            - Stock Data: get_stock_data(symbol, fetch_if_missing)
            - Portfolio Overview: get_portfolio_overview()
            - Watchlist: get_watchlist()
            - Favorites: get_favorites()
            - List Analyzed Stocks: list_analyzed_stocks()
            - SEC Form Discovery: sec_search_filings(symbol, formTypes?, lookbackDays?, limit?)
            - SEC Save Filings: sec_save_filings_metadata(symbol, accessionNumbers)
            - SEC Analyze Filings: sec_analyze_saved_filings(symbol, accessionNumbers)                   
                        
            Memory Cache:
            - You have a long-term Memory Cache stored in the app’s database with three scopes: USER, STOCK, and GROUP.            
            - The memory cache is your baby you have the function tools to write to it silently and automatically use this to better know and understand the user, stocks and groups build it into something great!
            
            Data and behavior:
            - You have access to the app's database which can provide you financial metrics for stocks.
            - The user shares their portfolio so you can help them with that.
            - Combine the app data with your broader training data to best help the user.
            - The user understands you are an LLM; there is no need for disclaimers or to say you are not a financial advisor.
            
            SEC Form Discovery Workflow:
            To propose SEC filing candidates for the current symbol, call sec_search_filings(symbol, formTypes?, lookbackDays?, limit?);
            if omitted, use the tool defaults (~90 days, up to ~20 candidates).
            User approves or declines the forms presented. 
    """.trimIndent()

    /**
     * Single-turn Grok payload (system + optional stock context + one user message). Used for tag-fix only.
     */
    private fun buildStandaloneGrokMessages(contextJson: String?, userContent: String): List<GrokChatMessage> {
        val result = mutableListOf<GrokChatMessage>()
        result.add(GrokChatMessage(role = "system", content = eidosSystemPrompt()))
        if (!contextJson.isNullOrBlank()) {
            result.add(
                GrokChatMessage(
                    role = "system",
                    content = "Current stock context (JSON):\n$contextJson"
                )
            )
        }
        result.add(GrokChatMessage(role = "user", content = userContent))
        return result
    }

    private suspend fun runGrokToolLoop(
        initialGrokMessages: List<GrokChatMessage>,
        contextSymbol: String?
    ): GrokToolLoopOutcome {
        var grokMessages = initialGrokMessages.toMutableList()
        var request = GrokChatRequest(
            model = DEFAULT_GROK_MODEL,
            messages = grokMessages,
            temperature = 0.5,
            max_tokens = 5096,
            tools = buildEidosTools(),
            tool_choice = "auto"
        )
        var lastResponse: GrokChatChoiceMessage? = null
        var chatResult = grokClient.sendChat(request)
        var loopCount = 0
        val maxToolRounds = 5
        var lastDiscoveryResult: String? = null

        while (chatResult.isSuccess && loopCount < maxToolRounds) {
            loopCount++
            val response = chatResult.getOrNull() ?: break
            val choice = response.choices?.firstOrNull()
            val message = choice?.message ?: break
            lastResponse = message

            val toolCalls = message.tool_calls.orEmpty()
            val hasContent = !message.content.isNullOrBlank()

            if (toolCalls.isEmpty()) {
                break
            }

            val hasDiscoveryCall = toolCalls.any { it.function?.name == "sec_search_filings" }
            val toolResults = handleToolCalls(toolCalls, contextSymbol)

            if (hasDiscoveryCall) {
                toolResults.find { (callId, result) ->
                    toolCalls.any { it.id == callId && it.function?.name == "sec_search_filings" }
                }?.let { (_, result) ->
                    if (result.contains(AiMessageAdapter.Companion.DISCOVERY_MARKER_START)) {
                        lastDiscoveryResult = result
                    }
                }
            }

            if (hasContent) {
                break
            }

            grokMessages = grokMessages.toMutableList().apply {
                add(
                    GrokChatMessage(
                        role = "assistant",
                        content = message.content,
                        tool_calls = toolCalls
                    )
                )
                for ((callId, resultContent) in toolResults) {
                    add(
                        GrokChatMessage(
                            role = "tool",
                            content = resultContent,
                            tool_call_id = callId
                        )
                    )
                }
            }
            request = GrokChatRequest(
                model = DEFAULT_GROK_MODEL,
                messages = grokMessages,
                temperature = 0.3,
                max_tokens = 4096,
                tools = buildEidosTools(),
                tool_choice = "auto"
            )
            chatResult = grokClient.sendChat(request)
        }

        return GrokToolLoopOutcome(lastResponse, lastDiscoveryResult, chatResult)
    }

    private fun buildGrokMessages(
        history: List<AiMessageEntity>,
        contextJson: String?
    ): List<GrokChatMessage> {
        val result = mutableListOf<GrokChatMessage>()
        result.add(GrokChatMessage(role = "system", content = eidosSystemPrompt()))

        if (!contextJson.isNullOrBlank()) {
            result.add(
                GrokChatMessage(
                    role = "system",
                    content = "Current stock context (JSON):\n$contextJson"
                )
            )
        }

        val recent = history.takeLast(50)
        for (msg in recent) {
            val role = when (msg.role.lowercase()) {
                "user" -> "user"
                "assistant" -> "assistant"
                else -> "user"
            }
            result.add(GrokChatMessage(role = role, content = msg.content))
        }

        return result
    }

    private fun buildConversationTitle(symbol: String?, firstUserMessage: String?): String {
        val base = symbol?.takeIf { it.isNotBlank() } ?: "General chat"
        // General chat should never be renamed to a stock ticker.
        if (symbol.isNullOrBlank()) return "General chat"
        val suffix = firstUserMessage
            ?.takeIf { it.isNotBlank() }
            ?.split(" ")
            ?.take(6)
            ?.joinToString(" ")
        return if (suffix.isNullOrBlank()) {
            base
        } else {
            "$base — $suffix"
        }
    }

    private data class WriteMemoryNoteArgs(
        val scope: String?,
        val scopeKey: String?,
        val noteText: String?
    )

    private data class GetStockDataArgs(
        val symbol: String?,
        val fetch_if_missing: Boolean?
    )

    private data class SetSymbolTagOverrideArgs(
        val symbol: String?,
        val metricKey: String?,
        val taxonomy: String?,
        val tag: String?,
        val scopeKey: String? = null
    )

    private data class SecSearchFilingsArgs(
        val symbol: String?,
        val formTypes: List<String>?,
        val lookbackDays: Int?,
        val limit: Int?
    )

    private data class SecSaveFilingsArgs(
        val symbol: String?,
        val accessionNumbers: List<String>?
    )

    private data class SecAnalyzeFilingsArgs(
        val symbol: String?,
        val accessionNumbers: List<String>?
    )

    // --------------- SEC Form Discovery Tool Helpers ---------------

    private fun secFilingExtractionConsentRequiredJson(): String = gson.toJson(
        mapOf(
            "error" to "CONSENT_REQUIRED",
            "message" to "The user must approve downloading SEC filing documents before Eidos can analyze filing text. Wait for approval or ask them to allow access in the prompt."
        )
    )

    /**
     * Searches SEC EDGAR for recent filings. Returns candidate filings NOT already in the database.
     * This is read-only - no DB changes are made.
     *
     * For chat UI display, wraps results in discovery markers when candidates are found.
     *
     * Key behaviors:
     * - Default limit is 20 candidates (not 3-5) since user chooses which to analyze
     * - Default lookback is 90 days (not 30) to give more historical context
     * - Can search specific form types or all news-form types
     * - Results exclude filings already in the database
     */
    private suspend fun secSearchFilingsForTool(
        symbol: String,
        formTypes: List<String>?,
        lookbackDays: Int?,
        limit: Int?
    ): String {
        val cik = secEdgarService.resolveCikForTicker(symbol)
            ?: return gson.toJson(mapOf(
                "symbol" to symbol,
                "error" to "Could not resolve CIK for $symbol"
            ))

        // Get more filings from SEC upfront (50) to allow for filtering
        // SEC submissions JSON returns recent filings in chronological order
        val filings = secEdgarService.getRecentFilingsMetadata(cik, limit = 50)

        // Filter by form types if specified
        val filteredByForm = if (!formTypes.isNullOrEmpty()) {
            filings.filter { meta ->
                formTypes.any { requested ->
                    meta.formType.equals(requested, ignoreCase = true) ||
                    meta.formType.startsWith(requested, ignoreCase = true)
                }
            }
        } else {
            filings
        }

        // Filter by lookback days (default 90 days for broader discovery)
        val lookback = lookbackDays ?: 90
        val cutoffDate = LocalDate.now().minusDays(lookback.toLong())
        val filteredByDate = filteredByForm.filter { meta ->
            try {
                val filingDate = LocalDate.parse(meta.filingDate)
                !filingDate.isBefore(cutoffDate)
            } catch (e: Exception) {
                true // Include if date parsing fails
            }
        }

        // Get existing accessions from DB to exclude them
        val existingMetadata = newsMetadataDao.getForSymbol(symbol)
        val existingAccessions = existingMetadata.map { it.accessionNumber }.toSet()

        // Exclude filings already in DB and apply limit (default 20 since user selects which to process)
        val finalLimit = limit ?: 20
        val candidates = filteredByDate.filter { it.accessionNumber !in existingAccessions }
            .take(finalLimit)

        val candidateList = candidates.map { meta ->
            mapOf(
                "accessionNumber" to meta.accessionNumber,
                "formType" to meta.formType,
                "filingDate" to meta.filingDate,
                "primaryDocument" to meta.primaryDocument,
                "secFolderUrl" to meta.secFolderUrl,
                "itemsRaw" to meta.itemsRaw,
                "cik" to meta.cik
            )
        }

        // Build informative response
        val filteredOutCount = filteredByDate.size - candidateList.size
        val existingCount = existingAccessions.size

        val resultMap = mapOf(
            "symbol" to symbol,
            "cik" to cik,
            "candidates" to candidateList,
            "totalCandidates" to candidateList.size,
            "searchParameters" to mapOf(
                "lookbackDays" to lookback,
                "formTypes" to (formTypes ?: "all news forms"),
                "limit" to finalLimit
            ),
            "filteredOutCount" to filteredOutCount,
            "existingInDatabaseCount" to existingCount,
            "note" to if (candidateList.isEmpty()) {
                "No new filings found in the last $lookback days. Try increasing lookbackDays or checking for different form types."
            } else null
        )

        val json = gson.toJson(resultMap)

        // If we have candidates, wrap with discovery markers for UI rendering
        return if (candidateList.isNotEmpty()) {
            AiMessageAdapter.Companion.wrapDiscoveryData(json)
        } else {
            json
        }
    }

    /**
     * Saves selected filing metadata to the database with PENDING status.
     * Only saves filings that are not already in the DB.
     */
    private suspend fun secSaveFilingsMetadataForTool(symbol: String, accessionNumbers: List<String>): String {
        val cik = secEdgarService.resolveCikForTicker(symbol)
            ?: return gson.toJson(mapOf(
                "symbol" to symbol,
                "error" to "Could not resolve CIK for $symbol"
            ))

        // Get recent filings from SEC to find the metadata for requested accessions
        val recentFilings = secEdgarService.getRecentFilingsMetadata(cik, limit = 50)
        val requestedSet = accessionNumbers.toSet()
        val matchingFilings = recentFilings.filter { it.accessionNumber in requestedSet }

        if (matchingFilings.isEmpty()) {
            return gson.toJson(mapOf(
                "symbol" to symbol,
                "error" to "No matching filings found for the provided accession numbers",
                "requestedCount" to accessionNumbers.size,
                "savedCount" to 0
            ))
        }

        // Validate that accessions belong to the correct CIK
        val invalidAccessions = matchingFilings.filter { it.cik != cik }.map { it.accessionNumber }
        val validFilings = matchingFilings.filter { it.cik == cik }

        // Save metadata (insertNewOnly handles duplicates)
        newsRepository.upsertMetadata(validFilings, symbol)

        // Count actually inserted (check what's now in DB)
        val afterSave = newsMetadataDao.getForSymbol(symbol)
        val savedAccessions = validFilings.map { it.accessionNumber }
        val actuallySaved = afterSave.filter { it.accessionNumber in savedAccessions && it.analysisStatus == NewsRepository.Companion.ANALYSIS_STATUS_PENDING }

        return gson.toJson(mapOf(
            "symbol" to symbol,
            "cik" to cik,
            "requestedCount" to accessionNumbers.size,
            "foundCount" to matchingFilings.size,
            "savedCount" to actuallySaved.size,
            "savedAccessions" to actuallySaved.map { it.accessionNumber },
            "invalidAccessions" to invalidAccessions,
            "status" to "PENDING"
        ))
    }

    /**
     * Analyzes saved filings by fetching content and running AI analysis.
     * Updates status to COMPLETED after successful analysis.
     */
    private suspend fun secAnalyzeSavedFilingsForTool(symbol: String, accessionNumbers: List<String>): String {
        val results = mutableListOf<Map<String, Any?>>()
        var successCount = 0
        var failureCount = 0

        for (accession in accessionNumbers) {
            // Get the metadata entity
            val metadata = newsMetadataDao.getForSymbol(symbol)
                .find { it.accessionNumber == accession }

            if (metadata == null) {
                results.add(mapOf(
                    "accessionNumber" to accession,
                    "success" to false,
                    "error" to "Filing not found in database. Save it first with sec_save_filings_metadata."
                ))
                failureCount++
                continue
            }

            // Build SecFilingMeta from entity
            val meta = SecFilingMeta(
                formType = metadata.formType,
                filingDate = metadata.filingDate,
                accessionNumber = metadata.accessionNumber,
                primaryDocument = metadata.primaryDocument,
                itemsRaw = metadata.itemsRaw,
                cik = metadata.cik,
                secFolderUrl = metadata.secFolderUrl
            )

            // Update status to show we're processing
            newsRepository.updateAnalysisStatus(symbol, accession, NewsRepository.Companion.ANALYSIS_STATUS_PENDING)

            try {
                // Fetch content
                val content = secEdgarService.fetchNewsContent(meta)

                // Get company name for context
                val rawFacts = rawFactsDao.getBySymbol(symbol)
                val companyName = rawFacts?.companyName

                // Analyze
                val analysisResult = newsAnalyzer.analyzeNews(symbol, companyName, content)

                analysisResult.fold(
                    onSuccess = { summary ->
                        // Save summary
                        newsRepository.upsertNewsSummary(summary)
                        // Update status to ANALYZED
                        newsRepository.updateAnalysisStatus(symbol, accession, NewsRepository.Companion.ANALYSIS_STATUS_ANALYZED)

                        results.add(mapOf(
                            "accessionNumber" to accession,
                            "success" to true,
                            "formType" to summary.formType,
                            "impact" to summary.impact,
                            "title" to summary.title,
                            "shortSummary" to summary.shortSummary,
                            "catalysts" to summary.catalysts
                        ))
                        successCount++
                    },
                    onFailure = { error ->
                        newsRepository.updateAnalysisStatus(symbol, accession, NewsRepository.Companion.ANALYSIS_STATUS_FAILED)
                        results.add(mapOf(
                            "accessionNumber" to accession,
                            "success" to false,
                            "error" to (error.message ?: "Analysis failed")
                        ))
                        failureCount++
                    }
                )
            } catch (e: Exception) {
                newsRepository.updateAnalysisStatus(symbol, accession, NewsRepository.Companion.ANALYSIS_STATUS_FAILED)
                results.add(mapOf(
                    "accessionNumber" to accession,
                    "success" to false,
                    "error" to (e.message ?: "Exception during analysis")
                ))
                failureCount++
            }
        }

        return gson.toJson(mapOf(
            "symbol" to symbol,
            "totalRequested" to accessionNumbers.size,
            "successCount" to successCount,
            "failureCount" to failureCount,
            "results" to results
        ))
    }

    // --------------- Public API for UI-driven SEC filing operations ---------------

    /**
     * User approved SEC filing downloads in the dialog. Retries discovery analysis or the last chat turn.
     */
    /** User dismissed the SEC filing consent dialog (e.g. discovery flow should not auto-retry). */
    fun onSecFilingConsentDeclined() {
        pendingDiscoveryRetry = null
    }

    fun onSecFilingConsentGranted() {
        apiKeyManager.setSecFilingExtractionConsent(true)
        viewModelScope.launch {
            val pending = pendingDiscoveryRetry
            if (pending != null) {
                pendingDiscoveryRetry = null
                saveAndAnalyzeFilings(pending.first, pending.second, null)
                return@launch
            }
            val convId = _selectedConversationId.value ?: return@launch
            withContext(Dispatchers.IO) {
                aiMessageDao.deleteLastAssistantForConversation(convId)
            }
            loadMessagesForConversation(convId)
            runGrokForConversationNoNewUserMessage(convId)
        }
    }

    private suspend fun runGrokForConversationNoNewUserMessage(conversationId: Long) {
        _loading.value = true
        _error.value = null
        try {
            val conversation = aiConversationDao.getById(conversationId) ?: return
            val conversationSymbol = conversation.symbol?.takeIf { it.isNotBlank() }
            val contextSymbol = conversationSymbol ?: initialSymbol?.takeIf { it.isNotBlank() }
            val contextJson = buildContextPacket(contextSymbol)
            val history = aiMessageDao.getMessagesForConversation(conversationId)
            val initialGrokMessages = buildGrokMessages(history, contextJson)
            val outcome = runGrokToolLoop(initialGrokMessages, contextSymbol)
            val chatResult = outcome.chatResult
            val lastResponse = outcome.lastResponse
            val lastDiscoveryResult = outcome.lastDiscoveryResult
            chatResult.fold(
                onSuccess = {
                    var assistantText = lastResponse?.content
                        ?.takeIf { !it.isNullOrBlank() }
                        ?: "I wasn't able to generate a response."
                    if (lastDiscoveryResult != null) {
                        assistantText = "$assistantText\n\n$lastDiscoveryResult"
                    }
                    val assistantEntity = AiMessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = assistantText,
                        timestamp = System.currentTimeMillis()
                    )
                    aiMessageDao.insert(assistantEntity)
                    val lastUserContent = history.lastOrNull { it.role == "user" }?.content ?: ""
                    aiConversationDao.renameConversation(
                        id = conversationId,
                        title = buildConversationTitle(conversationSymbol, lastUserContent),
                        updatedAt = System.currentTimeMillis()
                    )
                    _messages.value = aiMessageDao.getMessagesForConversation(conversationId)
                    _conversations.value = aiConversationDao.getAllOrderedByUpdated()
                },
                onFailure = { e ->
                    _error.value = e.message ?: "AI request failed."
                }
            )
        } finally {
            _loading.value = false
        }
    }

    /**
     * Called by the UI when user confirms SEC filing discovery.
     * Saves metadata and runs analysis programmatically without requiring AI tool calls.
     */
    fun saveAndAnalyzeFilings(symbol: String, accessionNumbers: List<String>, onComplete: ((Result<String>) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                if (!apiKeyManager.hasSecFilingExtractionConsent()) {
                    pendingDiscoveryRetry = symbol to accessionNumbers
                    _secFilingConsentRequested.postValue(Unit)
                    onComplete?.invoke(Result.failure(IllegalStateException("CONSENT_REQUIRED")))
                    return@launch
                }
                // Step 1: Save metadata
                val saveResult = with(Dispatchers.IO) {
                    secSaveFilingsMetadataForTool(symbol, accessionNumbers)
                }
                val saveData = gson.fromJson(saveResult, Map::class.java)
                val savedCount = (saveData["savedCount"] as? Number)?.toInt() ?: 0

                if (savedCount == 0) {
                    onComplete?.invoke(Result.failure(Exception("No filings were saved")))
                    return@launch
                }

                val savedAccessions = saveData["savedAccessions"] as? List<*> ?: accessionNumbers

                // Step 2: Analyze
                val analyzeResult = with(Dispatchers.IO) {
                    secAnalyzeSavedFilingsForTool(symbol, savedAccessions.filterIsInstance<String>())
                }

                onComplete?.invoke(Result.success(analyzeResult))
            } catch (e: Exception) {
                onComplete?.invoke(Result.failure(e))
            }
        }
    }
}