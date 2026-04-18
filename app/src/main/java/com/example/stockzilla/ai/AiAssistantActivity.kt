package com.example.stockzilla.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.R
import com.example.stockzilla.data.AiConversationEntity
import com.example.stockzilla.databinding.ActivityAiAssistantBinding
import com.example.stockzilla.feature.FullAnalysisActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AiAssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiAssistantBinding
    private val viewModel: AiAssistantViewModel by viewModels()
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var isUpdatingFromViewModel: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // Initial title; updated below when conversations load/selection changes.
        supportActionBar?.title = getString(R.string.ai_chat_title_prefix)

        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.ai_drawer_open,
            R.string.ai_drawer_close
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        val initialSymbol = intent.getStringExtra(EXTRA_SYMBOL)?.takeIf { it.isNotBlank() }
        val rawOpenMode = intent.getStringExtra(EXTRA_OPEN_MODE)
        val openMode = rawOpenMode?.let { modeName ->
            runCatching { AiAssistantViewModel.OpenMode.valueOf(modeName) }.getOrNull()
        } ?: AiAssistantViewModel.OpenMode.LAST_CHAT
        Log.i("EidosNav", "AiAssistantActivity onCreate initialSymbol=$initialSymbol openMode=$openMode")
        viewModel.setInitialSymbol(initialSymbol)
        viewModel.setOpenMode(openMode)
        val focusGovNewsItemId = intent.getLongExtra(EXTRA_GOV_NEWS_FOCUS_ITEM_ID, -1L).takeIf { it >= 0L }
        viewModel.setPendingGovNewsFocusItemId(focusGovNewsItemId)

        setupRecyclerViews()
        setupInput()
        observeViewModel()

        viewModel.loadConversations()

        if (intent.getBooleanExtra(EXTRA_LAUNCHED_FOR_TAG_FIX, false)) {
            viewModel.tagFixCompletedStock.observe(this) { stock ->
                if (stock != null) {
                    viewModel.tagFixCompletedStock.removeObservers(this)
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(FullAnalysisActivity.EXTRA_STOCK_DATA, stock as java.io.Serializable)
                    )
                    finish()
                }
            }
            val sym = intent.getStringExtra(EXTRA_SYMBOL)?.trim()?.uppercase()
            val mk = intent.getStringExtra(EXTRA_TAG_FIX_METRIC_KEY)?.trim()
            val fj = intent.getStringExtra(EXTRA_TAG_FIX_FACTS_INDEX_JSON).orEmpty()
            if (!sym.isNullOrBlank() && !mk.isNullOrBlank()) {
                binding.root.postDelayed({
                    viewModel.runTagFixBootstrap(sym, mk, fj)
                }, 500)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.ai_assistant_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return when (item.itemId) {
            R.id.action_ai_memory -> {
                // Launch Memory Cache viewer for the current stock symbol, if any.
                val currentSymbol = viewModel.selectedConversationId.value
                    ?.let { id -> viewModel.conversations.value?.firstOrNull { it.id == id }?.symbol }
                    ?.takeUnless { it == AiAssistantViewModel.RESERVED_SYMBOL_GOV_NEWS }
                AiMemoryCacheActivity.Companion.start(this, currentSymbol)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerViews() {
        val conversationsAdapter = AiConversationAdapter(
            onClick = { conversation ->
                viewModel.selectConversation(conversation.id)
                binding.drawerLayout.closeDrawers()
            },
            onLongClick = { conversation ->
                showConversationLongPressMenu(conversation)
            }
        )
        binding.rvConversations.layoutManager = LinearLayoutManager(this)
        binding.rvConversations.adapter = conversationsAdapter

        val messagesAdapter = AiMessageAdapter(
            onDiscoveryConfirm = { symbol, accessions ->
                handleDiscoveryConfirm(symbol, accessions)
            },
            onDeleteMessage = { message ->
                viewModel.deleteMessage(message.id)
            }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = messagesAdapter

        viewModel.conversations.observe(this) { list ->
            conversationsAdapter.submitList(list)

            // Update toolbar title to reflect the current conversation name.
            val selectedId = viewModel.selectedConversationId.value
            val current = list.firstOrNull { it.id == selectedId }
            val convoTitle = current?.title
            Log.i(
                "EidosNav",
                "conversations updated size=${list.size} selectedId=$selectedId hasGeneral=${list.any { it.symbol.isNullOrBlank() }} generalId=${list.firstOrNull { it.symbol.isNullOrBlank() }?.id}"
            )
            if (selectedId != null) {
                val idx = list.indexOfFirst { it.id == selectedId }
                Log.i("EidosNav", "conversations: selectedId=$selectedId index=$idx")
            }
            Log.i(
                "EidosNav",
                "conversations: computed title convoTitle=$convoTitle willSetToolbarTitle=${if (convoTitle.isNullOrBlank()) getString(
                    R.string.ai_chat_title_prefix) else getString(R.string.ai_chat_title_prefix) + " — " + convoTitle}"
            )
            supportActionBar?.title = if (convoTitle.isNullOrBlank()) {
                getString(R.string.ai_chat_title_prefix)
            } else {
                getString(R.string.ai_chat_title_prefix) + " — " + convoTitle
            }
        }

        viewModel.selectedConversationId.observe(this) { id ->
            val list = viewModel.conversations.value.orEmpty()
            val current = list.firstOrNull { it.id == id }
            val convoTitle = current?.title
            Log.i(
                "EidosNav",
                "selectedConversationId observer: id=$id convoTitle=$convoTitle willSetToolbarTitle=${if (convoTitle.isNullOrBlank()) getString(
                    R.string.ai_chat_title_prefix) else getString(R.string.ai_chat_title_prefix) + " — " + convoTitle}"
            )
            supportActionBar?.title = if (convoTitle.isNullOrBlank()) {
                getString(R.string.ai_chat_title_prefix)
            } else {
                getString(R.string.ai_chat_title_prefix) + " — " + convoTitle
            }
        }

        viewModel.messages.observe(this) { list ->
            messagesAdapter.submitList(list) {
                if (list.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(list.size - 1)
                }
            }
            binding.emptyMessagesText.isVisible = list.isEmpty()
        }
    }

    private fun showConversationLongPressMenu(conversation: AiConversationEntity) {
        // General and Gov News chats are pinned and not editable.
        val isPinnedSystem = conversation.symbol.isNullOrBlank() ||
            conversation.symbol == AiAssistantViewModel.RESERVED_SYMBOL_GOV_NEWS
        val options = if (isPinnedSystem) {
            arrayOf(getString(R.string.ai_delete_conversation))
        } else {
            arrayOf(
                getString(R.string.ai_rename_conversation),
                getString(R.string.ai_delete_conversation)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(options) { dialog, which ->
                if (isPinnedSystem) {
                    if (which == 0) {
                        // Do not actually delete pinned system chats; just dismiss.
                        dialog.dismiss()
                    }
                    return@setItems
                } else {
                    when (which) {
                        0 -> {
                            // Rename
                            showRenameConversationDialog(conversation)
                        }
                        1 -> {
                            // Delete
                            viewModel.deleteConversation(conversation.id)
                            binding.drawerLayout.closeDrawers()
                        }
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showRenameConversationDialog(conversation: AiConversationEntity) {
        val input = EditText(this).apply {
            setText(conversation.title)
            setSelection(conversation.title.length.coerceAtLeast(0))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ai_rename_conversation))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                val newTitle = input.text?.toString().orEmpty()
                viewModel.renameConversation(conversation.id, newTitle)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            sendMessageFromInput()
        }
        binding.editMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingFromViewModel) return
                viewModel.updateDraft(s?.toString().orEmpty())
            }
        })
        binding.editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessageFromInput()
                true
            } else {
                false
            }
        }
    }

    private fun sendMessageFromInput() {
        val text = binding.editMessage.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        isUpdatingFromViewModel = true
        binding.editMessage.setText("")
        binding.editMessage.setSelection(0)
        isUpdatingFromViewModel = false
        viewModel.sendMessage(text)
    }

    private fun observeViewModel() {
        viewModel.loading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
        }
        viewModel.error.observe(this) { errorText ->
            binding.errorText.isVisible = !errorText.isNullOrBlank()
            binding.errorText.text = errorText ?: ""
        }
        viewModel.requiresApiKey.observe(this) { needsKey ->
            binding.apiKeyHint.isVisible = needsKey
        }
        viewModel.secFilingConsentRequested.observe(this) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sec_filing_extraction_consent_title)
                .setMessage(R.string.sec_filing_extraction_consent_message)
                .setPositiveButton(R.string.sec_filing_extraction_allow) { _, _ ->
                    viewModel.onSecFilingConsentGranted()
                }
                .setNegativeButton(R.string.sec_filing_extraction_not_now) { _, _ ->
                    viewModel.onSecFilingConsentDeclined()
                }
                .show()
        }
        viewModel.draftText.observe(this) { draft ->
            val current = binding.editMessage.text?.toString() ?: ""
            if (current == draft) return@observe
            isUpdatingFromViewModel = true
            binding.editMessage.setText(draft)
            binding.editMessage.setSelection(draft.length)
            isUpdatingFromViewModel = false
        }
    }

    /**
     * Handles user confirmation of SEC filing discovery.
     * Programmatically saves and analyzes the selected filings.
     */
    private fun handleDiscoveryConfirm(symbol: String, accessions: List<String>) {
        viewModel.saveAndAnalyzeFilings(symbol, accessions) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = { analyzeResult ->
                        // Parse result to show appropriate message
                        val type = object : TypeToken<Map<String, Any?>>() {}.type
                        val data: Map<String, Any?> = Gson().fromJson(analyzeResult, type)
                        val successCount = (data["successCount"] as? Number)?.toInt() ?: 0
                        val message = if (successCount > 0) {
                            "Analysis complete. $successCount filing(s) saved and analyzed."
                        } else {
                            "Analysis completed but no filings were successfully processed."
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        if (error.message == "CONSENT_REQUIRED") {
                            return@fold
                        }
                        Toast.makeText(
                            this,
                            "Error: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SYMBOL = "extra_symbol"
        private const val EXTRA_OPEN_MODE = "extra_open_mode"
        private const val EXTRA_GOV_NEWS_FOCUS_ITEM_ID = "extra_gov_news_focus_item_id"
        const val EXTRA_LAUNCHED_FOR_TAG_FIX = "extra_launched_for_tag_fix"
        const val EXTRA_TAG_FIX_METRIC_KEY = "extra_tag_fix_metric_key"
        const val EXTRA_TAG_FIX_FACTS_INDEX_JSON = "extra_tag_fix_facts_index_json"

        fun createTagFixIntent(
            context: Context,
            symbol: String,
            metricKey: String,
            factsIndexJson: String
        ): Intent = Intent(context, AiAssistantActivity::class.java).apply {
            putExtra(EXTRA_SYMBOL, symbol.trim().uppercase())
            putExtra(EXTRA_LAUNCHED_FOR_TAG_FIX, true)
            putExtra(EXTRA_TAG_FIX_METRIC_KEY, metricKey)
            putExtra(EXTRA_TAG_FIX_FACTS_INDEX_JSON, factsIndexJson)
            putExtra(EXTRA_OPEN_MODE, AiAssistantViewModel.OpenMode.LAST_CHAT.name)
        }

        fun start(context: Context, symbol: String?) {
            start(context, symbol, AiAssistantViewModel.OpenMode.LAST_CHAT)
        }

        fun start(context: Context, symbol: String?, openMode: AiAssistantViewModel.OpenMode) {
            val intent = Intent(context, AiAssistantActivity::class.java).apply {
                if (!symbol.isNullOrBlank()) {
                    putExtra(EXTRA_SYMBOL, symbol)
                }
                putExtra(EXTRA_OPEN_MODE, openMode.name)
            }
            context.startActivity(intent)
        }

        /** Opens Eidos with the pinned Gov News conversation (no stock symbol). */
        fun startGovNewsChat(context: Context) {
            startGovNewsChat(context, focusGovNewsItemId = null)
        }

        /**
         * Opens the single Gov News chat. When [focusGovNewsItemId] is set, the next user message’s context
         * includes that article once (then cleared); the thread stays a general Gov News conversation.
         */
        fun startGovNewsChat(context: Context, focusGovNewsItemId: Long?) {
            val intent = Intent(context, AiAssistantActivity::class.java).apply {
                putExtra(EXTRA_OPEN_MODE, AiAssistantViewModel.OpenMode.FORCE_GOV_NEWS_IF_NO_SYMBOL.name)
                val id = focusGovNewsItemId?.takeIf { it >= 0L }
                if (id != null) {
                    putExtra(EXTRA_GOV_NEWS_FOCUS_ITEM_ID, id)
                }
            }
            context.startActivity(intent)
        }
    }
}