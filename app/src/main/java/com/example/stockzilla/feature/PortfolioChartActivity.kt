package com.example.stockzilla.feature

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.stockzilla.data.StockzillaDatabase
import com.example.stockzilla.databinding.ActivityPortfolioChartBinding
import kotlinx.coroutines.launch

class PortfolioChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPortfolioChartBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortfolioChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val dao = StockzillaDatabase.Companion.getDatabase(this).portfolioValueSnapshotDao()
        lifecycleScope.launch {
            val snapshots = dao.getOldestFirst(365)
            runOnUiThread {
                if (snapshots.size < 2) {
                    binding.tvChartEmpty.isVisible = true
                    binding.portfolioChartView.isVisible = false
                } else {
                    binding.tvChartEmpty.isVisible = false
                    binding.portfolioChartView.isVisible = true
                    binding.portfolioChartView.setData(snapshots)
                }
            }
        }
    }
}