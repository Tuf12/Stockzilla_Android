package com.example.stockzilla.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.stockzilla.ai.AiMemoryCacheAdapter
import com.example.stockzilla.ai.AiMemoryCacheViewModel
import com.example.stockzilla.databinding.ActivityAiMemoryCacheBinding

class AiMemoryCacheActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiMemoryCacheBinding
    private val viewModel: AiMemoryCacheViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiMemoryCacheBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val symbol = intent.getStringExtra(EXTRA_SYMBOL)?.takeIf { it.isNotBlank() }
        val adapter = AiMemoryCacheAdapter { noteId ->
            viewModel.deleteNote(noteId)
        }

        binding.rvMemoryNotes.layoutManager = LinearLayoutManager(this)
        binding.rvMemoryNotes.adapter = adapter

        viewModel.memoryNotes.observe(this) { notes ->
            adapter.submitList(notes)
            binding.emptyMemoryText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.loadNotesForContext(symbol)
    }

    companion object {
        private const val EXTRA_SYMBOL = "extra_symbol"

        fun start(context: Context, symbol: String?) {
            val intent = Intent(context, AiMemoryCacheActivity::class.java)
            if (!symbol.isNullOrBlank()) {
                intent.putExtra(EXTRA_SYMBOL, symbol)
            }
            context.startActivity(intent)
        }
    }
}