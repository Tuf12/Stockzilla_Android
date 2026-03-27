package com.example.stockzilla.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.stockzilla.R
import com.example.stockzilla.databinding.ActivityDiagnosticLogBinding

/**
 * Shows persisted EDGAR/data-source diagnostic log so the developer can copy or share it
 * when back at a computer (e.g. from device without adb).
 */
class DiagnosticLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsLogger.init(this)
        binding = ActivityDiagnosticLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        refreshLog()
        binding.btnCopy.setOnClickListener {
            val content = DiagnosticsLogger.getLogContent()
            if (content.isBlank()) {
                Toast.makeText(this, getString(R.string.diagnostic_log_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Stockzilla diagnostic log", content))
            Toast.makeText(this, getString(R.string.diagnostic_log_copied), Toast.LENGTH_SHORT).show()
        }
        binding.btnShare.setOnClickListener {
            val content = DiagnosticsLogger.getLogContent()
            if (content.isBlank()) {
                Toast.makeText(this, getString(R.string.diagnostic_log_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Stockzilla diagnostic log")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostic_log_share)))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        val content = DiagnosticsLogger.getLogContent()
        if (content.isBlank()) {
            binding.tvLog.text = getString(R.string.diagnostic_log_empty)
            return
        }
        val eidosColor = ContextCompat.getColor(this, R.color.eidosDiagnosticLog)
        val ssb = SpannableStringBuilder()
        val lines = content.split('\n')
        val prefix = DiagnosticsLogger.CATEGORY_EIDOS_TAG_PREFIX
        // Log lines: "yyyy-MM-dd HH:mm:ss CATEGORY [SYM] message" — category follows 19-char timestamp + space.
        val tsLen = 19
        for (i in lines.indices) {
            val line = lines[i]
            val start = ssb.length
            ssb.append(line)
            val category = if (line.length > tsLen + 1 && line[tsLen] == ' ') {
                val afterTs = line.substring(tsLen + 1)
                val endCat = afterTs.indexOf(' ').let { j -> if (j < 0) afterTs.length else j }
                afterTs.substring(0, endCat)
            } else {
                ""
            }
            if (category.startsWith(prefix)) {
                ssb.setSpan(
                    ForegroundColorSpan(eidosColor),
                    start,
                    ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i < lines.lastIndex) ssb.append('\n')
        }
        binding.tvLog.text = ssb
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DiagnosticLogActivity::class.java))
        }
    }
}