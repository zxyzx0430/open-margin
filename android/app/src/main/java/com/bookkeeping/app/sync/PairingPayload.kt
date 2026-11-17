package com.bookkeeping.app.sync

import org.json.JSONException
import org.json.JSONObject

/**
 * Rejection reasons for an offline pairing QR. They never carry the token so a
 * failure can be shown in the UI without leaking the ledger key.
 */
internal enum class PairingRejectReason {
    EMPTY,
    OVERSIZED,
    MALFORMED,
    UNSUPPORTED_VERSION,
    WRONG_KIND,
    INVALID_URL,
    INVALID_TOKEN,
    INVALID_SERVER_ID,
}

internal sealed interface PairingParseResult {
    data class Success(val payload: PairingPayload) : PairingParseResult
    data class Rejected(val reason: PairingRejectReason) : PairingParseResult
}

/**
 * Payload shown by the Windows setup wizard:
 * `{"v":1,"kind":"open-margin-pair","url":"https://host.ts.net/finance",
 *   "token":"...","server_id":"..."}`.
 *
 * The QR is generated locally by the setup wizard and contains the long-lived
 * device token. Parsing is deliberately strict and side-effect free: a scanned
 * payload only prefills the wizard. Nothing is saved or enabled until the
 * existing read-only probe verifies the ledger and the user confirms.
 */
data class PairingPayload(
    val url: String,
    val token: String,
    val serverId: String,
) {
    /** Data-class toString would print the token; keep it out of logs/crash reports. */
    override fun toString(): String =
        "PairingPayload(url=$url, token=<redacted>, serverId=${serverId.take(8)}…)"

    companion object {
        const val KIND = "open-margin-pair"
        const val VERSION = 1
        const val MIN_SECRET_LENGTH = 16

        /** Hard cap before JSON parsing so a hostile QR cannot allocate freely. */
        const val MAX_PAYLOAD_CHARS = 4096
        const val MAX_URL_CHARS = 512
        const val MAX_TOKEN_CHARS = 1024
        const val MAX_SERVER_ID_CHARS = 256

        fun parse(raw: String?): PairingParseResult {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) {
                return PairingParseResult.Rejected(PairingRejectReason.EMPTY)
            }
            if (text.length > MAX_PAYLOAD_CHARS) {
                return PairingParseResult.Rejected(PairingRejectReason.OVERSIZED)
            }

            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                return PairingParseResult.Rejected(PairingRejectReason.MALFORMED)
            }

            val version = json.opt("v")
            if (version !is Int || version != VERSION) {
                return PairingParseResult.Rejected(PairingRejectReason.UNSUPPORTED_VERSION)
            }
            val kind = json.opt("kind")
            if (kind !is String || kind != KIND) {
                return PairingParseResult.Rejected(PairingRejectReason.WRONG_KIND)
            }

            val rawUrl = json.opt("url") as? String
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_URL)
            val rawToken = json.opt("token") as? String
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_TOKEN)
            val rawServerId = json.opt("server_id") as? String
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_SERVER_ID)

            val url = normalizeUrl(rawUrl)
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_URL)
            val token = normalizeSecret(rawToken, MAX_TOKEN_CHARS)
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_TOKEN)
            val serverId = normalizeSecret(rawServerId, MAX_SERVER_ID_CHARS)
                ?: return PairingParseResult.Rejected(PairingRejectReason.INVALID_SERVER_ID)

            return PairingParseResult.Success(PairingPayload(url, token, serverId))
        }

        /**
         * Same normalization for typed input, so a scanned URL/token and the
         * same values typed by hand reach the probe as identical strings.
         * Returns null when a field does not pass the pairing rules.
         */
        fun fromInput(url: String, token: String, serverId: String): PairingPayload? {
            val normalizedUrl = normalizeUrl(url) ?: return null
            val normalizedToken = normalizeSecret(token, MAX_TOKEN_CHARS) ?: return null
            val normalizedServerId = normalizeSecret(serverId, MAX_SERVER_ID_CHARS) ?: return null
            return PairingPayload(normalizedUrl, normalizedToken, normalizedServerId)
        }

        private fun normalizeUrl(raw: String): String? {
            val value = raw.trim().trimEnd('/')
            if (value.length > MAX_URL_CHARS) return null
            if (value.any { it.isISOControl() || it.isWhitespace() }) return null
            return value.takeIf { WindowsConnectionPolicy.validateAddress(it) }
        }

        private fun normalizeSecret(raw: String, maxChars: Int): String? {
            val value = raw.trim()
            if (value.length < MIN_SECRET_LENGTH || value.length > maxChars) return null
            // Header values and log lines tolerate no embedded whitespace/control chars.
            if (value.any { it.isWhitespace() || it.isISOControl() }) return null
            return value
        }
    }
}
