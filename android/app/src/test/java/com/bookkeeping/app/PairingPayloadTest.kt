package com.bookkeeping.app

import com.bookkeeping.app.sync.PairingParseResult
import com.bookkeeping.app.sync.PairingPayload
import com.bookkeeping.app.sync.PairingRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {
    private val url = "https://my-pc.my-tailnet.ts.net/finance"
    private val token = "device-token-1234567890"
    private val serverId = "server-identity-1234567890"

    private fun json(
        url: String = this.url,
        token: String = this.token,
        serverId: String = this.serverId,
        version: String = "1",
        kind: String = "open-margin-pair",
    ): String =
        """{"v":$version,"kind":"$kind","url":"$url","token":"$token","server_id":"$serverId"}"""

    private fun reason(raw: String): PairingRejectReason =
        (PairingPayload.parse(raw) as PairingParseResult.Rejected).reason

    @Test
    fun parsesValidPayloadAndNormalizesTrailingSlashAndWhitespace() {
        val result = PairingPayload.parse(
            """ {"v":1,"kind":"open-margin-pair","url":"$url/","token":" $token ","server_id":" $serverId "} """
        )
        assertTrue(result is PairingParseResult.Success)
        val payload = (result as PairingParseResult.Success).payload
        assertEquals(url, payload.url)
        assertEquals(token, payload.token)
        assertEquals(serverId, payload.serverId)
    }

    @Test
    fun manualInputNormalizesExactlyLikeScannedInput() {
        val scanned = (PairingPayload.parse(json()) as PairingParseResult.Success).payload
        val manual = PairingPayload.fromInput(" $url/ ", " $token ", " $serverId ")
        assertEquals(scanned, manual)
        assertNotEquals(null, manual)
    }

    @Test
    fun rejectsWrongEnvelope() {
        assertEquals(PairingRejectReason.EMPTY, reason("   "))
        assertEquals(PairingRejectReason.MALFORMED, reason("not json"))
        assertEquals(PairingRejectReason.MALFORMED, reason("null"))
        assertEquals(PairingRejectReason.MALFORMED, reason("[1,2,3]"))
        assertEquals(PairingRejectReason.UNSUPPORTED_VERSION, reason(json(version = "2")))
        assertEquals(PairingRejectReason.UNSUPPORTED_VERSION, reason(json(version = "\"1\"")))
        assertEquals(PairingRejectReason.WRONG_KIND, reason(json(kind = "open-margin")))
    }

    @Test
    fun enforcesTheSameUrlRulesAsWindowsConnectionPolicy() {
        listOf(
            "http://my-pc.my-tailnet.ts.net/finance",
            "https://finance.example.com/finance",
            "https://my-pc.my-tailnet.ts.net/finance/extra",
            "https://my-pc.my-tailnet.ts.net/finance?token=exposed",
            "https://my-pc.my-tailnet.ts.net/finance#fragment",
            "https://user:pass@my-pc.my-tailnet.ts.net/finance",
            "https://my-pc.my-tailnet.ts.net:8443/finance",
            "https://my-pc.my-tailnet.ts.net/",
            "https://.ts.net/finance",
        ).forEach { invalid ->
            assertEquals(
                "URL should be rejected: $invalid",
                PairingRejectReason.INVALID_URL,
                reason(json(url = invalid)),
            )
        }
        assertTrue(PairingPayload.parse(json(url = "$url/")) is PairingParseResult.Success)
    }

    @Test
    fun rejectsShortOrUnsafeSecrets() {
        assertEquals(PairingRejectReason.INVALID_TOKEN, reason(json(token = "short-token")))
        assertEquals(PairingRejectReason.INVALID_TOKEN, reason(json(token = "valid-token-spaces 123")))
        assertEquals(PairingRejectReason.INVALID_SERVER_ID, reason(json(serverId = "short")))
        assertEquals(
            PairingRejectReason.INVALID_SERVER_ID,
            reason(json(serverId = "server\\nidentity-1234")),
        )
        // JSON numbers are not accepted where strings are required.
        assertEquals(
            PairingRejectReason.INVALID_TOKEN,
            reason("""{"v":1,"kind":"open-margin-pair","url":"$url","token":1234567890123456,"server_id":"$serverId"}"""),
        )
        assertEquals(
            PairingRejectReason.INVALID_SERVER_ID,
            reason("""{"v":1,"kind":"open-margin-pair","url":"$url","token":"$token","server_id":1234567890123456}"""),
        )
        // Exactly 16 characters is the accepted boundary.
        assertTrue(PairingPayload.parse(json(token = "1234567890123456")) is PairingParseResult.Success)
        assertTrue(PairingPayload.parse(json(serverId = "1234567890123456")) is PairingParseResult.Success)
    }

    @Test
    fun rejectsOversizedPayloadsBeforeParsing() {
        assertEquals(
            PairingRejectReason.OVERSIZED,
            reason("{\"v\":1,\"kind\":\"open-margin-pair\"," + "x".repeat(5000) + "}"),
        )
        assertEquals(
            PairingRejectReason.INVALID_TOKEN,
            reason(json(token = "a".repeat(2000))),
        )
        assertEquals(
            PairingRejectReason.INVALID_SERVER_ID,
            reason(json(serverId = "b".repeat(300))),
        )
    }

    @Test
    fun toStringNeverPrintsTheDeviceToken() {
        val payload = (PairingPayload.parse(json()) as PairingParseResult.Success).payload
        val rendered = payload.toString()
        assertFalse(rendered.contains(token))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains(url))
    }
}
