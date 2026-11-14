package com.bookkeeping.app.notification.capture

import org.json.JSONArray
import org.json.JSONObject

/**
 * A local, reviewable payment candidate captured from a supported page.
 *
 * It deliberately stores only structured fields and a local screenshot path;
 * the raw page text is not persisted. Nothing here is inserted into the ledger
 * until the normal review/deduplication path accepts it.
 */
data class CaptureCandidate(
    val packageName: String,
    val pageIdentity: String,
    val amountCents: Long,
    val merchantCandidates: List<String>,
    val orderId: String?,
    val capturedAtMs: Long,
    val screenshotPath: String?,
    val confidence: Float,
    val reason: String,
    val status: String = STATUS_PENDING,
    val kind: String = KIND_PAYMENT,
    val eventTimeMs: Long = capturedAtMs,
) {
    fun isValid(): Boolean {
        if (packageName != WECHAT && packageName != ALIPAY) return false
        if (pageIdentity.isBlank() || pageIdentity.length > 200) return false
        if (amountCents <= 0L || amountCents > MAX_CENTS) return false
        if (capturedAtMs <= 0L || eventTimeMs <= 0L) return false
        if (confidence !in 0f..1f) return false
        if (merchantCandidates.size > MAX_MERCHANTS) return false
        if (merchantCandidates.any { it.isBlank() || it.length > 80 }) return false
        if (orderId != null && (orderId.isBlank() || orderId.length > 80)) return false
        if (reason.isBlank() || reason.length > 80) return false
        if (status !in STATUSES) return false
        if (kind !in KINDS) return false
        return true
    }

    fun stableKey(): String = listOf(
        pageIdentity,
        kind,
        amountCents.toString(),
        merchantCandidates.firstOrNull().orEmpty(),
        eventTimeMs.toString(),
    ).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("pageIdentity", pageIdentity)
        put("amountCents", amountCents)
        put("merchantCandidates", JSONArray(merchantCandidates))
        put("orderId", orderId ?: JSONObject.NULL)
        put("capturedAtMs", capturedAtMs)
        put("screenshotPath", screenshotPath ?: JSONObject.NULL)
        put("confidence", confidence.toDouble())
        put("reason", reason)
        put("status", status)
        put("kind", kind)
        put("eventTimeMs", eventTimeMs)
    }

    companion object {
        const val WECHAT = "com.tencent.mm"
        const val ALIPAY = "com.eg.android.AlipayGphone"
        const val MAX_CENTS = 100_000_000_000L
        const val MAX_MERCHANTS = 5
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_IGNORED = "IGNORED"
        const val KIND_PAYMENT = "PAYMENT"
        const val KIND_REFUND = "REFUND"
        const val KIND_AUTO_PAY = "AUTO_PAY"
        val STATUSES = setOf(STATUS_PENDING, STATUS_APPROVED, STATUS_IGNORED)
        val KINDS = setOf(KIND_PAYMENT, KIND_REFUND, KIND_AUTO_PAY)

        fun fromJson(json: JSONObject): CaptureCandidate? = runCatching {
            val merchantsJson = json.optJSONArray("merchantCandidates") ?: JSONArray()
            val merchants = (0 until merchantsJson.length()).mapNotNull { index ->
                merchantsJson.optString(index).takeIf { it.isNotBlank() }
            }
            val status = json.optString("status", STATUS_PENDING)
                .takeIf { it in STATUSES } ?: STATUS_PENDING
            val kind = json.optString("kind", KIND_PAYMENT)
                .takeIf { it in KINDS } ?: KIND_PAYMENT
            val capturedAt = json.getLong("capturedAtMs")
            val candidate = CaptureCandidate(
                packageName = json.getString("packageName"),
                pageIdentity = json.getString("pageIdentity"),
                amountCents = json.getLong("amountCents"),
                merchantCandidates = merchants,
                orderId = json.optString("orderId").takeIf {
                    it.isNotBlank() && it != "null"
                },
                capturedAtMs = capturedAt,
                screenshotPath = json.optString("screenshotPath").takeIf {
                    it.isNotBlank() && it != "null"
                },
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                reason = json.optString("reason", "ok"),
                status = status,
                kind = kind,
                eventTimeMs = json.optLong("eventTimeMs", capturedAt),
            )
            candidate.takeIf { it.isValid() }
        }.getOrNull()
    }
}
