package com.example.stockzilla.sec

import com.google.gson.Gson

/**
 * Best-effort XML extraction for SEC ownership/13F forms.
 *
 * The SEC XML schemas can be complex and namespaced. To keep the pipeline robust,
 * we do:
 * - quick tag-stripping into readable text for the LLM
 * - lightweight tag-based extraction for a few high-signal fields
 *
 * If extraction fails, the stripped text still provides raw context for summarization.
 */
object SecXmlExtraction {
    private val gson = Gson()

    fun stripXmlAndNormalize(xml: String): String {
        var text = xml
        // Remove CDATA wrappers.
        text = text.replace("<![CDATA[", "").replace("]]>", "")
        // Remove comments entirely.
        text = text.replace(Regex("(?s)<!--.*?-->"), " ")
        // Remove all tags.
        text = text.replace(Regex("(?s)<[^>]+>"), " ")
        // Decode common HTML/XML entities.
        text = text.replace("&nbsp;", " ")
        text = text.replace("&amp;", "&")
        text = text.replace("&lt;", "<")
        text = text.replace("&gt;", ">")
        text = text.replace("&quot;", "\"")
        text = text.replace("&apos;", "'")
        // Collapse whitespace.
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        return text.trim()
    }

    /**
     * Build a compact JSON payload for LLM prompting.
     * This is intentionally "best-effort" — it should never throw.
     */
    fun buildFormHighlightsJson(formType: String, xml: String): String {
        return try {
            when (formType) {
                "3", "4", "5" -> gson.toJson(extractFormInsiderTransactions(xml))
                "13F-HR", "13F-HR/A" -> gson.toJson(extract13FInformationTable(xml))
                else -> gson.toJson(mapOf("formType" to formType))
            }
        } catch (_: Exception) {
            gson.toJson(mapOf("formType" to formType, "extraction" to "failed"))
        }
    }

    private fun extractFormInsiderTransactions(xml: String): Map<String, Any?> {
        val transactionDates = findAllTagValues(xml, "transactionDate")
        val acquiredDisposedCodes = findAllTagValues(xml, "transactionAcquiredDisposedCode")
        val shares = findAllTagValues(xml, "transactionShares")
        val pricePerShare = findAllTagValues(xml, "transactionPricePerShare")

        val count = minOf(transactionDates.size, acquiredDisposedCodes.size, shares.size, pricePerShare.size)
        val capped = count.coerceAtMost(10)

        val transactions = (0 until capped).map { idx ->
            mapOf(
                "transactionDate" to transactionDates.getOrNull(idx),
                "transactionAcquiredDisposedCode" to acquiredDisposedCodes.getOrNull(idx),
                "transactionShares" to shares.getOrNull(idx),
                "transactionPricePerShare" to pricePerShare.getOrNull(idx)
            )
        }

        return mapOf(
            "transactions" to transactions,
            "counts" to mapOf(
                "transactionDate" to transactionDates.size,
                "transactionAcquiredDisposedCode" to acquiredDisposedCodes.size,
                "transactionShares" to shares.size,
                "transactionPricePerShare" to pricePerShare.size
            )
        )
    }

    private fun extract13FInformationTable(xml: String): Map<String, Any?> {
        val blocks = extractXmlBlocks(xml, "informationTable")
        val capped = blocks.take(20)

        val positions = capped.map { block ->
            mapOf(
                "nameOfIssuer" to firstTagValue(block, "nameOfIssuer"),
                "titleOfClass" to firstTagValue(block, "titleOfClass"),
                "cusip" to firstTagValue(block, "cusip"),
                "value" to firstTagValue(block, "value"),
                "sharesOrPrnAmt" to firstTagValue(block, "shrsOrPrnAmt"),
                "sshPrnAmtType" to firstTagValue(block, "sshPrnAmtType")
            )
        }

        return mapOf(
            "informationTablePositions" to positions,
            "positionsTotalExtracted" to positions.size
        )
    }

    private fun findAllTagValues(xml: String, tagNameLocal: String): List<String> {
        val regex = Regex("(?is)<$tagNameLocal\\b[^>]*>(.*?)</$tagNameLocal>")
        return regex.findAll(xml)
            .map { match -> normalizeXmlValue(match.groupValues[1]) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun firstTagValue(xml: String, tagNameLocal: String): String? {
        val regex = Regex("(?is)<$tagNameLocal\\b[^>]*>(.*?)</$tagNameLocal>")
        return regex.find(xml)?.groupValues?.getOrNull(1)?.let { normalizeXmlValue(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractXmlBlocks(xml: String, blockTagLocal: String): List<String> {
        val regex = Regex("(?is)<$blockTagLocal\\b[^>]*>(.*?)</$blockTagLocal>")
        return regex.findAll(xml).map { it.groupValues[1] }.toList()
    }

    private fun normalizeXmlValue(valueInnerXml: String): String {
        // Strip any nested tags that appear inside the value element.
        return stripXmlAndNormalize(valueInnerXml)
    }
}