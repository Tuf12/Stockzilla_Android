package com.example.stockzilla.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.stockzilla.feature.DiagnosticsLogger
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
        binding.tvLog.text = if (content.isBlank()) getString(R.string.diagnostic_log_empty) else content
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DiagnosticLogActivity::class.java))
        }
    }
}