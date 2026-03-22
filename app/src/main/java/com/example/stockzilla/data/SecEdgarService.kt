package com.example.stockzilla.data

import android.util.Log
import com.example.stockzilla.sec.SecXmlExtraction
import com.example.stockzilla.feature.DiagnosticsLogger
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.sec.EdgarConcepts
import com.example.stockzilla.sec.NewsContent
import com.example.stockzilla.sec.SecFilingMeta
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator
import kotlin.math.abs
import okhttp3.Interceptor
import okhttp3.Response

private const val TAG = "SecEdgarService"

private class SecUserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Stockzilla/1.0 (contact@stockzilla.app)")
            .header("Accept", "application/json")
            .build()
        return chain.proceed(request)
    }
}

class SecEdgarService private constructor(
    /** Used for JSON API endpoints (submissions, companyfacts). Sets Accept: application/json. */
    private val client: OkHttpClient,
    /**
     * Used for raw document fetches (8-K HTML, exhibit HTML).
     * Does NOT set Accept: application/json — SEC EDGAR static file servers serve HTML regardless,
     * and the json Accept header can cause unexpected responses for non-JSON content.
     */
    private val documentClient: OkHttpClient,
    private val gson: Gson
) {
    private val cikCacheMutex = Mutex()
    private var cikCache: Map<String, String>? = null
    private var cikCacheTimestamp: Long = 0
    private val CIK_CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7)


    // News-form routing (incremental expansion).
    // Phase 1: 8-K / 6-K
    private val PHASE_1_FORM_TYPES = setOf(
        "8-K", "8-K/A",
        "6-K", "6-K/A"
    )

    // Phase 2: Ownership / Insider Activity
    private val PHASE_2_FORM_TYPES = setOf(
        // HTML-based ownership filings
        "SC 13D", "SC 13D/A",
        "SC 13G", "SC 13G/A",
        "144",
        // XML-based ownership filings
        "3", "4", "5",
        "13F-HR", "13F-HR/A"
    )

    // Phase 3: M&A / Tender Offers / Proxy Fights
    private val PHASE_3_FORM_TYPES = setOf(
        // Tender offers
        "SC TO-T", "SC TO-T/A",
        "SC TO-I", "SC TO-I/A",
        // Target responses to tender offers
        "SC 14D9", "SC 14D9/A",
        // Proxy statements
        "DEFM14A", "DEFA14A",
        "DEF 14A", "PREM14A"
    )

    // Phase 4: Dilution / Capital Raises
    private val PHASE_4_FORM_TYPES = setOf(
        // Registration statements
        "S-1", "S-1/A",
        "S-3", "S-3/A",
        "S-4", "S-4/A",
        // Employee plans (lower priority)
        "S-8", "S-8/A",
        // Registration effective / marketing materials
        "EFFECT", "FWP"
    )

    // Phase 5: Late/Delinquent Filings (Red Flags)
    private val PHASE_5_FORM_TYPES = setOf(
        "NT 10-K",
        "NT 10-Q",
        "NT 20-F"
    )

    // Phase 6: Periodic Financial Reports (long-form, narrative)
    private val PHASE_6_FORM_TYPES = setOf(
        "10-K", "10-K/A",
        "10-Q", "10-Q/A",
        "20-F", "20-F/A",
        "40-F", "40-F/A"
    )

    private val ALL_NEWS_FORM_TYPES =
        PHASE_1_FORM_TYPES + PHASE_2_FORM_TYPES + PHASE_3_FORM_TYPES + PHASE_4_FORM_TYPES +
            PHASE_5_FORM_TYPES + PHASE_6_FORM_TYPES

    private val XML_FORM_TYPES = setOf(
        "3", "4", "5",
        "13F-HR", "13F-HR/A"
    )

    private val HTML_FORM_TYPES = ALL_NEWS_FORM_TYPES - XML_FORM_TYPES

    // Long documents are handled via regex section extraction to stay within Grok's budget.
    private val LONG_DOCUMENT_FORM_TYPES = PHASE_3_FORM_TYPES + setOf(
        // Phase 4: S-1 / S-3 / S-4 can be extremely long (hundreds of pages)
        "S-1", "S-1/A",
        "S-3", "S-3/A",
        "S-4", "S-4/A",
        // Phase 6: 10-K / 10-Q / 20-F / 40-F are long-form financial reports
        "10-K", "10-K/A",
        "10-Q", "10-Q/A",
        "20-F", "20-F/A",
        "40-F", "40-F/A"
    )

    // --------------- CIK Resolution ---------------

    suspend fun resolveCikForTicker(ticker: String): String? = withContext(Dispatchers.IO) {
        val normalized = ticker.trim().uppercase()
        val cache = getOrLoadCikCache() ?: return@withContext null
        cache[normalized]
    }

    private suspend fun getOrLoadCikCache(): Map<String, String>? {
        cikCacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cikCache != null && (now - cikCacheTimestamp) < CIK_CACHE_TTL_MS) {
                return cikCache
            }
            return try {
                val request = Request.Builder()
                    .url("https://www.sec.gov/files/company_tickers.json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "company_tickers.json fetch failed: ${response.code()}")
                    return cikCache
                }
                val body = response.body()?.string() ?: return cikCache
                val parsed: Map<String, JsonObject> = gson.fromJson(
                    body, object : TypeToken<Map<String, JsonObject>>() {}.type
                )
                val map = mutableMapOf<String, String>()
                for ((_, entry) in parsed) {
                    val t = safeJsonString(entry.get("ticker"))?.uppercase() ?: continue
                    val cik = safeJsonCik(entry.get("cik_str")) ?: continue
                    map[t] = cik
                }
                cikCache = map
                cikCacheTimestamp = now
                Log.d(TAG, "Loaded ${map.size} ticker→CIK mappings")
                map
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load company_tickers.json", e)
                cikCache
            }
        }
    }

    // --------------- News Forms Recent Filings Metadata (Stage 1 — cheap, no documents) ---------------

    /**
     * Returns up to [limit] recent SEC news-form filing metadata entries for the given CIK.
     *
     * Included forms (Phase 1 + Phase 2):
     * - 8-K / 6-K (and amendments)
     * - Ownership / insider activity filings (SC 13D/13G, Forms 3/4/5, Form 144, 13F-HR)
     *
     * Reuses the submissions JSON already fetched by this service; no extra network call for document bodies.
     * Item numbers are extracted via regex before any AI call (when present on filings metadata as `items`).
     *
     * @param formTypesFilter Optional set of form types to include. Defaults to ALL_NEWS_FORM_TYPES.
     *                        Can also pass FINANCIAL_REPORT_FORM_TYPES for 10-K/10-Q analysis.
     */
    suspend fun getRecentFilingsMetadata(
        cik: String,
        limit: Int = 20,
        formTypesFilter: Set<String> = ALL_NEWS_FORM_TYPES
    ): List<SecFilingMeta> = withContext(Dispatchers.IO) {
        DiagnosticsLogger.log(
            null,
            "NEWS_META_FETCH",
            "Fetching filings metadata for CIK=$cik limit=$limit"
        )
        try {
            val paddedCik = cik.padStart(10, '0')
            val request = Request.Builder()
                .url("https://data.sec.gov/submissions/CIK$paddedCik.json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                DiagnosticsLogger.log(
                    null,
                    "8K_META_FAIL",
                    "submissions fetch failed: ${response.code()}"
                )
                return@withContext emptyList()
            }
            val body = response.body()?.string() ?: return@withContext emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val recent = json.getAsJsonObject("filings")
                ?.getAsJsonObject("recent")
                ?: return@withContext emptyList()

            val forms = recent.getAsJsonArray("form") ?: return@withContext emptyList()
            val dates = recent.getAsJsonArray("filingDate")
            val accessions = recent.getAsJsonArray("accessionNumber")
            val primaryDocs = recent.getAsJsonArray("primaryDocument")
            val itemsArr = recent.getAsJsonArray("items")

            val results = mutableListOf<SecFilingMeta>()
            for (i in 0 until forms.size()) {
                val formType = safeJsonString(forms.get(i)) ?: continue
                if (formType !in formTypesFilter) continue

                val filingDate = safeJsonString(dates?.get(i)) ?: ""
                val accession = safeJsonString(accessions?.get(i)) ?: continue
                val primaryDoc = safeJsonString(primaryDocs?.get(i))
                val itemsRaw = safeJsonString(itemsArr?.get(i))

                val meta = SecFilingMeta(
                    formType = formType,
                    filingDate = filingDate,
                    accessionNumber = accession,
                    primaryDocument = primaryDoc,
                    itemsRaw = itemsRaw,
                    cik = cik,
                    secFolderUrl = SecFilingMeta.Companion.buildSecFolderUrl(cik, accession)
                )
                results.add(meta)
                if (results.size >= limit) break
            }
            DiagnosticsLogger.log(
                null,
                "NEWS_META_OK",
                "Found ${results.size} SEC news-form filings for CIK=$cik"
            )
            results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch news-form metadata for CIK $cik", e)
            DiagnosticsLogger.log(null, "NEWS_META_ERROR", "Exception: ${e.message}")
            emptyList()
        }
    }

    // --------------- 8-K Content Fetching (Stage 2 — on demand only) ---------------

    /**
     * Fetches the primary document and all EX-99 exhibits for a single filing.
     * Uses the EDGAR filing index JSON to locate exhibit documents.
     * The result is never persisted — it is passed to [com.example.stockzilla.sec.NewsAnalyzer] and then discarded.
     */
    suspend fun fetchNewsContent(meta: SecFilingMeta): NewsContent = withContext(Dispatchers.IO) {
        DiagnosticsLogger.log(
            meta.cik,
            "NEWS_CONTENT_FETCH",
            "Fetching content for ${meta.accessionNumber}"
        )

        val indexUrl = "${meta.secFolderUrl}index.json"
        val exhibitUrls = mutableListOf<String>()
        var primaryDocUrl: String? = null
        var firstXmlDocUrl: String? = null
        val isXmlForm = meta.formType in XML_FORM_TYPES

        try {
            val indexRequest = Request.Builder()
                .url(indexUrl)
                .header("Accept", "application/json")
                .build()
            val indexResponse = documentClient.newCall(indexRequest).execute()
            if (indexResponse.isSuccessful) {
                val indexBody = indexResponse.body()?.string()
                if (indexBody != null) {
                    val indexJson = gson.fromJson(indexBody, JsonObject::class.java)
                    val items = indexJson.getAsJsonObject("directory")
                        ?.getAsJsonArray("item")

                    items?.forEach { element ->
                        val item = element.asJsonObject
                        val docName = safeJsonString(item.get("name")) ?: return@forEach
                        val docUrl = "${meta.secFolderUrl}$docName"
                        val lowerDocName = docName.lowercase()

                        when {
                            meta.primaryDocument != null && docName == meta.primaryDocument -> {
                                primaryDocUrl = docUrl
                                DiagnosticsLogger.log(meta.cik, "8K_PRIMARY_DOC", "name=$docName")
                            }

                            isXmlForm && firstXmlDocUrl == null && lowerDocName.endsWith(".xml") -> {
                                // Fallback XML doc in case the primaryDocument filename isn't wired as expected.
                                firstXmlDocUrl = docUrl
                            }

                            isExhibitDocument(docName, meta.primaryDocument) -> {
                                exhibitUrls.add(docUrl)
                                DiagnosticsLogger.log(meta.cik, "8K_EXHIBIT_FOUND", "name=$docName")
                            }
                        }
                    }
                }
                DiagnosticsLogger.log(
                    meta.cik,
                    "8K_INDEX_OK",
                    "Found ${exhibitUrls.size} exhibit documents"
                )
            } else {
                DiagnosticsLogger.log(
                    meta.cik,
                    "8K_INDEX_FAIL",
                    "index.json fetch failed: ${indexResponse.code()}"
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed to fetch index.json for ${meta.accessionNumber}",
                e
            )
            DiagnosticsLogger.log(meta.cik, "8K_INDEX_ERROR", "Exception: ${e.message}")
        }

        if (primaryDocUrl == null && meta.primaryDocument != null) {
            primaryDocUrl = "${meta.secFolderUrl}${meta.primaryDocument}"
        }

        if (isXmlForm) {
            if (primaryDocUrl == null) primaryDocUrl = firstXmlDocUrl

            val rawXml = primaryDocUrl?.let { fetchRawDocument(it, meta.cik, "xml-primary") } ?: ""
            val highlightsJson = if (rawXml.isNotBlank()) {
                SecXmlExtraction.buildFormHighlightsJson(meta.formType, rawXml)
            } else {
                ""
            }
            val strippedText = if (rawXml.isNotBlank()) {
                SecXmlExtraction.stripXmlAndNormalize(rawXml)
            } else {
                ""
            }

            val combined = buildString {
                append("=== SEC FILING XML FORM ${meta.formType} ===\n\n")
                append("=== EXTRACTED HIGHLIGHTS (best-effort) ===\n")
                append(if (highlightsJson.isNotBlank()) highlightsJson else "{}")
                append("\n\n=== XML TEXT (tag-stripped) ===\n")
                append(strippedText)
            }

            val combinedCapped = if (combined.length > MAX_AI_INPUT_CHARS) {
                combined.take(MAX_AI_INPUT_CHARS)
            } else combined

            return@withContext NewsContent(
                meta = meta,
                coverText = null,
                exhibitTexts = emptyList(),
                combinedForAi = combinedCapped
            )
        } else {
            val isLongDocument = meta.formType in LONG_DOCUMENT_FORM_TYPES
            val isLateFilingShortDoc = meta.formType in PHASE_5_FORM_TYPES

            val coverText = primaryDocUrl?.let {
                if (isLongDocument) {
                    fetchAndExtractKeySections(it, meta.cik, "cover", meta.formType)
                } else {
                    fetchAndNormalizeDocument(it, meta.cik, "cover")
                }
            }

            val exhibitTexts =
                if ((isLongDocument || isLateFilingShortDoc) && exhibitUrls.isNotEmpty()) {
                    // For long docs and short late-filing docs, skip exhibits to save tokens.
                    emptyList()
                } else {
                    exhibitUrls.mapNotNull { url ->
                        fetchAndNormalizeDocument(url, meta.cik, "exhibit")
                    }
                }

            val combined = if (isLongDocument && coverText != null) {
                // For long docs, use extracted sections directly (no exhibits-first reordering)
                val header = "=== ${meta.formType} FILING (SUMMARY SECTIONS EXTRACTED) ==="
                val full = "$header\n\n$coverText"
                if (full.length > MAX_AI_INPUT_CHARS) full.take(MAX_AI_INPUT_CHARS) else full
            } else {
                buildCombinedText(coverText, exhibitTexts)
            }

            return@withContext NewsContent(
                meta = meta,
                coverText = coverText,
                exhibitTexts = exhibitTexts,
                combinedForAi = combined
            )
        }
    }

    /** Downloads a document URL using the document client (no JSON Accept header), strips HTML, normalizes. */
    private fun fetchAndNormalizeDocument(url: String, cik: String, label: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = documentClient.newCall(request).execute()
            if (!response.isSuccessful) {
                DiagnosticsLogger.log(cik, "8K_DOC_FAIL", "$label fetch failed: ${response.code()} url=$url")
                return null
            }
            val raw = response.body()?.string() ?: return null
            val normalized = stripHtmlAndNormalize(raw)
            DiagnosticsLogger.log(cik, "8K_DOC_OK", "$label fetched, ${normalized.length} chars")
            normalized
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch document: $url", e)
            DiagnosticsLogger.log(cik, "8K_DOC_ERROR", "$label exception: ${e.message}")
            null
        }
    }

    /** Downloads a document URL using the document client and returns raw body text (no stripping). */
    private fun fetchRawDocument(url: String, cik: String, label: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = documentClient.newCall(request).execute()
            if (!response.isSuccessful) {
                DiagnosticsLogger.log(cik, "NEWS_XML_DOC_FAIL", "$label fetch failed: ${response.code()} url=$url")
                return null
            }
            response.body()?.string()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch raw document: $url", e)
            DiagnosticsLogger.log(cik, "NEWS_XML_DOC_ERROR", "$label exception: ${e.message}")
            null
        }
    }

    /**
     * Fetches a long M&A/proxy document and extracts key sections instead of processing the full file.
     * Phase 3 forms (DEFM14A, SC TO-T, etc.) are 50-200 pages — we need smart extraction to stay within token limits.
     */
    private fun fetchAndExtractKeySections(url: String, cik: String, label: String, formType: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = documentClient.newCall(request).execute()
            if (!response.isSuccessful) {
                DiagnosticsLogger.log(cik, "NEWS_LONG_DOC_FAIL", "$label fetch failed: ${response.code()} url=$url")
                return null
            }
            val rawHtml = response.body()?.string() ?: return null

            // First strip HTML to get readable text
            val normalizedText = stripHtmlAndNormalize(rawHtml)

            // Extract key sections based on form type
            val extractedSections = extractKeySectionsForFormType(normalizedText, formType)

            val result = if (extractedSections.isNotBlank()) {
                extractedSections
            } else {
                // Fallback: take first 20k chars if no sections found
                normalizedText.take(20_000)
            }

            DiagnosticsLogger.log(cik, "NEWS_LONG_DOC_OK", "$label extracted ${result.length} chars from ${normalizedText.length} total")
            result

        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch/extract long document: $url", e)
            DiagnosticsLogger.log(cik, "NEWS_LONG_DOC_ERROR", "$label exception: ${e.message}")
            null
        }
    }

    /**
     * Extracts key sections from long M&A/proxy documents based on form type.
     * Uses regex patterns to find "Summary", "Overview", "Terms", etc. sections.
     */
    private fun extractKeySectionsForFormType(text: String, formType: String): String {
        val sections = mutableListOf<String>()

        // Common section headers for M&A/proxy filings and registration statements
        val sectionPatterns = when (formType) {
            // Phase 4: Dilution / Capital Raises (S-1/S-3/S-4 are typically very long)
            "S-1", "S-1/A", "S-3", "S-3/A", "S-4", "S-4/A" -> listOf(
                // Often appears near the front for registration statements
                "(?i)SUMMARY[^\\n]*".toRegex(),
                // Offering mechanics / how proceeds will be used
                "(?i)USE[^\\n]*PROCEEDS".toRegex(),
                // Dilution section often labeled explicitly as "Dilution"
                "(?i)DILUTION".toRegex(),
                // Plan of distribution (how shares are sold)
                "(?i)PLAN[^\\n]*OF[^\\n]*DISTRIBUTION|UNDERWRITING".toRegex(),
                // Sometimes includes offering economics: capitalization, risk, etc.
                "(?i)CAPITALIZATION".toRegex(),
                "(?i)RISK[^\\n]*FACTORS".toRegex()
            )

            "DEFM14A", "DEFA14A", "PREM14A" -> listOf(
                "(?i)SUMMARY[^\\n]*MERGER|TRANSACTION[^\\n]*SUMMARY".toRegex(),
                "(?i)TERMS[^\\n]*MERGER|MERGER[^\\n]*CONSIDERATION".toRegex(),
                "(?i)QUESTIONS[^\\n]*ANSWERS|Q&amp;A|VOTE[^\\n]*RECOMMENDATION".toRegex(),
                "(?i)FAIRNESS[^\\n]*OPINION|FINANCIAL[^\\n]*ADVISOR".toRegex(),
                "(?i)CONDITIONS[^\\n]*CLOSING".toRegex()
            )
            "SC TO-T", "SC TO-I", "SC TO-T/A", "SC TO-I/A" -> listOf(
                "(?i)SUMMARY[^\\n]*OFFER|OFFER[^\\n]*SUMMARY".toRegex(),
                "(?i)TERMS[^\\n]*OFFER|PRICE[^\\n]*PER[^\\n]*SHARE".toRegex(),
                "(?i)PURPOSE[^\\n]*TRANSACTION|REASONS[^\\n]*OFFER".toRegex(),
                "(?i)CONDITIONS[^\\n]*OFFER".toRegex(),
                "(?i)SOURCE[^\\n]*FUNDS".toRegex()
            )
            "SC 14D9", "SC 14D9/A" -> listOf(
                "(?i)BOARD[^\\n]*RECOMMENDATION|RECOMMENDATION[^\\n]*SHAREHOLDERS".toRegex(),
                "(?i)FAIRNESS[^\\n]*OPINION".toRegex(),
                "(?i)REASONS[^\\n]*RECOMMENDATION".toRegex(),
                "(?i)ALTERNATIVES[^\\n]*CONSIDERED".toRegex()
            )
            "DEF 14A" -> listOf(
                "(?i)EXECUTIVE[^\\n]*COMPENSATION|COMPENSATION[^\\n]*DISCUSSION".toRegex(),
                "(?i)DIRECTOR[^\\n]*NOMINEES|BOARD[^\\n]*NOMINEES".toRegex(),
                "(?i)PROPOSAL[^\\n]*VOTE|MATTERS[^\\n]*VOTE".toRegex(),
                "(?i)AUDIT[^\\n]*COMMITTEE|AUDITOR[^\\n]*FEES".toRegex()
            )
            else -> emptyList()
        }

        // Try to extract each section
        for (pattern in sectionPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val startIndex = match.range.first
                // Extract from match start to next major section (roughly 2000 chars or to next header)
                val sectionText = text.substring(startIndex, minOf(startIndex + 3000, text.length))
                sections.add("=== ${match.value} ===\n${sectionText.trim()}")
            }
        }

        // If we found sections, join them; otherwise return empty to trigger fallback
        return if (sections.isNotEmpty()) {
            sections.joinToString("\n\n").take(40_000) // Cap extracted sections
        } else {
            ""
        }
    }

    /**
     * Strips HTML tags and XBRL/iXBRL metadata from raw SEC document content.
     *
     * Modern SEC filings use Inline XBRL (iXBRL) which embeds thousands of characters of
     * namespace declarations, schema references, and data-tagged values inside <ix:*> elements.
     * Without removing these, the stripped text is unreadable noise that fills the Grok budget
     * before any actual press-release text appears.
     */
    private fun stripHtmlAndNormalize(html: String): String {
        var text = html
        // Remove iXBRL hidden sections (XBRL metadata blocks embedded in HTML)
        text = text.replace(Regex("<ix:hidden[^>]*>[\\s\\S]*?</ix:hidden>", RegexOption.IGNORE_CASE), " ")
        // Remove all XBRL/iXBRL namespace tags but keep their text content
        text = text.replace(Regex("</?ix:[^>]+>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("</?xbrli:[^>]+>", RegexOption.IGNORE_CASE), " ")
        // Remove scripts, styles, SVG, and comment blocks entirely
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<svg[^>]*>[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        // Remove all remaining HTML tags
        text = text.replace(Regex("<[^>]+>"), " ")
        // Decode common HTML entities
        text = text.replace("&nbsp;", " ")
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&ldquo;", "\"")
        text = text.replace("&rdquo;", "\"")
        text = text.replace("&lsquo;", "'")
        text = text.replace("&rsquo;", "'")
        text = text.replace("&mdash;", "—")
        text = text.replace("&ndash;", "–")
        text = text.replace(Regex("&#[0-9]+;"), " ")
        text = text.replace(Regex("&[a-zA-Z0-9]+;"), " ")
        // Collapse excessive whitespace
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        // Remove lines that are pure noise after stripping (e.g. lone punctuation, numbers from XBRL values)
        text = text.lines()
            .filter { line -> line.trim().length > 3 }
            .joinToString("\n")
        return text.trim()
    }

    /**
     * Builds the combined text sent to Grok/Eidos.
     *
     * IMPORTANT ordering: exhibits come FIRST, then the cover page (capped).
     * The cover page often just points to the relevant exhibits and provides no direct news value.
     * The EX-99.1 press release is where the actual catalyst information lives.
     * Putting exhibits first ensures they are always within the char budget even if the cover is verbose.
     */
    private fun buildCombinedText(cover: String?, exhibits: List<String>): String {
        val parts = mutableListOf<String>()

        // Exhibits first — this is the actual news content (press releases, earnings, etc.)
        exhibits.forEachIndexed { i, text ->
            if (text.isNotBlank()) {
                parts.add("=== EXHIBIT ${i + 1} ===\n$text")
            }
        }

        // Cover page last, capped at COVER_MAX_CHARS — it usually just says "see Exhibit 99.1"
        if (!cover.isNullOrBlank()) {
            val cappedCover = if (cover.length > COVER_MAX_CHARS) cover.take(COVER_MAX_CHARS) + "…" else cover
            parts.add("=== SEC FILING COVER PAGE ===\n$cappedCover")
        }

        val combined = parts.joinToString("\n\n")
        DiagnosticsLogger.log(null, "NEWS_COMBINED_TEXT", "exhibits=${exhibits.size} combinedChars=${combined.length} limit=$MAX_AI_INPUT_CHARS")
        return if (combined.length > MAX_AI_INPUT_CHARS) combined.take(MAX_AI_INPUT_CHARS) else combined
    }

    /**
     * Returns true if the filename looks like an exhibit worth reading.
     * EDGAR's index.json "type" field is often generic, so we rely on filename patterns instead.
     *
     * Strategy:
     * - Only consider HTML documents (.htm/.html)
     * - Skip the primary 8-K cover page (handled separately)
     * - Skip known EDGAR boilerplate / viewer pages (e.g., *-index.htm, R1.htm, R2.htm)
     * - Everything else that is HTML is considered an exhibit — if the company filed it, it's worth reading.
     */
    private fun isExhibitDocument(name: String, primaryDocument: String?): Boolean {
        val lower = name.lowercase()
        // Must be an HTML document
        if (!lower.endsWith(".htm") && !lower.endsWith(".html")) return false
        // Skip the primary 8-K cover page (fetched separately)
        if (primaryDocument != null && name == primaryDocument) return false
        // Skip EDGAR infrastructure / boilerplate files
        if (lower.contains("-index")) return false
        // R1.htm, R2.htm etc. — XBRL viewer pages, not substantive exhibits
        if (lower.matches(Regex("r\\d+\\.htm"))) return false
        return true
    }

    // --------------- Submissions (metadata: name, SIC, NAICS) ---------------

    data class SubmissionsInfo(
        val companyName: String?,
        val sic: String?,
        val naics: String?,
        val latestFilingDate: String?
    )

    suspend fun getSubmissionsInfo(cik: String): SubmissionsInfo? = withContext(Dispatchers.IO) {
        try {
            val paddedCik = cik.padStart(10, '0')
            val request = Request.Builder()
                .url("https://data.sec.gov/submissions/CIK$paddedCik.json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body()?.string() ?: return@withContext null
            val json = gson.fromJson(body, JsonObject::class.java)
            val name = safeJsonString(json.get("name"))
            val sic = safeJsonString(json.get("sic"))
            val naics = safeJsonString(json.get("naics"))
            val recentFilings = json.getAsJsonObject("filings")
                ?.getAsJsonObject("recent")
            val filingDates = recentFilings?.getAsJsonArray("filingDate")
            val latestDate = safeJsonString(filingDates?.firstOrNull())
            SubmissionsInfo(
                companyName = name,
                sic = sic,
                naics = naics,
                latestFilingDate = latestDate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch submissions for CIK $cik", e)
            null
        }
    }

    // --------------- Company Facts (XBRL data) ---------------

    suspend fun getCompanyFacts(cik: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val paddedCik = cik.padStart(10, '0')
            val request = Request.Builder()
                .url("https://data.sec.gov/api/xbrl/companyfacts/CIK$paddedCik.json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "companyfacts fetch failed for CIK $cik: ${response.code()}"
                )
                return@withContext null
            }
            val body = response.body()?.string() ?: return@withContext null
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch companyfacts for CIK $cik", e)
            null
        }
    }

    // --------------- Full Pipeline: ticker → StockData ---------------

    suspend fun loadFundamentalsForTicker(ticker: String): Result<StockData> =
        withContext(Dispatchers.IO) {
            DiagnosticsLogger.log(ticker, "EDGAR_START", "Fetching fundamentals")
            val cik = resolveCikForTicker(ticker)
            if (cik == null) {
                DiagnosticsLogger.log(
                    ticker,
                    "EDGAR_CIK_MISSING",
                    "Ticker not in SEC company_tickers.json"
                )
                return@withContext Result.failure(Exception("Could not resolve CIK for $ticker. This ticker may not be listed with the SEC."))
            }
            DiagnosticsLogger.log(ticker, "EDGAR_CIK_OK", "CIK=$cik")

            val facts = getCompanyFacts(cik)
            if (facts == null) {
                DiagnosticsLogger.log(
                    ticker,
                    "EDGAR_FACTS_FAIL",
                    "companyfacts API failed or empty"
                )
                return@withContext Result.failure(Exception("Could not fetch EDGAR data for $ticker (CIK: $cik)"))
            }
            DiagnosticsLogger.log(ticker, "EDGAR_FACTS_OK", "companyfacts loaded")

            val submissions = getSubmissionsInfo(cik)
            val sic = submissions?.sic
            val companyName = submissions?.companyName
            val sector = EdgarConcepts.sectorFromSic(sic)
            val industry = EdgarConcepts.industryFromSic(sic)

            try {
                val stockData = parseCompanyFactsToStockData(
                    ticker, facts, companyName, sic, sector, industry, cik
                )
                val summary = buildEdgarParseSummary(stockData)
                DiagnosticsLogger.log(ticker, "EDGAR_PARSE_OK", summary)
                Result.success(stockData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse EDGAR data for $ticker", e)
                DiagnosticsLogger.log(
                    ticker,
                    "EDGAR_PARSE_FAIL",
                    e.message ?: "parse error",
                    e.javaClass.simpleName
                )
                Result.failure(Exception("Failed to parse EDGAR data for $ticker: ${e.message}"))
            }
        }

    private fun buildEdgarParseSummary(d: StockData): String {
        val parts = mutableListOf<String>()
        if (d.revenue != null) parts.add("revenue(10K)")
        if (d.revenueTtm != null) parts.add("revenue(TTM)")
        if (d.netIncome != null) parts.add("netIncome(10K)")
        if (d.netIncomeTtm != null) parts.add("netIncome(TTM)")
        if (d.outstandingShares != null) parts.add("shares")
        if (d.totalAssets != null) parts.add("assets")
        if (d.freeCashFlow != null) parts.add("fcf(10K)")
        if (d.freeCashFlowTtm != null) parts.add("fcf(TTM)")
        val present = parts.joinToString(",").ifEmpty { "none" }
        val missing = mutableListOf<String>()
        if (d.revenue == null && d.revenueTtm == null) missing.add("revenue")
        if (d.outstandingShares == null) missing.add("shares")
        if (missing.isNotEmpty()) return "present=$present missing=${missing.joinToString(",")}"
        return "present=$present"
    }

    // --------------- Companyfacts Parsing ---------------

    private fun parseCompanyFactsToStockData(
        ticker: String,
        facts: JsonObject,
        companyName: String?,
        sicCode: String?,
        sector: String?,
        industry: String?,
        cik: String?
    ): StockData {
        val factsObj = facts.getAsJsonObject("facts")
        val usGaap = factsObj?.getAsJsonObject("us-gaap")
        val dei = factsObj?.getAsJsonObject("dei")
        val ifrsFull = factsObj?.getAsJsonObject("ifrs-full")

        // --- Income statement ANNUAL (10-K primary) ---
        val revenue = getAnnualValue(usGaap, EdgarConcepts.REVENUE, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.REVENUE, "USD")
        val netIncome = getAnnualValue(usGaap, EdgarConcepts.NET_INCOME, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.NET_INCOME, "USD")
        val ebit = getAnnualValue(usGaap, EdgarConcepts.EBIT, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.EBIT, "USD")
        val depreciation = getAnnualValue(usGaap, EdgarConcepts.DEPRECIATION, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.DEPRECIATION, "USD")
        val ebitdaDirect = getAnnualValue(usGaap, EdgarConcepts.EBITDA, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.EBITDA, "USD")
        val ebitda = ebitdaDirect ?: run {
            if (ebit != null && depreciation != null) ebit + depreciation else ebit
        }
        val costOfGoodsSold = getAnnualValue(usGaap, EdgarConcepts.COST_OF_GOODS, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.COST_OF_GOODS, "USD")
        val directGrossProfit = getAnnualValue(usGaap, EdgarConcepts.GROSS_PROFIT, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.GROSS_PROFIT, "USD")
        val grossProfit = if (revenue != null && costOfGoodsSold != null) {
            revenue - costOfGoodsSold
        } else {
            directGrossProfit
        }
        val eps = getAnnualValue(usGaap, EdgarConcepts.EPS, "USD/shares")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.EPS, "USD/shares")
            ?: getAnnualValue(dei, EdgarConcepts.EPS, "USD/shares")

        // --- Income statement TTM (10-Q only, requires 4 clean quarters) ---
        val revenueTtm = getTtmValue(usGaap, EdgarConcepts.REVENUE, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.REVENUE, "USD")
        val netIncomeTtm = getTtmValue(usGaap, EdgarConcepts.NET_INCOME, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.NET_INCOME, "USD")
        val ebitTtm = getTtmValue(usGaap, EdgarConcepts.EBIT, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.EBIT, "USD")
        val depreciationTtm = getTtmValue(usGaap, EdgarConcepts.DEPRECIATION, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.DEPRECIATION, "USD")
        val ebitdaDirectTtm = getTtmValue(usGaap, EdgarConcepts.EBITDA, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.EBITDA, "USD")
        val ebitdaTtm = ebitdaDirectTtm ?: run {
            if (ebitTtm != null && depreciationTtm != null) ebitTtm + depreciationTtm else ebitTtm
        }
        val costOfGoodsSoldTtm = getTtmValue(usGaap, EdgarConcepts.COST_OF_GOODS, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.COST_OF_GOODS, "USD")
        val epsTtm = getTtmValue(usGaap, EdgarConcepts.EPS, "USD/shares")
            ?: getTtmValue(ifrsFull, EdgarConcepts.EPS, "USD/shares")
            ?: getTtmValue(dei, EdgarConcepts.EPS, "USD/shares")

        // --- Balance sheet (latest instant) ---
        val totalAssets = getLatestInstantValue(usGaap, EdgarConcepts.TOTAL_ASSETS_LIST, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.TOTAL_ASSETS_LIST, "USD")
        val totalLiabilities = getLatestInstantValue(usGaap, EdgarConcepts.TOTAL_LIABILITIES_LIST, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.TOTAL_LIABILITIES_LIST, "USD")
        val currentAssets = getLatestInstantValue(usGaap, EdgarConcepts.CURRENT_ASSETS_LIST, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.CURRENT_ASSETS_LIST, "USD")
        val currentLiabilities = getLatestInstantValue(usGaap, EdgarConcepts.CURRENT_LIABILITIES_LIST, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.CURRENT_LIABILITIES_LIST, "USD")
        val retainedEarnings = getLatestInstantValue(usGaap, EdgarConcepts.RETAINED_EARNINGS_LIST, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.RETAINED_EARNINGS_LIST, "USD")
        val totalEquity = getLatestInstantValue(usGaap, EdgarConcepts.STOCKHOLDERS_EQUITY, "USD")
            ?: getLatestInstantValue(ifrsFull, EdgarConcepts.STOCKHOLDERS_EQUITY, "USD")
        val totalDebt = getDebtFromBalanceSheet(usGaap) ?: getDebtFromBalanceSheet(ifrsFull)
        val sharesFromEdgar = getSharesOutstanding(usGaap, dei) ?: getSharesOutstanding(ifrsFull, dei)

        // --- Cash flow ANNUAL (10-K) ---
        val operatingCashFlow = getAnnualValue(usGaap, EdgarConcepts.OPERATING_CASH_FLOW, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.OPERATING_CASH_FLOW, "USD")
        val capex = getAnnualValue(usGaap, EdgarConcepts.CAPEX, "USD")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.CAPEX, "USD")
        val freeCashFlow = if (operatingCashFlow != null && capex != null) {
            operatingCashFlow - abs(capex)
        } else operatingCashFlow

        // --- Cash flow TTM (10-Q) ---
        val operatingCashFlowTtm = getTtmValue(usGaap, EdgarConcepts.OPERATING_CASH_FLOW, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.OPERATING_CASH_FLOW, "USD")
        val capexTtm = getTtmValue(usGaap, EdgarConcepts.CAPEX, "USD")
            ?: getTtmValue(ifrsFull, EdgarConcepts.CAPEX, "USD")
        val freeCashFlowTtm = if (operatingCashFlowTtm != null && capexTtm != null) {
            operatingCashFlowTtm - abs(capexTtm)
        } else if (operatingCashFlowTtm != null) operatingCashFlowTtm else null

        val workingCapital = if (currentAssets != null && currentLiabilities != null) {
            currentAssets - currentLiabilities
        } else null

        // --- Annual history (10-K only) ---
        val revenueHistory = getAnnualHistory(usGaap, EdgarConcepts.REVENUE, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.REVENUE, "USD", 5)
        val netIncomeHistory = getAnnualHistory(usGaap, EdgarConcepts.NET_INCOME, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.NET_INCOME, "USD", 5)
        val ebitdaHistory = getAnnualHistory(usGaap, EdgarConcepts.EBIT, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.EBIT, "USD", 5)
        val totalAssetsHistory = getAnnualHistory(usGaap, EdgarConcepts.TOTAL_ASSETS_LIST, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.TOTAL_ASSETS_LIST, "USD", 5)
        val currentAssetsHistory = getAnnualHistory(usGaap, EdgarConcepts.CURRENT_ASSETS_LIST, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.CURRENT_ASSETS_LIST, "USD", 5)
        val currentLiabilitiesHistory = getAnnualHistory(usGaap, EdgarConcepts.CURRENT_LIABILITIES_LIST, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.CURRENT_LIABILITIES_LIST, "USD", 5)
        val longTermDebtHistory = getAnnualHistory(usGaap, EdgarConcepts.LONG_TERM_DEBT, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.LONG_TERM_DEBT, "USD", 5)
        val costOfGoodsSoldHistory = getAnnualHistory(usGaap, EdgarConcepts.COST_OF_GOODS, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.COST_OF_GOODS, "USD", 5)
        val directGrossProfitHistory = getAnnualHistory(usGaap, EdgarConcepts.GROSS_PROFIT, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.GROSS_PROFIT, "USD", 5)
        val grossProfitHistory = (0 until maxOf(revenueHistory.size, costOfGoodsSoldHistory.size, directGrossProfitHistory.size)).map { i ->
            val revenuePoint = revenueHistory.getOrNull(i)
            val cogsPoint = costOfGoodsSoldHistory.getOrNull(i)
            val directPoint = directGrossProfitHistory.getOrNull(i)
            if (revenuePoint != null && cogsPoint != null) {
                revenuePoint - cogsPoint
            } else {
                directPoint
            }
        }
        val operatingCashFlowHistory = getAnnualHistory(usGaap, EdgarConcepts.OPERATING_CASH_FLOW, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.OPERATING_CASH_FLOW, "USD", 5)
        val capexHistory = getAnnualHistory(usGaap, EdgarConcepts.CAPEX, "USD", 5)
            .takeIf { it.any { v -> v != null } } ?: getAnnualHistory(ifrsFull, EdgarConcepts.CAPEX, "USD", 5)
        val freeCashFlowHistory = (0 until maxOf(operatingCashFlowHistory.size, capexHistory.size)).map { i ->
            val ocf = operatingCashFlowHistory.getOrNull(i)
            val cx = capexHistory.getOrNull(i)
            if (ocf != null && cx != null) ocf - abs(cx) else null
        }
        val sharesOutstandingHistory = getSharesOutstandingHistory(usGaap, dei, 5)

        // --- Growth from ANNUAL history only (no scalar TTM fallbacks) ---
        val revenueGrowth = calculateLatestGrowth(revenueHistory)
        val averageRevenueGrowth = calculateAverageGrowth(revenueHistory) ?: revenueGrowth
        val averageNetIncomeGrowth = calculateAverageGrowth(netIncomeHistory)
        val netIncomeGrowth = calculateLatestGrowth(netIncomeHistory)
        val fcfGrowth = calculateLatestGrowth(freeCashFlowHistory)
        val averageFcfGrowth = calculateAverageGrowth(freeCashFlowHistory) ?: fcfGrowth

        // Margins use TTM-preferred values
        val effectiveRevenue = revenueTtm ?: revenue
        val effectiveEbitda = ebitdaTtm ?: ebitda
        val effectiveFcf = freeCashFlowTtm ?: freeCashFlow

        val freeCashFlowMargin = if (effectiveFcf != null && effectiveRevenue != null && abs(
                effectiveRevenue
            ) > 1e-9) {
            effectiveFcf / effectiveRevenue
        } else null

        val latestEbitdaMargin = if (effectiveEbitda != null && effectiveRevenue != null && abs(
                effectiveRevenue
            ) > 1e-9) {
            effectiveEbitda / effectiveRevenue
        } else null
        val previousRevenue = revenueHistory.getOrNull(1)
        val previousEbitda = ebitdaHistory.getOrNull(1)
        val previousEbitdaMargin = if (previousEbitda != null && previousRevenue != null && abs(
                previousRevenue
            ) > 1e-9) {
            previousEbitda / previousRevenue
        } else null
        val ebitdaMarginGrowth = if (latestEbitdaMargin != null && previousEbitdaMargin != null) {
            latestEbitdaMargin - previousEbitdaMargin
        } else null

        // Use TTM-preferred net income for ROE so it reflects the most recent 12 months
        val effectiveNetIncome = netIncomeTtm ?: netIncome

        return StockData(
            symbol = ticker,
            companyName = companyName,
            price = null,
            marketCap = null,
            revenue = revenue,
            netIncome = netIncome,
            eps = eps,
            peRatio = null,
            psRatio = null,
            roe = if (effectiveNetIncome != null && totalEquity != null && totalEquity > 0) effectiveNetIncome / totalEquity else null,
            debtToEquity = if (totalDebt != null && totalEquity != null && totalEquity > 0) totalDebt / totalEquity else null,
            freeCashFlow = freeCashFlow,
            pbRatio = null,
            ebitda = ebitda,
            costOfGoodsSold = costOfGoodsSold,
            grossProfit = grossProfit,
            outstandingShares = sharesFromEdgar,
            totalAssets = totalAssets,
            totalLiabilities = totalLiabilities,
            totalCurrentAssets = currentAssets,
            totalCurrentLiabilities = currentLiabilities,
            retainedEarnings = retainedEarnings,
            netCashProvidedByOperatingActivities = operatingCashFlow,
            workingCapital = workingCapital,
            sector = sector,
            industry = industry,
            sicCode = sicCode,
            cik = cik,
            revenueGrowth = revenueGrowth,
            averageRevenueGrowth = averageRevenueGrowth,
            averageNetIncomeGrowth = averageNetIncomeGrowth,
            netIncomeGrowth = netIncomeGrowth,
            fcfGrowth = fcfGrowth,
            averageFcfGrowth = averageFcfGrowth,
            totalAssetsHistory = totalAssetsHistory,
            totalCurrentAssetsHistory = currentAssetsHistory,
            totalCurrentLiabilitiesHistory = currentLiabilitiesHistory,
            longTermDebtHistory = longTermDebtHistory,
            freeCashFlowMargin = freeCashFlowMargin,
            ebitdaMarginGrowth = ebitdaMarginGrowth,
            revenueHistory = revenueHistory,
            netIncomeHistory = netIncomeHistory,
            ebitdaHistory = ebitdaHistory,
            costOfGoodsSoldHistory = costOfGoodsSoldHistory,
            grossProfitHistory = grossProfitHistory,
            operatingCashFlowHistory = operatingCashFlowHistory,
            freeCashFlowHistory = freeCashFlowHistory,
            sharesOutstandingHistory = sharesOutstandingHistory,
            revenueTtm = revenueTtm,
            netIncomeTtm = netIncomeTtm,
            epsTtm = epsTtm,
            ebitdaTtm = ebitdaTtm,
            costOfGoodsSoldTtm = costOfGoodsSoldTtm,
            freeCashFlowTtm = freeCashFlowTtm,
            operatingCashFlowTtm = operatingCashFlowTtm
        )
    }

    /** Annual history for shares outstanding (instant facts from 10-K). Tries us-gaap and dei with "shares" and "pure" units. */
    private fun getSharesOutstandingHistory(usGaap: JsonObject?, dei: JsonObject?, years: Int): List<Double?> {
        for (unit in listOf("shares", "pure")) {
            getAnnualHistorySingle(usGaap, EdgarConcepts.SHARES_OUTSTANDING, unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
            getAnnualHistorySingle(dei, EdgarConcepts.SHARES_OUTSTANDING, unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
        }
        return emptyList()
    }

    /** Safely get string from JSON (handles both string and number for SEC API). */
    private fun safeJsonString(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return try {
            if (element is JsonPrimitive) {
                if (element.isString) element.asString else element.toString()
            } else element.asString
        } catch (_: Exception) { null }
    }

    /** Safely get CIK as string (SEC company_tickers often has cik_str as integer). */
    private fun safeJsonCik(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return try {
            if (element is JsonPrimitive) {
                if (element.isNumber) element.asInt.toString() else element.asString
            } else element.asString
        } catch (_: Exception) { null }
    }

    /** Safely get double from JSON (treats JsonNull/non-number as null). */
    private fun safeJsonDouble(element: JsonElement?): Double? {
        if (element == null || element.isJsonNull) return null
        return try {
            when (element) {
                is JsonPrimitive -> when {
                    element.isNumber -> element.asDouble
                    element.isString -> element.asString.toDoubleOrNull()
                    else -> null
                }
                else -> element.asDouble
            }
        } catch (_: Exception) { null }
    }

    // --------------- XBRL Extraction Helpers ---------------

    /** Try USD first, then CAD/EUR with conversion to USD (for filers like SNDL). Approximate rates. */
    private val MONETARY_UNITS_TO_USD = listOf(
        "USD" to 1.0,
        "CAD" to 0.74,
        "EUR" to 1.08
    )

    /**
     * True if this fact is entity-wide (consolidated). Facts with segment/dimension breakdowns
     * (e.g. by product or geography) have a non-empty "dimensions" field and must be excluded
     * so we use total revenue/income, not a single segment's value.
     */
    private fun isConsolidatedFact(fact: JsonObject): Boolean {
        if (!fact.has("dimensions")) return true
        val d = fact.get("dimensions") ?: return true
        return when (d) {
            is JsonObject -> d.size() == 0
            is JsonArray -> d.size() == 0
            else -> true
        }
    }

    /** Latest annual value from 10-K/20-F/40-F filings. FX-aware for USD. */
    private fun getAnnualValue(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        if (unit == "USD") {
            for ((u, rateToUsd) in MONETARY_UNITS_TO_USD) {
                getAnnualValueSingle(taxonomy, conceptTags, u)?.let { value ->
                    if (value.isFinite()) return value * rateToUsd
                }
            }
            return null
        }
        return getAnnualValueSingle(taxonomy, conceptTags, unit)
    }

    private fun getAnnualValueSingle(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        val annualForms = setOf("10-K", "40-F", "20-F")
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue

            val annualFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { fact -> safeJsonString(fact.get("form")) in annualForms }
                .sortedByDescending { safeJsonString(it.get("end")) ?: "" }

            if (annualFacts.isNotEmpty()) {
                val value = safeJsonDouble(annualFacts.first().get("val"))
                if (value != null && value.isFinite()) return value
            }
        }
        return null
    }

    /** TTM from exactly 4 standalone 10-Q quarters. Returns null if fewer than 4 available. FX-aware for USD. */
    private fun getTtmValue(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        if (unit == "USD") {
            for ((u, rateToUsd) in MONETARY_UNITS_TO_USD) {
                getTtmValueSingle(taxonomy, conceptTags, u)?.let { value ->
                    if (value.isFinite()) return value * rateToUsd
                }
            }
            return null
        }
        return getTtmValueSingle(taxonomy, conceptTags, unit)
    }

    private fun daysBetween(start: String?, end: String?): Long? {
        if (start.isNullOrBlank() || end.isNullOrBlank()) return null
        return try {
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            ChronoUnit.DAYS.between(LocalDate.parse(start, fmt), LocalDate.parse(end, fmt))
        } catch (_: Exception) { null }
    }

    private fun getTtmValueSingle(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue

            val quarterlyFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { fact ->
                    val form = safeJsonString(fact.get("form")) ?: ""
                    val fp = safeJsonString(fact.get("fp")) ?: ""
                    (form == "10-Q" && fp.startsWith("Q")) || (form == "6-K" && fp.startsWith("Q"))
                }
                .sortedByDescending { safeJsonString(it.get("end")) ?: "" }

            if (quarterlyFacts.size < 4) continue

            val standaloneQuarters = quarterlyFacts.filter { fact ->
                val d = daysBetween(safeJsonString(fact.get("start")), safeJsonString(fact.get("end")))
                d != null && d in 60..140
            }

            if (standaloneQuarters.size >= 4) {
                val sum = standaloneQuarters.take(4).sumOf { safeJsonDouble(it.get("val")) ?: 0.0 }
                if (sum.isFinite() && sum != 0.0) return sum
            }
        }
        return null
    }

    private fun getLatestInstantValue(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        if (unit == "USD") {
            for ((u, rateToUsd) in MONETARY_UNITS_TO_USD) {
                getLatestInstantValueSingle(taxonomy, conceptTags, u)?.let { value ->
                    if (value.isFinite()) return value * rateToUsd
                }
            }
            return null
        }
        return getLatestInstantValueSingle(taxonomy, conceptTags, unit)
    }

    private fun getLatestInstantValueSingle(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String
    ): Double? {
        if (taxonomy == null) return null
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue

            val validForms = setOf("10-K", "10-Q", "40-F", "20-F", "6-K")
            val sortedFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { fact ->
                    val form = safeJsonString(fact.get("form")) ?: ""
                    form in validForms
                }
                .sortedByDescending { safeJsonString(it.get("end")) ?: "" }

            for (fact in sortedFacts) {
                val value = safeJsonDouble(fact.get("val"))
                if (value != null && value.isFinite()) return value
            }
        }
        return null
    }

    /** Tries usGaap and dei, and units "shares" then "pure" (SEC uses both for share counts). */
    private fun getSharesOutstanding(usGaap: JsonObject?, dei: JsonObject?): Double? {
        for (unit in listOf("shares", "pure")) {
            getLatestInstantValue(usGaap, EdgarConcepts.SHARES_OUTSTANDING, unit)?.let { if (it > 0) return it }
            getLatestInstantValue(dei, EdgarConcepts.SHARES_OUTSTANDING, unit)?.let { if (it > 0) return it }
        }
        return null
    }

    private fun getDebtFromBalanceSheet(usGaap: JsonObject?): Double? {
        if (usGaap == null) return null
        var total = 0.0
        var found = false
        for (tag in EdgarConcepts.TOTAL_DEBT) {
            val v = getLatestInstantValue(usGaap, listOf(tag), "USD")
            if (v != null) {
                total += v
                found = true
            }
        }
        return if (found) total else null
    }

    private fun getAnnualHistory(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String,
        years: Int
    ): List<Double?> {
        if (taxonomy == null) return emptyList()
        if (unit == "USD") {
            for ((u, rateToUsd) in MONETARY_UNITS_TO_USD) {
                val list = getAnnualHistorySingle(taxonomy, conceptTags, u, years)
                if (list.any { it != null }) {
                    return list.map { it?.let { v -> v * rateToUsd } }
                }
            }
            return emptyList()
        }
        return getAnnualHistorySingle(taxonomy, conceptTags, unit, years)
    }

    private fun getAnnualHistorySingle(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String,
        years: Int
    ): List<Double?> {
        if (taxonomy == null) return emptyList()
        var bestResult: List<Double> = emptyList()
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue

            val annualFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { safeJsonString(it.get("form")) in setOf("10-K", "40-F", "20-F") }
                .sortedByDescending { safeJsonString(it.get("end")) ?: "" }

            val byFy = linkedMapOf<Int, Double>()
            for (fact in annualFacts) {
                val fy = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt
                    ?: yearFromEndDate(safeJsonString(fact.get("end")))
                    ?: continue
                val value = safeJsonDouble(fact.get("val")) ?: continue
                if (value.isFinite() && !byFy.containsKey(fy)) {
                    byFy[fy] = value
                }
                if (byFy.size >= years) break
            }
            if (byFy.size > bestResult.size) {
                bestResult = byFy.values.toList()
            }
        }
        return bestResult
    }

    /** Derive fiscal year from period end date string (e.g. "2023-09-30" -> 2023). */
    private fun yearFromEndDate(end: String?): Int? {
        if (end.isNullOrBlank() || end.length < 4) return null
        return end.take(4).toIntOrNull()
    }

    // --------------- Growth Calculations ---------------

    private fun calculateAverageGrowth(values: List<Double?>): Double? {
        if (values.size < 2) return null
        val growthRates = mutableListOf<Double>()
        for (i in 0 until values.size - 1) {
            val current = values[i]
            val previous = values[i + 1]
            if (current != null && previous != null && abs(previous) > 1e-9) {
                growthRates.add((current - previous) / abs(previous))
            }
        }
        return if (growthRates.isNotEmpty()) growthRates.average() else null
    }

    private fun calculateLatestGrowth(values: List<Double?>): Double? {
        if (values.size < 2) return null
        val current = values[0]
        val previous = values[1]
        if (current != null && previous != null && abs(previous) > 1e-9) {
            return (current - previous) / abs(previous)
        }
        return null
    }

    // --------------- Factory ---------------

    companion object {
        /** Maximum combined characters sent to Grok/Eidos for 8-K analysis. */
        const val MAX_AI_INPUT_CHARS = 80_000

        /**
         * Maximum characters used for the 8-K cover page in the combined AI prompt.
         * The cover page usually just says "See Exhibit 99.1" — exhibits get the bulk of the budget.
         */
        const val COVER_MAX_CHARS = 6_000

        /** Financial report forms (10-K, 10-Q, etc.) - accessible for financial report analysis */
        val FINANCIAL_REPORT_FORM_TYPES = setOf(
            "10-K", "10-K/A",
            "10-Q", "10-Q/A",
            "20-F", "20-F/A",
            "40-F", "40-F/A"
        )

        @Volatile
        private var INSTANCE: SecEdgarService? = null

        fun getInstance(): SecEdgarService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create().also { INSTANCE = it }
            }
        }

        private fun create(): SecEdgarService {
            // JSON API client — sets Accept: application/json for submissions/companyfacts endpoints
            val apiClient = OkHttpClient.Builder()
                .addInterceptor(SecUserAgentInterceptor())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            // Document client — User-Agent only, no Accept override.
            // SEC EDGAR Archives serve HTML files and index.json without strict content negotiation,
            // but sending Accept: application/json can cause issues for exhibit HTML documents.
            val docClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", "Stockzilla/1.0 (contact@stockzilla.app)")
                            .build()
                    )
                }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // exhibits can be large press-release HTML
                .build()

            return SecEdgarService(apiClient, docClient, Gson())
        }
    }
}