package com.example.stockzilla.data

import android.util.Log
import com.example.stockzilla.sec.SecXmlExtraction
import com.example.stockzilla.feature.DiagnosticsLogger
import com.example.stockzilla.scoring.StockData
import com.example.stockzilla.sec.EdgarConcepts
import com.example.stockzilla.sec.EdgarMetricKey
import com.example.stockzilla.sec.EdgarTaxonomy
import com.example.stockzilla.sec.NewsContent
import com.example.stockzilla.sec.SecFilingMeta
import com.example.stockzilla.sec.SecViewerUrls
import com.example.stockzilla.sec.TagOverrideResolver
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

/** Flow facts ~one fiscal quarter (standalone or narrowest Q1). */
private val FLOW_STANDALONE_DAYS = 35L..140L

/** Flow facts ~two quarters YTD (Q2 filing six-month cumulative). */
private val FLOW_SIX_MONTH_DAYS = 125L..245L

/** Flow facts ~three quarters YTD (Q3 filing nine-month cumulative). */
private val FLOW_NINE_MONTH_DAYS = 200L..355L

/** Accept duration facts from quarterly filings; exclude obvious annual strips. */
private val FLOW_QUARTERLY_RAW_MAX_DAYS = 370L

/** One row from `submissions.filings.recent` for periodic financial forms. */
data class RecentFinancialFilingRow(
    val form: String,
    val filingDate: String,
    val reportDate: String?,
    val accessionNumber: String,
    val primaryDocument: String?
)

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

    private val PERIODIC_FORMS_QUARTERLY = setOf("10-Q", "10-Q/A", "6-K", "6-K/A")
    private val PERIODIC_FORMS_ANNUAL = setOf("10-K", "10-K/A", "20-F", "20-F/A", "40-F", "40-F/A")

    /**
     * Parses `filings.recent` for financial forms with optional [reportDate] (period of report).
     */
    suspend fun fetchRecentFinancialFilingRows(cik: String, maxRows: Int = 400): List<RecentFinancialFilingRow> =
        withContext(Dispatchers.IO) {
            try {
                val paddedCik = cik.padStart(10, '0')
                val request = Request.Builder()
                    .url("https://data.sec.gov/submissions/CIK$paddedCik.json")
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body()?.string() ?: return@withContext emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)
                val recent = json.getAsJsonObject("filings")?.getAsJsonObject("recent")
                    ?: return@withContext emptyList()
                val forms = recent.getAsJsonArray("form") ?: return@withContext emptyList()
                val filingDates = recent.getAsJsonArray("filingDate")
                val accessions = recent.getAsJsonArray("accessionNumber")
                val primaryDocs = recent.getAsJsonArray("primaryDocument")
                val reportDates = recent.getAsJsonArray("reportDate")
                val out = mutableListOf<RecentFinancialFilingRow>()
                for (i in 0 until forms.size()) {
                    val form = safeJsonString(forms.get(i)) ?: continue
                    if (form !in PERIODIC_FORMS_QUARTERLY && form !in PERIODIC_FORMS_ANNUAL) continue
                    val acc = safeJsonString(accessions?.get(i)) ?: continue
                    out.add(
                        RecentFinancialFilingRow(
                            form = form,
                            filingDate = safeJsonString(filingDates?.get(i)) ?: "",
                            reportDate = safeJsonString(reportDates?.get(i)),
                            accessionNumber = acc,
                            primaryDocument = safeJsonString(primaryDocs?.get(i))
                        )
                    )
                    if (out.size >= maxRows) break
                }
                out
            } catch (e: Exception) {
                Log.e(TAG, "fetchRecentFinancialFilingRows failed for CIK $cik", e)
                emptyList()
            }
        }

    /**
     * Best-effort filing for a fiscal column: match [reportDate] to [periodEnd], else closest prior report.
     * Q4 prefers annual forms; Q1–Q3 prefer 10-Q / 6-K.
     */
    suspend fun resolveFilingForFiscalQuarter(
        cik: String,
        periodEnd: String,
        fiscalYear: Int,
        fiscalQuarter: String
    ): SecFilingMeta? {
        val fq = fiscalQuarter.uppercase().trim()
        val rows = fetchRecentFinancialFilingRows(cik, 500)
        if (rows.isEmpty()) return null
        val wantAnnual = fq == "Q4"
        val candidates = rows.filter { row ->
            when {
                wantAnnual -> row.form in PERIODIC_FORMS_ANNUAL
                else -> row.form in PERIODIC_FORMS_QUARTERLY
            }
        }.ifEmpty { rows }
        val exact = candidates.firstOrNull { it.reportDate == periodEnd }
        if (exact != null) {
            return SecFilingMeta(
                formType = exact.form,
                filingDate = exact.filingDate,
                accessionNumber = exact.accessionNumber,
                primaryDocument = exact.primaryDocument,
                itemsRaw = null,
                cik = cik.trimStart('0').padStart(10, '0'),
                secFolderUrl = SecFilingMeta.buildSecFolderUrl(cik, exact.accessionNumber)
            )
        }
        val pe = try {
            LocalDate.parse(periodEnd.take(10), DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            null
        }
        if (pe != null) {
            val withDates = candidates.mapNotNull { row ->
                val rd = row.reportDate?.take(10)?.let {
                    try {
                        LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE)
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@mapNotNull null
                row to rd
            }
            val beforeOrEq = withDates.filter { !it.second.isAfter(pe) }.maxByOrNull { it.second }
            val pick = beforeOrEq ?: withDates.minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(it.second, pe)) }
            if (pick != null) {
                val row = pick.first
                return SecFilingMeta(
                    formType = row.form,
                    filingDate = row.filingDate,
                    accessionNumber = row.accessionNumber,
                    primaryDocument = row.primaryDocument,
                    itemsRaw = null,
                    cik = cik.trimStart('0').padStart(10, '0'),
                    secFolderUrl = SecFilingMeta.buildSecFolderUrl(cik, row.accessionNumber)
                )
            }
        }
        val fyMatch = candidates.firstOrNull {
            it.reportDate?.startsWith(fiscalYear.toString()) == true
        } ?: candidates.firstOrNull()
        return fyMatch?.let { row ->
            SecFilingMeta(
                formType = row.form,
                filingDate = row.filingDate,
                accessionNumber = row.accessionNumber,
                primaryDocument = row.primaryDocument,
                itemsRaw = null,
                cik = cik.trimStart('0').padStart(10, '0'),
                secFolderUrl = SecFilingMeta.buildSecFolderUrl(cik, row.accessionNumber)
            )
        }
    }

    /**
     * Best-effort 10-K (or other annual form) for a fiscal year column: prefer [reportDate] year matching [fiscalYear].
     */
    suspend fun resolveFilingForFiscalYear(cik: String, fiscalYear: Int): SecFilingMeta? {
        val rows = fetchRecentFinancialFilingRows(cik, 500)
        val candidates = rows.filter { it.form in PERIODIC_FORMS_ANNUAL }
        if (candidates.isEmpty()) return null
        val matched = candidates.filter { row ->
            row.reportDate?.take(4)?.toIntOrNull() == fiscalYear
        }
        val pool = matched.ifEmpty { candidates }
        val row = pool.maxWithOrNull(compareBy<RecentFinancialFilingRow> { it.filingDate }) ?: return null
        return SecFilingMeta(
            formType = row.form,
            filingDate = row.filingDate,
            accessionNumber = row.accessionNumber,
            primaryDocument = row.primaryDocument,
            itemsRaw = null,
            cik = cik.trimStart('0').padStart(10, '0'),
            secFolderUrl = SecFilingMeta.buildSecFolderUrl(cik, row.accessionNumber)
        )
    }

    fun filingViewerUrl(cik: String, accessionNumber: String): String =
        SecViewerUrls.filingViewerUrl(cik, accessionNumber)

    // --------------- Company Facts (XBRL data) ---------------

    /**
     * Small JSON for Eidos: local tag names per SEC taxonomy namespace from **one** companyfacts payload.
     * Keep caps low — this string is embedded in the Grok user message; it is not multiple filings.
     */
    fun buildFactsConceptIndexJson(factsRoot: JsonObject, maxKeysPerTaxonomy: Int = 32): String {
        val facts = factsRoot.getAsJsonObject("facts") ?: return "{\"facts\":null}"
        val out = JsonObject()
        for (tax in listOf(EdgarTaxonomy.US_GAAP, EdgarTaxonomy.IFRS_FULL, EdgarTaxonomy.DEI)) {
            val obj = facts.getAsJsonObject(tax) ?: continue
            val cap = if (tax == EdgarTaxonomy.DEI) minOf(maxKeysPerTaxonomy, 16) else maxKeysPerTaxonomy
            val arr = JsonArray()
            var n = 0
            for (name in obj.keySet().sorted()) {
                if (n++ >= cap) break
                arr.add(name)
            }
            out.add(tax, arr)
        }
        return gson.toJson(out)
    }

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

    suspend fun loadFundamentalsForTicker(
        ticker: String,
        tagOverrides: List<SymbolTagOverrideEntity> = emptyList()
    ): Result<StockData> =
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
                    ticker, facts, companyName, sic, sector, industry, cik, tagOverrides
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

    suspend fun loadQuarterlyFactsForTicker(
        ticker: String,
        tagOverrides: List<SymbolTagOverrideEntity> = emptyList(),
        maxQuarters: Int = 8
    ): Result<List<QuarterlyFinancialFactEntity>> = withContext(Dispatchers.IO) {
        val symbol = ticker.trim().uppercase()
        val cik = resolveCikForTicker(symbol)
            ?: return@withContext Result.failure(Exception("Could not resolve CIK for $symbol"))
        val facts = getCompanyFacts(cik)
            ?: return@withContext Result.failure(Exception("Could not fetch companyfacts for $symbol"))
        return@withContext try {
            Result.success(
                parseQuarterlyFacts(symbol, facts, tagOverrides, maxQuarters)
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class QuarterlyFactPoint(
        val metricKey: EdgarMetricKey,
        val periodStart: String?,
        val periodEnd: String,
        val fiscalYear: Int?,
        val fiscalPeriod: String?,
        val form: String?,
        val unit: String,
        val taxonomy: String,
        val tag: String,
        val value: Double,
        val filedDate: String?,
        val accessionNumber: String? = null
    )

    private data class AnnualFactPoint(
        val fiscalYear: Int,
        val periodEnd: String,
        val form: String?,
        val unit: String,
        val taxonomy: String,
        val tag: String,
        val value: Double,
        val filedDate: String?,
        val accessionNumber: String? = null
    )

    private fun parseQuarterlyFacts(
        symbol: String,
        facts: JsonObject,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFinancialFactEntity> {
        val factsObj = facts.getAsJsonObject("facts")
        val usGaap = factsObj?.getAsJsonObject("us-gaap")
        val dei = factsObj?.getAsJsonObject("dei")
        val ifrsFull = factsObj?.getAsJsonObject("ifrs-full")

        val points = mutableListOf<QuarterlyFactPoint>()
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.REVENUE, EdgarMetricKey.REVENUE, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.GROSS_PROFIT, EdgarMetricKey.GROSS_PROFIT, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.NET_INCOME, EdgarMetricKey.NET_INCOME, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.EBIT, EdgarMetricKey.EBIT, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.DEPRECIATION, EdgarMetricKey.DEPRECIATION, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.OPERATING_CASH_FLOW, EdgarMetricKey.OPERATING_CASH_FLOW, overrides, maxQuarters)
        points += getQuarterlyFlowFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CAPEX, EdgarMetricKey.CAPEX, overrides, maxQuarters)
        points += getQuarterlyInstantFactsWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.SHARES_OUTSTANDING, EdgarMetricKey.SHARES_OUTSTANDING, overrides, maxQuarters)
        points += getQuarterlyTotalDebtPointsWithOverrides(usGaap, ifrsFull, dei, overrides, maxQuarters)

        val derivedQ4Points = mutableListOf<QuarterlyFactPoint>()
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.REVENUE,
            EdgarMetricKey.REVENUE,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.GROSS_PROFIT,
            EdgarMetricKey.GROSS_PROFIT,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.NET_INCOME,
            EdgarMetricKey.NET_INCOME,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.EBIT,
            EdgarMetricKey.EBIT,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.DEPRECIATION,
            EdgarMetricKey.DEPRECIATION,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.OPERATING_CASH_FLOW,
            EdgarMetricKey.OPERATING_CASH_FLOW,
            overrides
        )
        derivedQ4Points += deriveQ4FlowPointsWithOverrides(
            usGaap, ifrsFull, dei,
            EdgarConcepts.CAPEX,
            EdgarMetricKey.CAPEX,
            overrides
        )
        points += derivedQ4Points

        val byPeriod = points.groupBy { it.periodEnd }
        val freeCashFlowPoints = mutableListOf<QuarterlyFactPoint>()
        val ebitdaDerivedPoints = mutableListOf<QuarterlyFactPoint>()
        byPeriod.forEach { (_, periodPoints) ->
            val ocf = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.OPERATING_CASH_FLOW }?.value
            val capex = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.CAPEX }?.value
            if (ocf != null && capex != null) {
                val sample = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.OPERATING_CASH_FLOW }
                if (sample != null) {
                    freeCashFlowPoints += sample.copy(
                        metricKey = EdgarMetricKey.FREE_CASH_FLOW,
                        value = ocf - abs(capex),
                        tag = "DerivedFromOCFAndCAPEX"
                    )
                }
            }
            val oib = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.EBIT }?.value
            val da = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.DEPRECIATION }?.value
            if (oib != null && da != null) {
                val sample = periodPoints.firstOrNull { it.metricKey == EdgarMetricKey.EBIT }
                if (sample != null) {
                    ebitdaDerivedPoints += sample.copy(
                        metricKey = EdgarMetricKey.EBITDA,
                        value = oib + da,
                        tag = "DerivedFromOperatingIncomeAndDepreciation"
                    )
                }
            }
        }

        val now = System.currentTimeMillis()
        return (points + freeCashFlowPoints + ebitdaDerivedPoints)
            .groupBy { Triple(it.metricKey, it.periodEnd, it.fiscalPeriod ?: "") }
            .mapNotNull { (_, variants) ->
                variants.maxWithOrNull(
                    compareBy<QuarterlyFactPoint>(
                        { it.filedDate ?: "" },
                        { it.periodEnd },
                        { it.tag.startsWith("DerivedQ4").compareTo(false) }
                    )
                )
            }
            .sortedByDescending { it.periodEnd }
            .map { point ->
                QuarterlyFinancialFactEntity(
                    symbol = symbol,
                    metricKey = point.metricKey.name,
                    periodStart = point.periodStart,
                    periodEnd = point.periodEnd,
                    fiscalYear = point.fiscalYear,
                    fiscalPeriod = point.fiscalPeriod,
                    form = point.form,
                    unit = point.unit,
                    taxonomy = point.taxonomy,
                    tag = point.tag,
                    value = point.value,
                    filedDate = point.filedDate,
                    accessionNumber = point.accessionNumber,
                    lastUpdated = now
                )
            }
    }

    private fun deriveQ4FlowPointsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>
    ): List<QuarterlyFactPoint> {
        val annualFacts = getAnnualFactsWithOverrides(usGaap, ifrsFull, dei, standard, key, overrides)
        if (annualFacts.isEmpty()) return emptyList()
        val q123 = getQuarterlyFlowFactsWithOverrides(
            usGaap = usGaap,
            ifrsFull = ifrsFull,
            dei = dei,
            standard = standard,
            key = key,
            overrides = overrides,
            maxQuarters = 16
        ).filter { it.fiscalPeriod in setOf("Q1", "Q2", "Q3") }
        if (q123.isEmpty()) return emptyList()

        val byFy = q123.groupBy { it.fiscalYear }
        val derived = mutableListOf<QuarterlyFactPoint>()
        for (annual in annualFacts) {
            val fyRows = byFy[annual.fiscalYear].orEmpty()
            val q1 = fyRows.firstOrNull { it.fiscalPeriod == "Q1" }?.value
            val q2 = fyRows.firstOrNull { it.fiscalPeriod == "Q2" }?.value
            val q3 = fyRows.firstOrNull { it.fiscalPeriod == "Q3" }?.value
            if (q1 == null || q2 == null || q3 == null) continue
            val q4 = annual.value - q1 - q2 - q3
            if (!q4.isFinite()) continue
            val periodStart = annual.periodEnd.takeIf { it.length >= 10 }?.let { end ->
                val year = end.substring(0, 4).toIntOrNull() ?: return@let null
                val month = end.substring(5, 7).toIntOrNull() ?: return@let null
                val day = end.substring(8, 10).toIntOrNull() ?: return@let null
                val endDate = try {
                    LocalDate.of(year, month, day)
                } catch (_: Exception) {
                    return@let null
                }
                endDate.minusMonths(3).plusDays(1).toString()
            }
            derived += QuarterlyFactPoint(
                metricKey = key,
                periodStart = periodStart,
                periodEnd = annual.periodEnd,
                fiscalYear = annual.fiscalYear,
                fiscalPeriod = "Q4",
                form = annual.form,
                unit = annual.unit,
                taxonomy = annual.taxonomy,
                tag = "DerivedQ4FromAnnualMinusQ1Q2Q3:${annual.tag}",
                value = q4,
                filedDate = annual.filedDate,
                accessionNumber = annual.accessionNumber
            )
        }
        return derived
    }

    private fun getAnnualFactsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>,
        maxYears: Int = 6
    ): List<AnnualFactPoint> {
        val base = getAnnualFacts(usGaap, EdgarTaxonomy.US_GAAP, standard, "USD", maxYears)
            .ifEmpty { getAnnualFacts(ifrsFull, EdgarTaxonomy.IFRS_FULL, standard, "USD", maxYears) }
        if (base.isNotEmpty()) return base
        val o = TagOverrideResolver.resolveGlobal(overrides, key) ?: return emptyList()
        val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy) ?: return emptyList()
        return getAnnualFacts(tax, o.taxonomy, listOf(o.tag), "USD", maxYears)
    }

    private fun getAnnualFacts(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        unit: String,
        maxYears: Int
    ): List<AnnualFactPoint> {
        if (taxonomy == null) return emptyList()
        for ((unitCode, fx) in if (unit == "USD") MONETARY_UNITS_TO_USD else listOf(unit to 1.0)) {
            val result = getAnnualFactsSingle(taxonomy, taxonomyName, conceptTags, unitCode, maxYears)
                .map { if (fx == 1.0) it else it.copy(value = it.value * fx) }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun getAnnualFactsSingle(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        unit: String,
        maxYears: Int
    ): List<AnnualFactPoint> {
        if (taxonomy == null) return emptyList()
        val annualForms = setOf("10-K", "20-F", "40-F")
        var bestResult: List<AnnualFactPoint> = emptyList()
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue
            val annualFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { fact -> safeJsonString(fact.get("form")) in annualForms }
                .sortedWith(
                    compareByDescending<JsonObject> { safeJsonString(it.get("end")) ?: "" }
                        .thenByDescending { safeJsonString(it.get("filed")) ?: "" }
                )
            val byFy = linkedMapOf<Int, AnnualFactPoint>()
            for (fact in annualFacts) {
                val fy = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt
                    ?: yearFromEndDate(safeJsonString(fact.get("end")))
                    ?: continue
                if (byFy.containsKey(fy)) continue
                val valNum = safeJsonDouble(fact.get("val")) ?: continue
                if (!valNum.isFinite()) continue
                val periodEnd = safeJsonString(fact.get("end")) ?: continue
                byFy[fy] = AnnualFactPoint(
                    fiscalYear = fy,
                    periodEnd = periodEnd,
                    form = safeJsonString(fact.get("form")),
                    unit = unit,
                    taxonomy = taxonomyName,
                    tag = tag,
                    value = valNum,
                    filedDate = safeJsonString(fact.get("filed")),
                    accessionNumber = factAccession(fact)
                )
                if (byFy.size >= maxYears) break
            }
            val list = byFy.values.toList()
            if (list.size > bestResult.size) bestResult = list
        }
        return bestResult
    }

    private fun getQuarterlyFlowFactsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        val fromOverrides = mergeQuarterlyOverrideFlowPoints(usGaap, ifrsFull, dei, key, overrides, maxQuarters)
        if (fromOverrides.isNotEmpty()) return fromOverrides
        return getQuarterlyFlowFacts(usGaap, EdgarTaxonomy.US_GAAP, standard, key, "USD", maxQuarters)
            .ifEmpty { getQuarterlyFlowFacts(ifrsFull, EdgarTaxonomy.IFRS_FULL, standard, key, "USD", maxQuarters) }
    }

    /** SymbolTagOverrides for [key]: global tag fills quarters, scoped rows replace matching FY:Qn periods. */
    private fun mergeQuarterlyOverrideFlowPoints(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        val rows = overrides.filter { it.metricKey == key.name }
        if (rows.isEmpty()) return emptyList()
        val merged = linkedMapOf<String, QuarterlyFactPoint>()
        val global = rows.firstOrNull { it.scopeKey.isEmpty() }
        if (global != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, global.taxonomy) ?: return emptyList()
            getQuarterlyFlowFacts(tax, global.taxonomy, listOf(global.tag), key, "USD", maxQuarters)
                .forEach { merged[it.periodEnd] = it }
        }
        for (row in rows.filter { it.scopeKey.isNotEmpty() }) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy) ?: continue
            val pts = getQuarterlyFlowFacts(tax, row.taxonomy, listOf(row.tag), key, "USD", maxQuarters)
            for (p in pts) {
                val sk = TagOverrideResolver.formatScopedKey(p.fiscalYear, p.fiscalPeriod)
                if (sk == row.scopeKey) merged[p.periodEnd] = p
            }
        }
        return merged.values.sortedByDescending { it.periodEnd }.take(maxQuarters)
    }

    private fun getQuarterlyInstantFactsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        val fromOverrides = mergeQuarterlyOverrideInstantPoints(usGaap, ifrsFull, dei, standard, key, overrides, maxQuarters)
        if (fromOverrides.isNotEmpty()) return fromOverrides
        return getQuarterlyInstantFacts(usGaap, EdgarTaxonomy.US_GAAP, standard, key, listOf("shares", "pure"), maxQuarters)
            .ifEmpty { getQuarterlyInstantFacts(dei, EdgarTaxonomy.DEI, standard, key, listOf("shares", "pure"), maxQuarters) }
            .ifEmpty { getQuarterlyInstantFacts(ifrsFull, EdgarTaxonomy.IFRS_FULL, standard, key, listOf("shares", "pure"), maxQuarters) }
    }

    private fun mergeQuarterlyOverrideInstantPoints(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        val rows = overrides.filter { it.metricKey == key.name }
        if (rows.isEmpty()) return emptyList()
        val units = listOf("shares", "pure")
        val merged = linkedMapOf<String, QuarterlyFactPoint>()
        val global = rows.firstOrNull { it.scopeKey.isEmpty() }
        if (global != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, global.taxonomy) ?: return emptyList()
            getQuarterlyInstantFacts(tax, global.taxonomy, listOf(global.tag), key, units, maxQuarters)
                .forEach { merged[it.periodEnd] = it }
        }
        for (row in rows.filter { it.scopeKey.isNotEmpty() }) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy) ?: continue
            val pts = getQuarterlyInstantFacts(tax, row.taxonomy, listOf(row.tag), key, units, maxQuarters)
            for (p in pts) {
                val sk = TagOverrideResolver.formatScopedKey(p.fiscalYear, p.fiscalPeriod)
                if (sk == row.scopeKey) merged[p.periodEnd] = p
            }
        }
        return merged.values.sortedByDescending { it.periodEnd }.take(maxQuarters)
    }

    /**
     * Balance-sheet total debt by quarter: sums [EdgarConcepts.TOTAL_DEBT] instant values on each 10-Q/6-K period end,
     * or a single tag when [EdgarMetricKey.TOTAL_DEBT] override is set. (No Q4 synthetic row — use yearly mode for FY debt.)
     */
    private fun getQuarterlyTotalDebtPointsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        val row = TagOverrideResolver.resolveTotalDebt(overrides)
        if (row != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy)
            if (tax != null) {
                val fromOverride = getQuarterlyInstantMonetaryFacts(
                    tax, row.taxonomy, listOf(row.tag), EdgarMetricKey.TOTAL_DEBT, maxQuarters
                )
                if (fromOverride.isNotEmpty()) return fromOverride
            }
        }
        val us = sumQuarterlyInstantDebt(usGaap, EdgarTaxonomy.US_GAAP, maxQuarters)
        if (us.isNotEmpty()) return us
        return sumQuarterlyInstantDebt(ifrsFull, EdgarTaxonomy.IFRS_FULL, maxQuarters)
    }

    private fun getQuarterlyInstantMonetaryFacts(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        metricKey: EdgarMetricKey,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        for ((unitCode, fx) in MONETARY_UNITS_TO_USD) {
            val raw = getQuarterlyInstantFactsSingle(
                taxonomy, taxonomyName, conceptTags, metricKey, unitCode, maxQuarters
            )
            if (raw.isNotEmpty()) {
                return raw.map { p ->
                    if (fx == 1.0) p else p.copy(value = p.value * fx, unit = "USD")
                }
            }
        }
        return emptyList()
    }

    private fun sumQuarterlyInstantDebt(
        taxonomy: JsonObject?,
        taxonomyName: String,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        val sumByEnd = linkedMapOf<String, Double>()
        val templateByEnd = linkedMapOf<String, QuarterlyFactPoint>()
        for (tag in EdgarConcepts.TOTAL_DEBT) {
            unitLoop@ for ((unitCode, fx) in MONETARY_UNITS_TO_USD) {
                val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
                val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
                val factsArray = unitsObj.getAsJsonArray(unitCode) ?: continue
                val instantFacts = factsArray.mapNotNull { it.asJsonObject }
                    .filter { isConsolidatedFact(it) }
                    .filter { fact ->
                        val form = safeJsonString(fact.get("form")) ?: ""
                        val fp = safeJsonString(fact.get("fp")) ?: ""
                        (form == "10-Q" || form == "10-Q/A" || form == "6-K" || form == "6-K/A") &&
                            fp.uppercase() in setOf("Q1", "Q2", "Q3")
                    }
                    .sortedWith(
                        compareByDescending<JsonObject> { safeJsonString(it.get("end")) ?: "" }
                            .thenByDescending { safeJsonString(it.get("filed")) ?: "" }
                    )
                for (fact in instantFacts) {
                    val periodEnd = safeJsonString(fact.get("end")) ?: continue
                    val value = safeJsonDouble(fact.get("val")) ?: continue
                    if (!value.isFinite()) continue
                    val scaled = value * fx
                    sumByEnd[periodEnd] = (sumByEnd[periodEnd] ?: 0.0) + scaled
                    if (!templateByEnd.containsKey(periodEnd)) {
                        templateByEnd[periodEnd] = QuarterlyFactPoint(
                            metricKey = EdgarMetricKey.TOTAL_DEBT,
                            periodStart = safeJsonString(fact.get("start")),
                            periodEnd = periodEnd,
                            fiscalYear = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt,
                            fiscalPeriod = safeJsonString(fact.get("fp")),
                            form = safeJsonString(fact.get("form")),
                            unit = "USD",
                            taxonomy = taxonomyName,
                            tag = "SummedDebtComponents",
                            value = scaled,
                            filedDate = safeJsonString(fact.get("filed")),
                            accessionNumber = factAccession(fact)
                        )
                    }
                }
                break@unitLoop
            }
        }
        return sumByEnd.entries
            .sortedByDescending { it.key }
            .take(maxQuarters)
            .mapNotNull { (end, sum) ->
                templateByEnd[end]?.copy(value = sum, tag = "SummedDebtComponents")
            }
    }

    private fun isQuarterlyReportForm(form: String): Boolean =
        form == "10-Q" || form == "10-Q/A" || form == "6-K" || form == "6-K/A"

    private fun flowDurationDays(fact: JsonObject): Long? =
        daysBetween(safeJsonString(fact.get("start")), safeJsonString(fact.get("end")))

    /** Raw duration facts from Q1–Q3 filings (standalone + YTD cumulative strips). */
    private fun collectQuarterlyFlowJsonFactsForUnit(factsArray: JsonArray): List<JsonObject> =
        factsArray.mapNotNull { it as? JsonObject }
            .filter { isConsolidatedFact(it) }
            .filter {
                val form = safeJsonString(it.get("form")) ?: ""
                isQuarterlyReportForm(form) &&
                    safeJsonString(it.get("fp"))?.uppercase()?.trim() in setOf("Q1", "Q2", "Q3")
            }
            .filter {
                val d = flowDurationDays(it) ?: return@filter false
                d in 28..FLOW_QUARTERLY_RAW_MAX_DAYS
            }
            .filter { safeJsonDouble(it.get("val"))?.isFinite() == true }

    private fun mergeFactsByBetterCandidate(facts: List<JsonObject>): JsonObject? =
        if (facts.isEmpty()) null else facts.reduce { a, b -> betterQuarterFactCandidate(a, b) }

    private fun bestFactsByDurationBuckets(atEnd: List<JsonObject>): Triple<JsonObject?, JsonObject?, JsonObject?> {
        val standalone = atEnd.filter { (flowDurationDays(it) ?: 0L) in FLOW_STANDALONE_DAYS }
        val sixMo = atEnd.filter { (flowDurationDays(it) ?: 0L) in FLOW_SIX_MONTH_DAYS }
        val nineMo = atEnd.filter { (flowDurationDays(it) ?: 0L) in FLOW_NINE_MONTH_DAYS }
        return Triple(
            mergeFactsByBetterCandidate(standalone),
            mergeFactsByBetterCandidate(sixMo),
            mergeFactsByBetterCandidate(nineMo)
        )
    }

    private fun quarterFlowApproxStartFromEnd(periodEnd: String): String? {
        if (periodEnd.length < 10) return null
        val year = periodEnd.substring(0, 4).toIntOrNull() ?: return null
        val month = periodEnd.substring(5, 7).toIntOrNull() ?: return null
        val day = periodEnd.substring(8, 10).toIntOrNull() ?: return null
        return try {
            LocalDate.of(year, month, day).minusMonths(3).plusDays(1).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun quarterlyFlowPointFromJson(
        fact: JsonObject,
        metricKey: EdgarMetricKey,
        displayTag: String,
        taxonomyName: String,
        unit: String,
        valueOverride: Double? = null,
        periodStartOverride: String? = null
    ): QuarterlyFactPoint? {
        val v = valueOverride ?: safeJsonDouble(fact.get("val")) ?: return null
        if (!v.isFinite()) return null
        val end = safeJsonString(fact.get("end")) ?: return null
        val ps = periodStartOverride ?: safeJsonString(fact.get("start"))
        return QuarterlyFactPoint(
            metricKey = metricKey,
            periodStart = ps,
            periodEnd = end,
            fiscalYear = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt,
            fiscalPeriod = safeJsonString(fact.get("fp")),
            form = safeJsonString(fact.get("form")),
            unit = unit,
            taxonomy = taxonomyName,
            tag = displayTag,
            value = v,
            filedDate = safeJsonString(fact.get("filed")),
            accessionNumber = factAccession(fact)
        )
    }

    /**
     * Standalone quarter flow amounts: prefer short-duration facts; else Q2 = 6mo YTD − Q1, Q3 = 9mo YTD − 6mo YTD
     * (same idea as Q4 = annual − Q1 − Q2 − Q3).
     */
    private fun deriveStandaloneQuarterlyFlowPointsFromJsonFacts(
        allRaw: List<JsonObject>,
        metricKey: EdgarMetricKey,
        conceptTag: String,
        taxonomyName: String,
        unit: String
    ): List<QuarterlyFactPoint> {
        if (allRaw.isEmpty()) return emptyList()
        val byFqEnd = mutableMapOf<Triple<Int, String, String>, MutableList<JsonObject>>()
        for (fact in allRaw) {
            val fy = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt ?: continue
            val fp = safeJsonString(fact.get("fp"))?.uppercase()?.trim() ?: continue
            if (fp !in setOf("Q1", "Q2", "Q3")) continue
            val end = safeJsonString(fact.get("end")) ?: continue
            byFqEnd.getOrPut(Triple(fy, fp, end)) { mutableListOf() }.add(fact)
        }
        fun latestEndFor(fy: Int, fp: String): String? =
            byFqEnd.keys.filter { it.first == fy && it.second == fp }.maxOfOrNull { it.third }

        fun factsAtLatest(fy: Int, fp: String): List<JsonObject> {
            val e = latestEndFor(fy, fp) ?: return emptyList()
            return byFqEnd[Triple(fy, fp, e)].orEmpty()
        }

        val out = mutableListOf<QuarterlyFactPoint>()
        val fiscalYears = byFqEnd.keys.map { it.first }.distinct().sortedDescending()
        for (fy in fiscalYears) {
            val atQ1 = factsAtLatest(fy, "Q1")
            val (s1, _, _) = bestFactsByDurationBuckets(atQ1)
            val narrowQ1 = mergeFactsByBetterCandidate(
                atQ1.filter {
                    val d = flowDurationDays(it) ?: return@filter false
                    d in 28..165
                }
            )
            val q1Point = when {
                s1 != null -> quarterlyFlowPointFromJson(
                    s1, metricKey, conceptTag, taxonomyName, unit
                )
                narrowQ1 != null -> quarterlyFlowPointFromJson(
                    narrowQ1, metricKey, conceptTag, taxonomyName, unit
                )
                else -> null
            }
            val q1Val = q1Point?.value
            if (q1Point != null) out += q1Point

            val atQ2 = factsAtLatest(fy, "Q2")
            val (short2, six2, _) = bestFactsByDurationBuckets(atQ2)
            val q2Point = when {
                short2 != null -> quarterlyFlowPointFromJson(
                    short2, metricKey, conceptTag, taxonomyName, unit
                )
                six2 != null && q1Val != null -> run {
                    val sixV = safeJsonDouble(six2.get("val")) ?: return@run null
                    if (!sixV.isFinite()) return@run null
                    val standalone = sixV - q1Val
                    if (!standalone.isFinite()) return@run null
                    quarterlyFlowPointFromJson(
                        fact = six2,
                        metricKey = metricKey,
                        displayTag = "DerivedQ2StandaloneFromSixMonthYtdMinusQ1:$conceptTag",
                        taxonomyName = taxonomyName,
                        unit = unit,
                        valueOverride = standalone,
                        periodStartOverride = quarterFlowApproxStartFromEnd(
                            safeJsonString(six2.get("end")) ?: ""
                        )
                    )
                }
                else -> null
            }
            if (q2Point != null) out += q2Point
            val q2Val = q2Point?.value
            val sixMonthThroughQ2: Double? = six2?.let { safeJsonDouble(it.get("val")) }
                ?: run {
                    if (q1Val != null && q2Val != null) q1Val + q2Val else null
                }

            val atQ3 = factsAtLatest(fy, "Q3")
            val (short3, _, nine3) = bestFactsByDurationBuckets(atQ3)
            val q3Point = when {
                short3 != null -> quarterlyFlowPointFromJson(
                    short3, metricKey, conceptTag, taxonomyName, unit
                )
                nine3 != null && sixMonthThroughQ2 != null -> run {
                    val nineV = safeJsonDouble(nine3.get("val")) ?: return@run null
                    if (!nineV.isFinite()) return@run null
                    val standalone = nineV - sixMonthThroughQ2
                    if (!standalone.isFinite()) return@run null
                    quarterlyFlowPointFromJson(
                        fact = nine3,
                        metricKey = metricKey,
                        displayTag = "DerivedQ3StandaloneFromNineMonthYtdMinusSixMonthYtd:$conceptTag",
                        taxonomyName = taxonomyName,
                        unit = unit,
                        valueOverride = standalone,
                        periodStartOverride = quarterFlowApproxStartFromEnd(
                            safeJsonString(nine3.get("end")) ?: ""
                        )
                    )
                }
                else -> null
            }
            if (q3Point != null) out += q3Point
        }
        return out
    }

    private fun standaloneQuarterlyFlowValuesByFiscalQuarterForTagUnit(
        taxonomy: JsonObject?,
        conceptTag: String,
        unit: String,
        metricKey: EdgarMetricKey,
        taxonomyName: String
    ): Map<FiscalQuarterKey, Double> {
        if (taxonomy == null) return emptyMap()
        val conceptObj = taxonomy.getAsJsonObject(conceptTag) ?: return emptyMap()
        val unitsObj = conceptObj.getAsJsonObject("units") ?: return emptyMap()
        val factsArray = unitsObj.getAsJsonArray(unit) ?: return emptyMap()
        val raw = collectQuarterlyFlowJsonFactsForUnit(factsArray)
        val points = deriveStandaloneQuarterlyFlowPointsFromJsonFacts(
            raw, metricKey, conceptTag, taxonomyName, unit
        )
        val m = points.mapNotNull { p ->
            val fyy = p.fiscalYear ?: return@mapNotNull null
            val q = parseQuarterOrdinalFromFp(p.fiscalPeriod) ?: return@mapNotNull null
            FiscalQuarterKey(fyy, q) to p.value
        }.toMap().toMutableMap()
        supplementQ4StandaloneFromAnnual(taxonomy, taxonomyName, listOf(conceptTag), unit, m)
        return m
    }

    /** Q4 standalone = annual − Q1 − Q2 − Q3 when Q1–Q3 exist (aligns with [deriveQ4FlowPointsWithOverrides]). */
    private fun supplementQ4StandaloneFromAnnual(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        unit: String,
        fiscalMap: MutableMap<FiscalQuarterKey, Double>
    ) {
        if (taxonomy == null) return
        val annualPoints = getAnnualFacts(taxonomy, taxonomyName, conceptTags, unit, maxYears = 12)
        for (annual in annualPoints) {
            val fy = annual.fiscalYear
            val q1 = fiscalMap[FiscalQuarterKey(fy, 1)] ?: continue
            val q2 = fiscalMap[FiscalQuarterKey(fy, 2)] ?: continue
            val q3 = fiscalMap[FiscalQuarterKey(fy, 3)] ?: continue
            val q4 = annual.value - q1 - q2 - q3
            if (!q4.isFinite()) continue
            val k = FiscalQuarterKey(fy, 4)
            if (fiscalMap.containsKey(k)) continue
            fiscalMap[k] = q4
        }
    }

    private fun getQuarterlyFlowFacts(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        metricKey: EdgarMetricKey,
        unit: String,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        for ((unitCode, fx) in if (unit == "USD") MONETARY_UNITS_TO_USD else listOf(unit to 1.0)) {
            val result = getQuarterlyFlowFactsSingle(taxonomy, taxonomyName, conceptTags, metricKey, unitCode, maxQuarters)
                .map { if (fx == 1.0) it else it.copy(value = it.value * fx) }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun getQuarterlyFlowFactsSingle(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        metricKey: EdgarMetricKey,
        unit: String,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        var bestResult: List<QuarterlyFactPoint> = emptyList()
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue
            val raw = collectQuarterlyFlowJsonFactsForUnit(factsArray)
            val derived = deriveStandaloneQuarterlyFlowPointsFromJsonFacts(
                raw, metricKey, tag, taxonomyName, unit
            )
            val list = derived.sortedByDescending { it.periodEnd }.take(maxQuarters)
            if (list.size > bestResult.size) bestResult = list
        }
        return bestResult
    }

    private fun getQuarterlyInstantFacts(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        metricKey: EdgarMetricKey,
        units: List<String>,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        for (unit in units) {
            val result = getQuarterlyInstantFactsSingle(taxonomy, taxonomyName, conceptTags, metricKey, unit, maxQuarters)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun getQuarterlyInstantFactsSingle(
        taxonomy: JsonObject?,
        taxonomyName: String,
        conceptTags: List<String>,
        metricKey: EdgarMetricKey,
        unit: String,
        maxQuarters: Int
    ): List<QuarterlyFactPoint> {
        if (taxonomy == null) return emptyList()
        var bestResult: List<QuarterlyFactPoint> = emptyList()
        for (tag in conceptTags) {
            val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
            val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
            val factsArray = unitsObj.getAsJsonArray(unit) ?: continue

            val instantFacts = factsArray.mapNotNull { it.asJsonObject }
                .filter { isConsolidatedFact(it) }
                .filter { fact ->
                    val form = safeJsonString(fact.get("form")) ?: ""
                    val fp = safeJsonString(fact.get("fp")) ?: ""
                    val quarter = fp.uppercase()
                    (form == "10-Q" || form == "10-Q/A" || form == "6-K" || form == "6-K/A") &&
                        quarter in setOf("Q1", "Q2", "Q3")
                }
                .sortedWith(
                    compareByDescending<JsonObject> { safeJsonString(it.get("end")) ?: "" }
                        .thenByDescending { safeJsonString(it.get("filed")) ?: "" }
                )

            val byPeriod = linkedMapOf<String, QuarterlyFactPoint>()
            for (fact in instantFacts) {
                val periodEnd = safeJsonString(fact.get("end")) ?: continue
                if (byPeriod.containsKey(periodEnd)) continue
                val value = safeJsonDouble(fact.get("val")) ?: continue
                if (!value.isFinite()) continue
                byPeriod[periodEnd] = QuarterlyFactPoint(
                    metricKey = metricKey,
                    periodStart = safeJsonString(fact.get("start")),
                    periodEnd = periodEnd,
                    fiscalYear = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt,
                    fiscalPeriod = safeJsonString(fact.get("fp")),
                    form = safeJsonString(fact.get("form")),
                    unit = unit,
                    taxonomy = taxonomyName,
                    tag = tag,
                    value = value,
                    filedDate = safeJsonString(fact.get("filed")),
                    accessionNumber = factAccession(fact)
                )
                if (byPeriod.size >= maxQuarters) break
            }
            val list = byPeriod.values.toList()
            if (list.size > bestResult.size) bestResult = list
        }
        return bestResult
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

    private fun taxonomyObject(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        taxonomy: String
    ): JsonObject? = when (taxonomy) {
        EdgarTaxonomy.US_GAAP -> usGaap
        EdgarTaxonomy.IFRS_FULL -> ifrsFull
        EdgarTaxonomy.DEI -> dei
        else -> null
    }

    private fun getAnnualUsdWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val base = getAnnualValue(usGaap, standard, "USD")
            ?: getAnnualValue(ifrsFull, standard, "USD")
        if (base != null) return base
        val o = TagOverrideResolver.resolveGlobal(overrides, key) ?: return null
        val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy) ?: return null
        return getAnnualValue(tax, listOf(o.tag), "USD")
    }

    private fun getAnnualEpsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val base = getAnnualValue(usGaap, EdgarConcepts.EPS, "USD/shares")
            ?: getAnnualValue(ifrsFull, EdgarConcepts.EPS, "USD/shares")
            ?: getAnnualValue(dei, EdgarConcepts.EPS, "USD/shares")
        if (base != null) return base
        val o = TagOverrideResolver.resolveGlobal(overrides, EdgarMetricKey.EPS) ?: return null
        val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy) ?: return null
        return getAnnualValue(tax, listOf(o.tag), "USD/shares")
    }

    private fun getTtmUsdWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val o = TagOverrideResolver.resolveGlobal(overrides, key)
        if (o != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy)
            if (tax != null) {
                getTtmValue(tax, listOf(o.tag), "USD", o.taxonomy, key)?.let { return it }
            }
        }
        return getTtmValue(usGaap, standard, "USD", EdgarTaxonomy.US_GAAP, key)
            ?: getTtmValue(ifrsFull, standard, "USD", EdgarTaxonomy.IFRS_FULL, key)
    }

    private fun getTtmEpsWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val o = TagOverrideResolver.resolveGlobal(overrides, EdgarMetricKey.EPS)
        if (o != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy)
            if (tax != null) {
                getTtmValue(
                    tax,
                    listOf(o.tag),
                    "USD/shares",
                    o.taxonomy,
                    EdgarMetricKey.EPS
                )?.let { return it }
            }
        }
        return getTtmValue(
            usGaap,
            EdgarConcepts.EPS,
            "USD/shares",
            EdgarTaxonomy.US_GAAP,
            EdgarMetricKey.EPS
        )
            ?: getTtmValue(
                ifrsFull,
                EdgarConcepts.EPS,
                "USD/shares",
                EdgarTaxonomy.IFRS_FULL,
                EdgarMetricKey.EPS
            )
            ?: getTtmValue(
                dei,
                EdgarConcepts.EPS,
                "USD/shares",
                EdgarTaxonomy.DEI,
                EdgarMetricKey.EPS
            )
    }

    private fun getLatestInstantUsdWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val base = getLatestInstantValue(usGaap, standard, "USD")
            ?: getLatestInstantValue(ifrsFull, standard, "USD")
        if (base != null) return base
        val o = TagOverrideResolver.resolveGlobal(overrides, key) ?: return null
        val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy) ?: return null
        return getLatestInstantValue(tax, listOf(o.tag), "USD")
    }

    private fun getAnnualHistoryWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        standard: List<String>,
        unit: String,
        years: Int,
        key: EdgarMetricKey,
        overrides: List<SymbolTagOverrideEntity>
    ): List<Double?> {
        val fromUs = getAnnualHistory(usGaap, standard, unit, years)
        val primary = if (fromUs.any { it != null }) fromUs else getAnnualHistory(ifrsFull, standard, unit, years)
        if (primary.any { it != null }) return primary
        val o = TagOverrideResolver.resolveGlobal(overrides, key) ?: return emptyList()
        val tax = taxonomyObject(usGaap, ifrsFull, dei, o.taxonomy) ?: return emptyList()
        return getAnnualHistory(tax, listOf(o.tag), unit, years)
    }

    private fun parseCompanyFactsToStockData(
        ticker: String,
        facts: JsonObject,
        companyName: String?,
        sicCode: String?,
        sector: String?,
        industry: String?,
        cik: String?,
        tagOverrides: List<SymbolTagOverrideEntity> = emptyList()
    ): StockData {
        val factsObj = facts.getAsJsonObject("facts")
        val usGaap = factsObj?.getAsJsonObject("us-gaap")
        val dei = factsObj?.getAsJsonObject("dei")
        val ifrsFull = factsObj?.getAsJsonObject("ifrs-full")
        val o = tagOverrides

        // --- Income statement ANNUAL (10-K primary) ---
        val revenue = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.REVENUE, EdgarMetricKey.REVENUE, o)
        val netIncome = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.NET_INCOME, EdgarMetricKey.NET_INCOME, o)
        val ebit = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.EBIT, EdgarMetricKey.EBIT, o)
        val depreciation = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.DEPRECIATION, EdgarMetricKey.DEPRECIATION, o)
        val ebitda = if (ebit != null && depreciation != null) ebit + depreciation else null
        val costOfGoodsSoldTagged = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.COST_OF_GOODS, EdgarMetricKey.COST_OF_GOODS, o)
        val directGrossProfit = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.GROSS_PROFIT, EdgarMetricKey.GROSS_PROFIT, o)
        // COGS is derived from reported revenue and gross profit when both exist; else fall back to tagged CostOfRevenue/CostOfGoodsSold.
        val costOfGoodsSold = if (revenue != null && directGrossProfit != null) {
            revenue - directGrossProfit
        } else {
            costOfGoodsSoldTagged
        }
        val grossProfit = directGrossProfit
        val eps = getAnnualEpsWithOverrides(usGaap, ifrsFull, dei, o)

        // --- Income statement TTM (10-Q only, requires 4 clean quarters) ---
        val revenueTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.REVENUE, EdgarMetricKey.REVENUE, o)
        val netIncomeTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.NET_INCOME, EdgarMetricKey.NET_INCOME, o)
        val ebitTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.EBIT, EdgarMetricKey.EBIT, o)
        val depreciationTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.DEPRECIATION, EdgarMetricKey.DEPRECIATION, o)
        val ebitdaTtm = if (ebitTtm != null && depreciationTtm != null) ebitTtm + depreciationTtm else null
        val grossProfitTtmDirect = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.GROSS_PROFIT, EdgarMetricKey.GROSS_PROFIT, o)
        val costOfGoodsSoldTaggedTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.COST_OF_GOODS, EdgarMetricKey.COST_OF_GOODS, o)
        val costOfGoodsSoldTtm = if (revenueTtm != null && grossProfitTtmDirect != null) {
            revenueTtm - grossProfitTtmDirect
        } else {
            costOfGoodsSoldTaggedTtm
        }
        val epsTtm = getTtmEpsWithOverrides(usGaap, ifrsFull, dei, o)

        // --- Balance sheet (latest instant) ---
        val totalAssets = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.TOTAL_ASSETS_LIST, EdgarMetricKey.TOTAL_ASSETS, o)
        val totalLiabilities = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.TOTAL_LIABILITIES_LIST, EdgarMetricKey.TOTAL_LIABILITIES, o)
        val currentAssets = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CURRENT_ASSETS_LIST, EdgarMetricKey.CURRENT_ASSETS, o)
        val currentLiabilities = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CURRENT_LIABILITIES_LIST, EdgarMetricKey.CURRENT_LIABILITIES, o)
        val retainedEarnings = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.RETAINED_EARNINGS_LIST, EdgarMetricKey.RETAINED_EARNINGS, o)
        val totalEquity = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.STOCKHOLDERS_EQUITY, EdgarMetricKey.STOCKHOLDERS_EQUITY, o)
        val totalDebt = getTotalDebtWithOverrides(usGaap, ifrsFull, dei, o)
        val cashAndEquivalents = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CASH, EdgarMetricKey.CASH_AND_EQUIVALENTS, o)
        val accountsReceivable = getLatestInstantUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.ACCOUNTS_RECEIVABLE, EdgarMetricKey.ACCOUNTS_RECEIVABLE, o)
        val sharesFromEdgar = getSharesOutstandingWithOverrides(usGaap, ifrsFull, dei, o)

        // --- Cash flow ANNUAL (10-K) ---
        val operatingCashFlow = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.OPERATING_CASH_FLOW, EdgarMetricKey.OPERATING_CASH_FLOW, o)
        val capex = getAnnualUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CAPEX, EdgarMetricKey.CAPEX, o)
        /** FCF = OCF − |capex| only when both exist (never substitute OCF alone). */
        val freeCashFlow = if (operatingCashFlow != null && capex != null) {
            operatingCashFlow - abs(capex)
        } else null

        // --- Cash flow TTM (10-Q) ---
        val operatingCashFlowTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.OPERATING_CASH_FLOW, EdgarMetricKey.OPERATING_CASH_FLOW, o)
        val capexTtm = getTtmUsdWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CAPEX, EdgarMetricKey.CAPEX, o)
        val freeCashFlowTtm = if (operatingCashFlowTtm != null && capexTtm != null) {
            operatingCashFlowTtm - abs(capexTtm)
        } else null

        val workingCapital = if (currentAssets != null && currentLiabilities != null) {
            currentAssets - currentLiabilities
        } else null

        // --- Annual history (10-K only) ---
        val revenueHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.REVENUE, "USD", 5, EdgarMetricKey.REVENUE, o)
        val netIncomeHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.NET_INCOME, "USD", 5, EdgarMetricKey.NET_INCOME, o)
        val depreciationHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.DEPRECIATION, "USD", 5, EdgarMetricKey.DEPRECIATION, o)
        val operatingIncomeHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.EBIT, "USD", 5, EdgarMetricKey.EBIT, o)
        val ebitdaHistory = (0 until maxOf(operatingIncomeHistory.size, depreciationHistory.size)).map { i ->
            val oi = operatingIncomeHistory.getOrNull(i)
            val da = depreciationHistory.getOrNull(i)
            if (oi != null && da != null) oi + da else null
        }
        val totalAssetsHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.TOTAL_ASSETS_LIST, "USD", 5, EdgarMetricKey.TOTAL_ASSETS, o)
        val currentAssetsHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CURRENT_ASSETS_LIST, "USD", 5, EdgarMetricKey.CURRENT_ASSETS, o)
        val currentLiabilitiesHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CURRENT_LIABILITIES_LIST, "USD", 5, EdgarMetricKey.CURRENT_LIABILITIES, o)
        val longTermDebtHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.LONG_TERM_DEBT, "USD", 5, EdgarMetricKey.LONG_TERM_DEBT, o)
        val costOfGoodsSoldTaggedHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.COST_OF_GOODS, "USD", 5, EdgarMetricKey.COST_OF_GOODS, o)
        val grossProfitHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.GROSS_PROFIT, "USD", 5, EdgarMetricKey.GROSS_PROFIT, o)
        val costOfGoodsSoldHistory = (0 until maxOf(revenueHistory.size, grossProfitHistory.size, costOfGoodsSoldTaggedHistory.size)).map { i ->
            val revenuePoint = revenueHistory.getOrNull(i)
            val grossPoint = grossProfitHistory.getOrNull(i)
            if (revenuePoint != null && grossPoint != null) {
                revenuePoint - grossPoint
            } else {
                costOfGoodsSoldTaggedHistory.getOrNull(i)
            }
        }
        val operatingCashFlowHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.OPERATING_CASH_FLOW, "USD", 5, EdgarMetricKey.OPERATING_CASH_FLOW, o)
        val capexHistory = getAnnualHistoryWithOverrides(usGaap, ifrsFull, dei, EdgarConcepts.CAPEX, "USD", 5, EdgarMetricKey.CAPEX, o)
        val freeCashFlowHistory = (0 until maxOf(operatingCashFlowHistory.size, capexHistory.size)).map { i ->
            val ocf = operatingCashFlowHistory.getOrNull(i)
            val cx = capexHistory.getOrNull(i)
            if (ocf != null && cx != null) ocf - abs(cx) else null
        }
        val sharesOutstandingHistory = getSharesOutstandingHistoryWithOverrides(usGaap, ifrsFull, dei, 5, o)
        val totalDebtHistory = getAnnualTotalDebtHistoryWithOverrides(usGaap, ifrsFull, dei, o, 5)

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
            capex = capex,
            capexTtm = capexTtm,
            capexHistory = capexHistory,
            depreciationAmortization = depreciation,
            depreciationAmortizationTtm = depreciationTtm,
            depreciationAmortizationHistory = depreciationHistory,
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
            totalDebtHistory = totalDebtHistory,
            revenueTtm = revenueTtm,
            netIncomeTtm = netIncomeTtm,
            epsTtm = epsTtm,
            ebitdaTtm = ebitdaTtm,
            costOfGoodsSoldTtm = costOfGoodsSoldTtm,
            freeCashFlowTtm = freeCashFlowTtm,
            operatingCashFlowTtm = operatingCashFlowTtm,
            totalDebt = totalDebt,
            cashAndEquivalents = cashAndEquivalents,
            accountsReceivable = accountsReceivable,
            operatingIncome = ebit,
            operatingIncomeTtm = ebitTtm
        )
    }

    /** Annual history for shares outstanding (instant facts from 10-K). Tries us-gaap, dei, ifrs-full, then per-symbol override. */
    private fun getSharesOutstandingHistoryWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        years: Int,
        overrides: List<SymbolTagOverrideEntity>
    ): List<Double?> {
        for (unit in listOf("shares", "pure")) {
            getAnnualHistorySingle(usGaap, EdgarConcepts.SHARES_OUTSTANDING, unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
            getAnnualHistorySingle(dei, EdgarConcepts.SHARES_OUTSTANDING, unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
            getAnnualHistorySingle(ifrsFull, EdgarConcepts.SHARES_OUTSTANDING, unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
        }
        val row = TagOverrideResolver.resolveGlobal(overrides, EdgarMetricKey.SHARES_OUTSTANDING) ?: return emptyList()
        val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy) ?: return emptyList()
        for (unit in listOf("shares", "pure")) {
            getAnnualHistorySingle(tax, listOf(row.tag), unit, years).takeIf { it.any { v -> v != null } }?.let { return it }
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

    private fun factAccession(fact: JsonObject): String? =
        safeJsonString(fact.get("accn")) ?: safeJsonString(fact.get("accessionNumber"))

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

    /** TTM from the latest fiscal quarter plus three consecutive prior quarters (same tag). FX-aware for USD. */
    private fun getTtmValue(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String,
        taxonomyName: String,
        metricKey: EdgarMetricKey
    ): Double? {
        if (taxonomy == null) return null
        if (unit == "USD") {
            for ((u, rateToUsd) in MONETARY_UNITS_TO_USD) {
                getTtmValueSingle(taxonomy, conceptTags, u, taxonomyName, metricKey)?.let { value ->
                    if (value.isFinite()) return value * rateToUsd
                }
            }
            return null
        }
        return getTtmValueSingle(taxonomy, conceptTags, unit, taxonomyName, metricKey)
    }

    private fun daysBetween(start: String?, end: String?): Long? {
        if (start.isNullOrBlank() || end.isNullOrBlank()) return null
        return try {
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            ChronoUnit.DAYS.between(LocalDate.parse(start, fmt), LocalDate.parse(end, fmt))
        } catch (_: Exception) { null }
    }

    /** Fiscal period from SEC `fy` + `fp` (Q1–Q4); used to require consecutive quarters for TTM. */
    private data class FiscalQuarterKey(val fiscalYear: Int, val quarter: Int) : Comparable<FiscalQuarterKey> {
        init {
            require(quarter in 1..4) { "quarter must be 1..4" }
        }

        override fun compareTo(other: FiscalQuarterKey): Int =
            compareValuesBy(this, other, { it.fiscalYear }, { it.quarter })

        fun previous(): FiscalQuarterKey =
            if (quarter == 1) FiscalQuarterKey(fiscalYear - 1, 4)
            else FiscalQuarterKey(fiscalYear, quarter - 1)
    }

    private fun parseQuarterOrdinalFromFp(fp: String?): Int? {
        val raw = fp?.uppercase()?.trim() ?: return null
        if (!raw.startsWith("Q")) return null
        var i = 1
        while (i < raw.length && raw[i].isDigit()) i++
        if (i == 1) return null
        return raw.substring(1, i).toIntOrNull()?.takeIf { it in 1..4 }
    }

    private fun betterQuarterFactCandidate(a: JsonObject, b: JsonObject): JsonObject {
        val filedA = safeJsonString(a.get("filed")) ?: ""
        val filedB = safeJsonString(b.get("filed")) ?: ""
        if (filedA != filedB) return if (filedA > filedB) a else b
        val endA = safeJsonString(a.get("end")) ?: ""
        val endB = safeJsonString(b.get("end")) ?: ""
        return if (endA >= endB) a else b
    }

    /**
     * TTM = sum of **standalone** amounts for the latest fiscal quarter and three immediately prior quarters.
     * Standalone Q1–Q3 from short-duration facts or YTD subtraction (6mo−Q1, 9mo−6mo); Q4 from annual−Q1−Q2−Q3 when needed.
     */
    private fun getTtmValueSingle(
        taxonomy: JsonObject?,
        conceptTags: List<String>,
        unit: String,
        taxonomyName: String,
        metricKey: EdgarMetricKey
    ): Double? {
        if (taxonomy == null) return null
        tagLoop@ for (tag in conceptTags) {
            val map = standaloneQuarterlyFlowValuesByFiscalQuarterForTagUnit(
                taxonomy, tag, unit, metricKey, taxonomyName
            )
            if (map.isEmpty()) continue
            val latest = map.keys.maxOrNull() ?: continue
            var cursor = latest
            var sum = 0.0
            for (i in 0 until 4) {
                val v = map[cursor] ?: continue@tagLoop
                if (!v.isFinite()) continue@tagLoop
                sum += v
                if (i < 3) cursor = cursor.previous()
            }
            if (sum.isFinite()) return sum
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

    private fun getSharesOutstandingWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val base = getSharesOutstanding(usGaap, dei) ?: getSharesOutstanding(ifrsFull, dei)
        if (base != null) return base
        val row = TagOverrideResolver.resolveGlobal(overrides, EdgarMetricKey.SHARES_OUTSTANDING) ?: return null
        val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy) ?: return null
        for (unit in listOf("shares", "pure")) {
            getLatestInstantValue(tax, listOf(row.tag), unit)?.let { if (it > 0) return it }
        }
        return null
    }

    private fun getTotalDebtWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>
    ): Double? {
        val row = TagOverrideResolver.resolveTotalDebt(overrides)
        if (row != null) {
            val tax = taxonomyObject(usGaap, ifrsFull, dei, row.taxonomy) ?: return null
            return getLatestInstantValue(tax, listOf(row.tag), "USD")
        }
        return getDebtFromBalanceSheet(usGaap) ?: getDebtFromBalanceSheet(ifrsFull)
    }

    private fun getDebtFromBalanceSheet(taxonomy: JsonObject?): Double? {
        if (taxonomy == null) return null
        var total = 0.0
        var found = false
        for (tag in EdgarConcepts.TOTAL_DEBT) {
            val v = getLatestInstantValue(taxonomy, listOf(tag), "USD")
            if (v != null) {
                total += v
                found = true
            }
        }
        return if (found) total else null
    }

    private fun getAnnualTotalDebtHistoryWithOverrides(
        usGaap: JsonObject?,
        ifrsFull: JsonObject?,
        dei: JsonObject?,
        overrides: List<SymbolTagOverrideEntity>,
        years: Int
    ): List<Double?> {
        val row = TagOverrideResolver.resolveTotalDebt(overrides)
        if (row != null) {
            return getAnnualHistoryWithOverrides(
                usGaap, ifrsFull, dei, listOf(row.tag), "USD", years, EdgarMetricKey.TOTAL_DEBT, overrides
            )
        }
        val us = getAnnualSummedDebtHistory(usGaap, years)
        if (us.isNotEmpty()) return us.map { it }
        val ifrs = getAnnualSummedDebtHistory(ifrsFull, years)
        return if (ifrs.isNotEmpty()) ifrs.map { it } else emptyList()
    }

    /** One value per fiscal year (newest first): sum of standard debt tags from 10-K/20-F/40-F. */
    private fun getAnnualSummedDebtHistory(taxonomy: JsonObject?, years: Int): List<Double> {
        if (taxonomy == null) return emptyList()
        val totals = linkedMapOf<Int, Double>()
        for (tag in EdgarConcepts.TOTAL_DEBT) {
            tagUnitLoop@ for ((unitCode, fx) in MONETARY_UNITS_TO_USD) {
                val conceptObj = taxonomy.getAsJsonObject(tag) ?: continue
                val unitsObj = conceptObj.getAsJsonObject("units") ?: continue
                val factsArray = unitsObj.getAsJsonArray(unitCode) ?: continue
                val annualFacts = factsArray.mapNotNull { it.asJsonObject }
                    .filter { isConsolidatedFact(it) }
                    .filter { safeJsonString(it.get("form")) in setOf("10-K", "40-F", "20-F") }
                    .sortedByDescending { safeJsonString(it.get("end")) ?: "" }
                if (annualFacts.isEmpty()) continue
                val seenFyForTag = mutableSetOf<Int>()
                for (fact in annualFacts) {
                    val fy = fact.get("fy")?.takeIf { !it.isJsonNull }?.asInt
                        ?: yearFromEndDate(safeJsonString(fact.get("end")))
                        ?: continue
                    if (fy in seenFyForTag) continue
                    val value = safeJsonDouble(fact.get("val")) ?: continue
                    if (!value.isFinite()) continue
                    seenFyForTag.add(fy)
                    totals[fy] = (totals[fy] ?: 0.0) + value * fx
                }
                break@tagUnitLoop
            }
        }
        return totals.entries.sortedByDescending { it.key }.take(years).map { it.value }
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