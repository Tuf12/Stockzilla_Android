package com.example.stockzilla.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.stockzilla.data.AiMemoryCacheEntity
import com.example.stockzilla.data.StockzillaDatabase
import kotlinx.coroutines.launch

class AiMemoryCacheViewModel(application: Application) : AndroidViewModel(application) {

    private val database = StockzillaDatabase.Companion.getDatabase(application)
    private val aiMemoryCacheDao = database.aiMemoryCacheDao()

    private val _memoryNotes = MutableLiveData<List<AiMemoryCacheEntity>>(emptyList())
    val memoryNotes: LiveData<List<AiMemoryCacheEntity>> = _memoryNotes

    fun loadNotesForContext(symbol: String?) {
        viewModelScope.launch {
            val userNotes = aiMemoryCacheDao.getNotesForScope(scope = "USER", scopeKey = "user")
            val stockNotes = symbol?.let {
                aiMemoryCacheDao.getNotesForScope(scope = "STOCK", scopeKey = it)
            } ?: emptyList()

            _memoryNotes.value = (stockNotes + userNotes).sortedByDescending { it.updatedAt }
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            aiMemoryCacheDao.deleteById(id)
            // Reload after delete to reflect current context.
            val currentList = _memoryNotes.value.orEmpty()
            if (currentList.isNotEmpty()) {
                val sample = currentList.first()
                val symbolFromSample = if (sample.scope == "STOCK") sample.scopeKey else null
                loadNotesForContext(symbolFromSample)
            } else {
                _memoryNotes.value = emptyList()
            }
        }
    }
}