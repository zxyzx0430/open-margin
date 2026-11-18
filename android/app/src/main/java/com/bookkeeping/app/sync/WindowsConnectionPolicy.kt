package com.bookkeeping.app.sync

import java.net.URI

/** Validation and read-only server probe decisions for any self-hosted HTTPS endpoint. */
internal object WindowsConnectionPolicy {
    enum class Result {
        READY, INVALID_ADDRESS, INVALID_TOKEN, UNAUTHORIZED, UNAVAILABLE,
        ROUTE_MISSING, INVALID_RESPONSE, WRONG_LEDGER
    }

    fun validateAddress(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        // Ordinary VPS domains and Tailscale hosts follow the same pairing protocol.
        // Accept DNS names only; reject local addresses and URL tricks that could
        // redirect a long-lived device credential to the wrong destination.
        val labels = host.split('.')
        val labelRule = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        val isDnsName = host.length <= 253 && labels.size >= 2 &&
            labels.all { labelRule.matches(it) } &&
            !host.endsWith(".local") &&
            !Regex("(?:[0-9]+\\.){3}[0-9]+").matches(host)
        return uri.scheme.equals("https", ignoreCase = true) &&
            isDnsName &&
            uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.trimEnd('/') == "/finance"
    }

    fun preflight(raw: String, token: String): Result = when {
        !validateAddress(raw) -> Result.INVALID_ADDRESS
        token.trim().length < 16 -> Result.INVALID_TOKEN
        else -> Result.READY
    }

    /** A successful identity response must also obey the v2 sync page contract.
     * No mutations are sent by this onboarding read-only probe.
     * Cursor-ahead is handled separately by SyncServerIdentityPolicy, so it
     * must not be accidentally misreported as a malformed response.
     */
    fun isValidProbeEnvelope(
        requestedSince: Long, returnedCursor: Long, head: Long,
        itemCount: Int?, hasMore: Boolean?
    ): Boolean {
        if (requestedSince < 0 || head < 0 || returnedCursor < requestedSince ||
            itemCount == null || itemCount !in 0..1 || hasMore == null) return false
        if (requestedSince > head) {
            return itemCount == 0 && returnedCursor == requestedSince && !hasMore
        }
        if (returnedCursor > head) return false
        if (itemCount == 0) return returnedCursor == requestedSince && !hasMore
        return returnedCursor > requestedSince
    }

    fun isSameAttempt(checkedUrl: String, checkedToken: String,
                      currentUrl: String, currentToken: String): Boolean =
        checkedUrl == currentUrl.trim().trimEnd('/') &&
            checkedToken == currentToken.trim()

    fun response(code: Int, identity: SyncServerIdentityPolicy.Decision?): Result = when {
        code == 401 || code == 403 -> Result.UNAUTHORIZED
        code == 404 || code == 405 -> Result.ROUTE_MISSING
        code != 200 -> Result.UNAVAILABLE
        identity == null || identity == SyncServerIdentityPolicy.Decision.MISSING_ID ->
            Result.INVALID_RESPONSE
        identity != SyncServerIdentityPolicy.Decision.ACCEPT -> Result.WRONG_LEDGER
        else -> Result.READY
    }
}
