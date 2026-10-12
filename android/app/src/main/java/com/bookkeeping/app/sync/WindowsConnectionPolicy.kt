package com.bookkeeping.app.sync

import java.net.URI

/** Validation and read-only server probe decisions for Windows private onboarding. */
internal object WindowsConnectionPolicy {
    enum class Result {
        READY, INVALID_ADDRESS, INVALID_TOKEN, UNAUTHORIZED, UNAVAILABLE,
        ROUTE_MISSING, INVALID_RESPONSE, WRONG_LEDGER
    }

    fun validateAddress(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            host.endsWith(".ts.net") && host.length > ".ts.net".length &&
            uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.trimEnd('/') == "/finance"
    }

    fun preflight(raw: String, token: String): Result = when {
        !validateAddress(raw) -> Result.INVALID_ADDRESS
        token.trim().length < 16 -> Result.INVALID_TOKEN
        else -> Result.READY
    }

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
