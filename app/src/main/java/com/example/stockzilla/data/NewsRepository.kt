package com.example.stockzilla.data

import com.example.stockzilla.feature.DiagnosticsLogger
import com.example.stockzilla.sec.NewsSummary
import com.example.stockzilla.sec.SecFilingMeta
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NewsRepository(
    private val metadataDao: NewsMetadataDao,
    private val newsDao: NewsSummariesDao,
    private val gson: Gson = Gson()
) {

    // --------------- Stage 1: Metadata (cheap) ---------------

    /**
     * Inserts metadata rows for filings that don't already exist in the DB.
     * Rows that are already present (by PK: symbol+accessionNumber) are left untouched,
     * preserving their analysisStatus (ANALYZED / FAILED / PENDING).
     */
    suspend fun upsertMetadata(filings: List<SecFilingMeta>, symbol: String) {
        val now = System.currentTimeMillis()
        val entities = filings.map { meta ->
            NewsMetadataEntity(
                symbol = symbol,
                cik = meta.cik,
                accessionNumber = meta.accessionNumber,
                filingDate = meta.filingDate,
                formType = meta.formType,
                primaryDocument = meta.primaryDocument,
                itemsRaw = meta.itemsRaw,
                normalizedItems = meta.normalizedItems.joinToString(","),
                secFolderUrl = meta.secFolderUrl,
                analysisStatus = ANALYSIS_STATUS_PENDING,
                createdAt = now,
                updatedAt = now
            )
        }
        metadataDao.insertNewOnly(entities)
        DiagnosticsLogger.log(symbol, "8K_META_UPSERT", "Inserted new-only from ${entities.size} metadata rows")
    }

    /** Filing date of the newest already-ANALYZED filing, or null if nothing has been analyzed yet. */
    suspend fun getNewestAnalyzedDate(symbol: String): String? =
        metadataDao.getNewestAnalyzedDate(symbol)

    /**
     * Returns how many PENDING filings are newer than the newest ANALYZED filing.
     * If nothing has been analyzed yet, returns the total PENDING count (all are "new").
     */
    suspend fun getNewFilingsCount(symbol: String): Int {
        val newestAnalyzed = metadataDao.getNewestAnalyzedDate(symbol)
        return if (newestAnalyzed == null) {
            metadataDao.countAllPending(symbol)
        } else {
            metadataDao.countPendingNewerThan(symbol, newestAnalyzed)
        }
    }

    /**
     * Returns Stage 2 candidates — the filings that should be sent to Eidos next.
     *
     * - Initial run (nothing analyzed yet): top [limit] pending by filingDate desc.
     * - Subsequent runs: pending filings with filingDate > newest analyzed date, up to [limit].
     */
    suspend fun getNewFilingCandidates(symbol: String, limit: Int = 3): List<NewsMetadataEntity> {
        val newestAnalyzed = metadataDao.getNewestAnalyzedDate(symbol)
        return if (newestAnalyzed == null) {
            metadataDao.getTopPending(symbol, limit)
        } else {
            metadataDao.getPendingNewerThan(symbol, newestAnalyzed, limit)
        }
    }

    suspend fun updateAnalysisStatus(symbol: String, accessionNumber: String, status: String) {
        metadataDao.updateStatus(
            symbol = symbol,
            accessionNumber = accessionNumber,
            status = status,
            updatedAt = System.currentTimeMillis()
        )
    }

    // --------------- Stage 2: News summaries (Eidos outputs) ---------------

    suspend fun upsertNewsSummary(summary: NewsSummary) {
        val now = System.currentTimeMillis()
        val entity = NewsSummaryEntity(
            symbol = summary.symbol,
            cik = summary.cik,
            accessionNumber = summary.accessionNumber,
            filingDate = summary.filingDate,
            title = summary.title,
            shortSummary = summary.shortSummary,
            detailedSummary = summary.detailedSummary,
            impact = summary.impact,
            catalystsJson = gson.toJson(summary.catalysts),
            normalizedItems = summary.normalizedItems.joinToString(","),
            secUrl = summary.secUrl,
            createdAt = now,
            lastAnalyzedAt = now
        )
        newsDao.upsert(entity)
        DiagnosticsLogger.log(summary.symbol, "8K_NEWS_UPSERT", "Saved summary for ${summary.accessionNumber} impact=${summary.impact}")
    }

    /** Returns up to [limit] most recent Eidos-analyzed summaries for a symbol, mapped to domain objects. */
    suspend fun getRecentSummaries(symbol: String, limit: Int = 3): List<NewsSummary> {
        return newsDao.getRecentForSymbolWithFormType(symbol, limit).map { entity ->
            NewsSummary(
                symbol = entity.symbol,
                cik = entity.cik,
                accessionNumber = entity.accessionNumber,
                filingDate = entity.filingDate,
                formType = entity.formType,
                secUrl = entity.secUrl,
                title = entity.title,
                shortSummary = entity.shortSummary,
                detailedSummary = entity.detailedSummary,
                impact = entity.impact,
                catalysts = parseCatalysts(entity.catalystsJson),
                normalizedItems = parseItems(entity.normalizedItems),
                createdAt = entity.createdAt
            )
        }
    }

    private fun parseCatalysts(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseItems(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        const val ANALYSIS_STATUS_PENDING = "PENDING"
        const val ANALYSIS_STATUS_ANALYZED = "ANALYZED"
        const val ANALYSIS_STATUS_FAILED = "FAILED"
    }
}