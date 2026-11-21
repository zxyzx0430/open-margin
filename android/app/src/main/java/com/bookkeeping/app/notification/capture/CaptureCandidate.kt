package com.bookkeeping.app.notification.capture

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A local, reviewable payment candidate captured from a supported page.
 *
 * It deliberately stores only structured fields and a local screenshot path;
 * the raw page text is not persisted. Completed cards are written through the
 * normal deduplicating ledger path; the record remains for audit and display.
 */
data class CaptureCandidate(
    /** Legacy field kept for old accessibility-capture records. It is a payment app ID, not intake source. */
    val packageName: String?,
    val pageIdentity: String,
    val amountCents: Long?,
    val merchantCandidates: List<String>,
    val orderId: String?,
    val capturedAtMs: Long,
    val screenshotPath: String?,
    val confidence: Float,
    val reason: String,
    val status: String = STATUS_PENDING,
    val kind: String = KIND_PAYMENT,
    val eventTimeMs: Long? = capturedAtMs,
    val direction: String = DIRECTION_EXPENSE,
    val diagnosticAttemptId: String? = null,
    val intakeMethod: String = INTAKE_ACCESSIBILITY,
    val paymentPlatform: String = platformFromPackage(packageName),
    val confidenceTier: String = TIER_MEDIUM,
    val completionStatus: String = COMPLETION_COMPLETED,
    val eventTimeSource: String = TIME_SOURCE_ACTUAL,
    val eventTimeNeedsConfirmation: Boolean = eventTimeSource == TIME_SOURCE_CAPTURE,
    val imageFingerprint: String? = null,
    val candidateId: String = java.util.UUID.randomUUID().toString(),
    val supportedPaymentPage: Boolean = packageName != null,
    val transactionLike: Boolean = true,
    val amountUnambiguous: Boolean = amountCents != null,
    val completionExplicit: Boolean = completionStatus == COMPLETION_COMPLETED,
    val directionExplicit: Boolean = direction != DIRECTION_UNKNOWN,
    val matchedTransactionId: Long? = null,
    /** A preview candidate can be reviewed/ignored but never written through any path. */
    val ledgerWriteAllowed: Boolean = true,
) {

    fun isValid(): Boolean {
        if (packageName != null && packageName != WECHAT && packageName != ALIPAY) return false
        if (paymentPlatform !in PAYMENT_PLATFORMS || intakeMethod !in INTAKE_METHODS) return false
        if (pageIdentity.isBlank() || pageIdentity.length > 200) return false
        if (amountCents != null && amountCents !in 1..MAX_CENTS) return false
        if (capturedAtMs <= 0L || eventTimeMs?.let { it <= 0L } == true) return false
        if (confidence !in 0f..1f) return false
        if (merchantCandidates.size > MAX_MERCHANTS) return false
        if (merchantCandidates.any { it.isBlank() || it.length > 80 }) return false
        if (orderId != null && (orderId.isBlank() || orderId.length > 80)) return false
        if (reason.isBlank() || reason.length > 80) return false
        if (status !in STATUSES || confidenceTier !in CONFIDENCE_TIERS) return false
        if (completionStatus !in COMPLETION_STATUSES || eventTimeSource !in EVENT_TIME_SOURCES) return false
        if (kind !in KINDS || direction !in DIRECTIONS) return false
        if (candidateId.isBlank() || candidateId.length > 80) return false
        if (matchedTransactionId != null && matchedTransactionId <= 0L) return false
        if (imageFingerprint != null && !imageFingerprint.matches(Regex("[a-f0-9]{64}"))) return false
        return true
    }

    fun stableKey(): String = listOf(
        pageIdentity,
        kind,
        amountCents.toString(),
        merchantCandidates.firstOrNull().orEmpty(),
        eventTimeMs.toString(),
        direction,
    ).joinToString("|")

    /**
     * Duplicate key for repeated accessibility/OCR passes over the same card.
     * Page identity is deliberately excluded because the page text can change
     * between events while the underlying transaction stays the same.
     */
    fun dedupKey(): String {
        orderId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "order|$paymentPlatform|$it"
        }
        val merchant = merchantCandidates.firstOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val timeKey = if (eventTimeSource == TIME_SOURCE_ACTUAL && eventTimeMs != null) {
            "event:$eventTimeMs"
        } else {
            "capture:${capturedAtMs / DEDUP_BUCKET_MS}"
        }
        return "$paymentPlatform|$kind|$amountCents|$direction|$merchant|$timeKey"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("pageIdentity", pageIdentity)
        put("amountCents", amountCents ?: JSONObject.NULL)
        put("merchantCandidates", JSONArray(merchantCandidates))
        put("orderId", orderId ?: JSONObject.NULL)
        put("capturedAtMs", capturedAtMs)
        put("screenshotPath", screenshotPath ?: JSONObject.NULL)
        put("confidence", confidence.toDouble())
        put("reason", reason)
        put("status", status)
        put("kind", kind)
        put("eventTimeMs", eventTimeMs ?: JSONObject.NULL)
        put("direction", direction)
        put("diagnosticAttemptId", diagnosticAttemptId ?: JSONObject.NULL)
        put("intakeMethod", intakeMethod)
        put("paymentPlatform", paymentPlatform)
        put("confidenceTier", confidenceTier)
        put("completionStatus", completionStatus)
        put("eventTimeSource", eventTimeSource)
        put("eventTimeNeedsConfirmation", eventTimeNeedsConfirmation)
        put("imageFingerprint", imageFingerprint ?: JSONObject.NULL)
        put("candidateId", candidateId)
        put("supportedPaymentPage", supportedPaymentPage)
        put("transactionLike", transactionLike)
        put("amountUnambiguous", amountUnambiguous)
        put("completionExplicit", completionExplicit)
        put("directionExplicit", directionExplicit)
        put("matchedTransactionId", matchedTransactionId ?: JSONObject.NULL)
        put("ledgerWriteAllowed", ledgerWriteAllowed)
    }

    companion object {
        const val WECHAT = "com.tencent.mm"
        const val ALIPAY = "com.eg.android.AlipayGphone"
        const val MAX_CENTS = 100_000_000_000L
        const val MAX_MERCHANTS = 5
        const val DEDUP_BUCKET_MS = 60_000L
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_IGNORED = "IGNORED"
        const val STATUS_WAITING = "WAITING_EVIDENCE"
        const val STATUS_LINKED = "LINKED_EXISTING"

        /**
         * 候选与账本里已有记录疑似同一笔（跨通道弱证据：另一侧是通知，只有「微信支付」
         * 占位名）时写进 [reason] 的说明。这类候选保持 [STATUS_PENDING]，由用户在
         * 待确认列表里决定是否记入账本，不会被当成重复悄悄丢掉。
         */
        const val REASON_LIKELY_DUPLICATE = "疑似与已有记录重复"
        const val INTAKE_MEDIASTORE = "MEDIASTORE"
        const val INTAKE_SHARE = "SHARE"
        const val INTAKE_PHOTO_PICKER = "PHOTO_PICKER"
        const val INTAKE_ACCESSIBILITY = "ACCESSIBILITY"
        const val PLATFORM_WECHAT = "WECHAT"
        const val PLATFORM_ALIPAY = "ALIPAY"
        const val PLATFORM_UNKNOWN = "UNKNOWN"
        const val TIER_HIGH = "HIGH"
        const val TIER_MEDIUM = "MEDIUM"
        const val TIER_LOW = "LOW"
        const val COMPLETION_COMPLETED = "COMPLETED"
        const val COMPLETION_PENDING = "PENDING"
        const val COMPLETION_CREATED = "CREATED"
        const val COMPLETION_CANCELLED = "CANCELLED"
        const val COMPLETION_FAILED = "FAILED"
        const val COMPLETION_UNKNOWN = "UNKNOWN"
        const val TIME_SOURCE_ACTUAL = "ACTUAL_TRANSACTION_TIME"
        const val TIME_SOURCE_CAPTURE = "SCREENSHOT_CAPTURE_TIME"
        const val TIME_SOURCE_USER_CORRECTED = "USER_CORRECTED"
        const val KIND_PAYMENT = "PAYMENT"
        const val KIND_REFUND = "REFUND"
        const val KIND_AUTO_PAY = "AUTO_PAY"
        const val KIND_TRANSFER = "TRANSFER"
        const val KIND_RED_PACKET = "RED_PACKET"
        const val KIND_WITHDRAWAL = "WITHDRAWAL"
        const val KIND_BILL = "BILL"
        const val DIRECTION_EXPENSE = "EXPENSE"
        const val DIRECTION_INCOME = "INCOME"
        const val DIRECTION_UNKNOWN = "UNKNOWN"
        val STATUSES = setOf(
            STATUS_PENDING, STATUS_APPROVED, STATUS_IGNORED, STATUS_WAITING, STATUS_LINKED,
        )
        val KINDS = setOf(
            KIND_PAYMENT,
            KIND_REFUND,
            KIND_AUTO_PAY,
            KIND_TRANSFER,
            KIND_RED_PACKET,
            KIND_WITHDRAWAL,
            KIND_BILL,
        )
        val DIRECTIONS = setOf(DIRECTION_EXPENSE, DIRECTION_INCOME, DIRECTION_UNKNOWN)
        val INTAKE_METHODS = setOf(
            INTAKE_MEDIASTORE, INTAKE_SHARE, INTAKE_PHOTO_PICKER, INTAKE_ACCESSIBILITY,
        )
        val PAYMENT_PLATFORMS = setOf(PLATFORM_WECHAT, PLATFORM_ALIPAY, PLATFORM_UNKNOWN)
        val CONFIDENCE_TIERS = setOf(TIER_HIGH, TIER_MEDIUM, TIER_LOW)
        val COMPLETION_STATUSES = setOf(
            COMPLETION_COMPLETED, COMPLETION_PENDING, COMPLETION_CREATED,
            COMPLETION_CANCELLED, COMPLETION_FAILED, COMPLETION_UNKNOWN,
        )
        val EVENT_TIME_SOURCES = setOf(
            TIME_SOURCE_ACTUAL, TIME_SOURCE_CAPTURE, TIME_SOURCE_USER_CORRECTED,
        )

        fun platformFromPackage(packageName: String?): String = when (packageName) {
            WECHAT -> PLATFORM_WECHAT
            ALIPAY -> PLATFORM_ALIPAY
            else -> PLATFORM_UNKNOWN
        }

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
            val packageName = json.optString("packageName").takeIf {
                it.isNotBlank() && it != "null"
            }
            val timeSource = json.optString("eventTimeSource", TIME_SOURCE_ACTUAL)
                .takeIf { it in EVENT_TIME_SOURCES } ?: TIME_SOURCE_ACTUAL
            val eventTime = when {
                !json.has("eventTimeMs") -> capturedAt
                json.isNull("eventTimeMs") -> null
                else -> json.optLong("eventTimeMs")
            }
            val amount = if (json.has("amountCents") && !json.isNull("amountCents")) {
                json.optLong("amountCents")
            } else {
                null
            }
            val direction = json.optString("direction", DIRECTION_EXPENSE)
                .takeIf { it in DIRECTIONS } ?: DIRECTION_EXPENSE
            val candidateId = json.optString("candidateId").takeIf(String::isNotBlank)
                ?: java.util.UUID.nameUUIDFromBytes(
                    listOf(packageName, json.optString("pageIdentity"), kind,
                        json.optLong("amountCents"), eventTime, direction).joinToString("|").toByteArray(),
                ).toString()
            val candidate = CaptureCandidate(
                packageName = packageName,
                pageIdentity = json.optString("pageIdentity"),
                amountCents = amount,
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
                eventTimeMs = eventTime,
                direction = direction,
                diagnosticAttemptId = json.optString("diagnosticAttemptId").takeIf {
                    it.isNotBlank() && it != "null"
                },
                intakeMethod = json.optString("intakeMethod", INTAKE_ACCESSIBILITY)
                    .takeIf { it in INTAKE_METHODS } ?: INTAKE_ACCESSIBILITY,
                paymentPlatform = json.optString("paymentPlatform", platformFromPackage(packageName))
                    .takeIf { it in PAYMENT_PLATFORMS } ?: PLATFORM_UNKNOWN,
                confidenceTier = json.optString("confidenceTier", TIER_MEDIUM)
                    .takeIf { it in CONFIDENCE_TIERS } ?: TIER_MEDIUM,
                completionStatus = json.optString("completionStatus", COMPLETION_COMPLETED)
                    .takeIf { it in COMPLETION_STATUSES } ?: COMPLETION_UNKNOWN,
                eventTimeSource = timeSource,
                eventTimeNeedsConfirmation = json.optBoolean(
                    "eventTimeNeedsConfirmation", timeSource == TIME_SOURCE_CAPTURE,
                ),
                imageFingerprint = json.optString("imageFingerprint").takeIf {
                    it.matches(Regex("[a-f0-9]{64}"))
                },
                candidateId = candidateId,
                supportedPaymentPage = json.optBoolean("supportedPaymentPage", packageName != null),
                transactionLike = json.optBoolean("transactionLike", true),
                amountUnambiguous = json.optBoolean("amountUnambiguous", false),
                completionExplicit = json.optBoolean("completionExplicit", false),
                directionExplicit = json.optBoolean("directionExplicit", direction != DIRECTION_UNKNOWN),
                matchedTransactionId = if (json.isNull("matchedTransactionId")) null else
                    json.optLong("matchedTransactionId").takeIf { it > 0L },
                ledgerWriteAllowed = json.optBoolean("ledgerWriteAllowed", true),
            )
            candidate.takeIf { it.isValid() }
        }.getOrNull()
    }
}
