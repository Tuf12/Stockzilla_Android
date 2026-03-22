package com.example.stockzilla.feature

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persists EDGAR/data-source diagnostics so the developer can view or share logs
 * when back at a computer (e.g. from device without adb).
 */
object DiagnosticsLogger {
    private const val TAG = "StockzillaDiag"
    private const val FILE_NAME = "edgar_diagnostics.log"
    private const val MAX_FILE_BYTES = 300_000
    private const val MAX_MEMORY_ENTRIES = 300
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var appContext: Context? = null
    private val memoryLog = CopyOnWriteArrayList<String>()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun file(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    /**
     * Log a diagnostic entry. Also sent to Log.d. Stored in memory and appended to file.
     */
    fun log(symbol: String?, category: String, message: String, detail: String? = null) {
        val ts = dateFormat.format(Date())
        val sym = symbol?.let { "[$it]" } ?: ""
        val line = buildString {
            append(ts)
            append(" ")
            append(category)
            append(" ")
            append(sym)
            append(" ")
            append(message)
            if (!detail.isNullOrBlank()) {
                append(" | ")
                append(detail)
            }
        }
        Log.d(TAG, line)
        memoryLog.add(0, line)
        while (memoryLog.size > MAX_MEMORY_ENTRIES) memoryLog.removeAt(memoryLog.size - 1)
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val f = file() ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) {
                val content = f.readText()
                val trimmed = content.takeLast(MAX_FILE_BYTES / 2)
                f.writeText(trimmed)
            }
            f.appendText(line + "\n")
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Returns the full log content (memory + file) for display or share.
     * Newest first.
     */
    fun getLogContent(): String {
        val fromFile = try {
            file()?.takeIf { it.exists() }?.readText()?.lines()?.reversed()?.take(500) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val fromMemory = memoryLog.toList()
        val combined = (fromMemory + fromFile).distinct().take(MAX_MEMORY_ENTRIES)
        return combined.joinToString("\n")
    }

    fun clear() {
        memoryLog.clear()
        try {
            file()?.delete()
        } catch (_: Exception) { }
    }
}