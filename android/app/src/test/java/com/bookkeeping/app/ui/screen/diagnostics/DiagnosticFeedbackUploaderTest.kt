package com.bookkeeping.app.ui.screen.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFeedbackUploaderTest {
    @Test
    fun `buildPayloadJson serializes metadata and truncates oversized logs`() {
        val oversized = "a".repeat(2_000_000)
        val payload = DiagnosticFeedbackUploader.buildPayloadJson(
            logText = oversized,
            description = "  微信支付后出现两笔待确认  ",
            contact = "  测试用户A  ",
            appVersion = "1.47",
            versionCode = 48,
            deviceModel = "Xiaomi 2210132C",
            androidVersion = "Android 16 (API 36)",
            fileName = "diag.txt",
        )
        val json = JSONObject(payload)
        assertEquals("微信支付后出现两笔待确认", json.getString("description"))
        assertEquals("测试用户A", json.getString("contact"))
        assertEquals("1.47", json.getString("app_version"))
        assertEquals(48, json.getInt("version_code"))
        assertTrue(json.getString("log_text").toByteArray(Charsets.UTF_8).size <= 1_800_000)
    }

    @Test
    fun `parseResponse extracts feedback_id on 200 and rejects error status`() {
        val ok = DiagnosticFeedbackUploader.parseResponse(
            200,
            """{"ok":true,"feedback_id":"FB-1124-A1B2"}""",
        )
        assertTrue(ok.ok)
        assertEquals("FB-1124-A1B2", ok.feedbackId)

        val rateLimited = DiagnosticFeedbackUploader.parseResponse(429, """{"ok":false}""")
        assertFalse(rateLimited.ok)
    }
}
