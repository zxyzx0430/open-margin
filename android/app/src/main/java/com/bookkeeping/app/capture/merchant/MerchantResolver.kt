package com.bookkeeping.app.capture.merchant

import java.text.Normalizer

enum class MerchantSource {
    USER,
    IMPORT,
    NOTIFICATION,
    PAGE,
    OCR,
}

data class MerchantCandidate(
    val raw: String,
    val normalized: String,
    val source: MerchantSource,
    val confidence: Float? = null,
)

data class MerchantInput(
    val notification: String? = null,
    val page: String? = null,
    val ocr: String? = null,
    val ocrConfidence: Float? = null,
    val import: String? = null,
    val user: String? = null,
)

data class MerchantResolution(
    val canonical: String?,
    /** Original candidate text, useful for showing "待确认" without inventing a merchant. */
    val raw: String?,
    val source: MerchantSource?,
    val needsConfirmation: Boolean,
    val candidates: List<MerchantCandidate>,
)

interface MerchantAliasStore {
    fun canonical(raw: String): String?
    fun put(raw: String, canonical: String)
}

class InMemoryMerchantAliasStore : MerchantAliasStore {
    private val values = mutableMapOf<String, String>()

    override fun canonical(raw: String): String? = values[raw.trim()]

    override fun put(raw: String, canonical: String) {
        if (raw.isBlank() || canonical.isBlank()) return
        values[raw.trim()] = canonical.trim()
    }
}

/**
 * Merges merchant-name candidates by trust level. OCR-only results are never
 * treated as facts; they stay candidates until the user confirms or an alias
 * matches. This is the guard against silently inventing merchant names.
 */
object MerchantResolver {
    fun resolve(
        input: MerchantInput,
        aliases: MerchantAliasStore = InMemoryMerchantAliasStore(),
    ): MerchantResolution {
        val candidates = buildList {
            input.user?.toCandidate(MerchantSource.USER, 1f)?.let(::add)
            input.import?.toCandidate(MerchantSource.IMPORT, 0.95f)?.let(::add)
            input.notification?.toCandidate(MerchantSource.NOTIFICATION, 0.9f)?.let(::add)
            input.page?.toCandidate(MerchantSource.PAGE, 0.8f)?.let(::add)
            input.ocr?.toCandidate(MerchantSource.OCR, input.ocrConfidence ?: 0.4f)?.let(::add)
        }
        if (candidates.isEmpty()) {
            return MerchantResolution(null, null, null, true, emptyList())
        }

        fun resolved(candidate: MerchantCandidate): String =
            aliases.canonical(candidate.raw)
                ?: aliases.canonical(candidate.normalized)
                ?: candidate.normalized

        candidates.firstOrNull { it.source == MerchantSource.USER }?.let { user ->
            return MerchantResolution(resolved(user), user.raw, user.source, false, candidates)
        }
        candidates.firstOrNull { it.source == MerchantSource.IMPORT }?.let { imported ->
            return MerchantResolution(resolved(imported), imported.raw, imported.source, false, candidates)
        }

        val notification = candidates.firstOrNull { it.source == MerchantSource.NOTIFICATION }
        val page = candidates.firstOrNull { it.source == MerchantSource.PAGE }
        if (notification != null) {
            val notificationCanonical = resolved(notification)
            val pageCanonical = page?.let(::resolved)
            return MerchantResolution(
                canonical = notificationCanonical,
                raw = notification.raw,
                source = MerchantSource.NOTIFICATION,
                needsConfirmation = pageCanonical != null && !sameMerchant(pageCanonical, notificationCanonical),
                candidates = candidates,
            )
        }
        if (page != null) {
            return MerchantResolution(resolved(page), page.raw, page.source, false, candidates)
        }

        val ocr = candidates.firstOrNull { it.source == MerchantSource.OCR }
            ?: return MerchantResolution(null, null, null, true, candidates)
        val alias = aliases.canonical(ocr.raw) ?: aliases.canonical(ocr.normalized)
        return if (alias != null) {
            MerchantResolution(alias, ocr.raw, MerchantSource.OCR, false, candidates)
        } else {
            MerchantResolution(null, ocr.raw, MerchantSource.OCR, true, candidates)
        }
    }

    fun normalize(raw: String): String {
        var value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        value = value.replace(Regex("""\s+"""), " ")
        value = value.replace(
            Regex("""^(?:商户(?:名称)?|收款方|收款商户|付款给|Merchant)\s*[:：]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        value = value.replace(Regex("""[（(][^）)]*(?:店|门店|分店)[^）)]*[）)]$"""), "")
        value = value.replace(Regex("""(?:商户|收款方|支付|付款|收款)$"""), "")
        return value.trim(' ', '，', ',', '。', ':', '：', '-', '·')
    }

    private fun sameMerchant(a: String, b: String): Boolean =
        a.replace(Regex("""\s+"""), "")
            .equals(b.replace(Regex("""\s+"""), ""), ignoreCase = true)

    private fun String.toCandidate(source: MerchantSource, confidence: Float): MerchantCandidate? {
        val normalized = normalize(this)
        if (normalized.length !in 2..80) return null
        if (normalized.all { it.isDigit() }) return null
        return MerchantCandidate(
            raw = trim(),
            normalized = normalized,
            source = source,
            confidence = confidence,
        )
    }
}
