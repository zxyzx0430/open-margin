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
) {
    fun isValid(): Boolean {
        if (packageName != WECHAT && packageName != ALIPAY) return false
        if (pageIdentity.isBlank() || pageIdentity.length > 200) return false
        if (amountCents <= 0L || amountCents > MAX_CENTS) return false
        if (capturedAtMs <= 0L) return false
        if (confidence !in 0f..1f) return false
        if (merchantCandidates.size > MAX_MERCHANTS) return false
        if (merchantCandidates.any { it.isBlank() || it.length > 80 }) return false
        if (orderId != null && (orderId.isBlank() || orderId.length > 80)) return false
        if (reason.isBlank() || reason.length > 80) return false
        return true
    }

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
    }

    companion object {
        const val WECHAT = "com.tencent.mm"
        const val ALIPAY = "com.eg.android.AlipayGphone"
        const val MAX_CENTS = 100_000_000_000L
        const val MAX_MERCHANTS = 5

        fun fromJson(json: JSONObject): CaptureCandidate? = runCatching {
            val merchantsJson = json.optJSONArray("merchantCandidates") ?: JSONArray()
            val merchants = (0 until merchantsJson.length()).mapNotNull { index ->
                merchantsJson.optString(index).takeIf { it.isNotBlank() }
            }
            val candidate = CaptureCandidate(
                packageName = json.getString("packageName"),
                pageIdentity = json.getString("pageIdentity"),
                amountCents = json.getLong("amountCents"),
                merchantCandidates = merchants,
                orderId = json.optString("orderId").takeIf {
                    it.isNotBlank() && it != "null"
                },
                capturedAtMs = json.getLong("capturedAtMs"),
                screenshotPath = json.optString("screenshotPath").takeIf {
                    it.isNotBlank() && it != "null"
                },
                confidence = json.optDouble("confidence", 0.0).toFloat(),
                reason = json.optString("reason", "ok"),
            )
            candidate.takeIf { it.isValid() }
        }.getOrNull()
    }
}
