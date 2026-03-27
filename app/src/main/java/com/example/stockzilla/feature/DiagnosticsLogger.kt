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
 *
 * Entries are appended to [filesDir]/[FILE_NAME]. [getLogContent] reads from that file (newest first)
 * so history survives process death and is not limited to the in-memory ring buffer.
 * Categories prefixed with [CATEGORY_EIDOS_TAG_PREFIX] are highlighted in Diagnostic Log UI.
 */
object DiagnosticsLogger {
    private const val TAG = "StockzillaDiag"
    private const val FILE_NAME = "edgar_diagnostics.log"

    /** Use categories starting with this string for Eidos tagging (colored in diagnostic viewer). */
    const val CATEGORY_EIDOS_TAG_PREFIX = "EIDOS_TAG"

    private const val MAX_FILE_BYTES = 1_500_000

    /** In-memory ring for Logcat parity and fallback if file read fails. */
    private const val MAX_MEMORY_ENTRIES = 400

    /** Max lines returned from disk (newest first). */
    private const val MAX_DISPLAY_LINES_FROM_DISK = 1_200

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
     * Log a diagnostic entry. Also sent to Log.d. Stored in memory ring and appended to file.
     */
    @Synchronized
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
        while (memoryLog.size > MAX_MEMORY_ENTRIES) {
            memoryLog.removeAt(memoryLog.size - 1)
        }
        appendToFile(line)
    }

    private fun appendToFile(line: String) {
        val f = file() ?: return
        try {
            synchronized(this) {
                if (f.length() > MAX_FILE_BYTES) {
                    val content = f.readText()
                    val trimmed = content.takeLast(MAX_FILE_BYTES / 2)
                    f.writeText(trimmed)
                }
                f.appendText(line + "\n")
            }
        } catch (_: Exception) { /* ignore */ }
    }

    /**
     * Newest first. Reads persisted file (full history tail) so logs survive app restarts.
     */
    @Synchronized
    fun getLogContent(): String {
        val f = file()
        val fromDisk = try {
            if (f != null && f.exists() && f.length() > 0L) {
                f.readLines()
                    .asReversed()
                    .filter { it.isNotBlank() }
                    .take(MAX_DISPLAY_LINES_FROM_DISK)
                    .joinToString("\n")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (!fromDisk.isNullOrBlank()) return fromDisk
        return memoryLog.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        memoryLog.clear()
        try {
            file()?.delete()
        } catch (_: Exception) { }
    }
}
