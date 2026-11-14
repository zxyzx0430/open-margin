package com.bookkeeping.app.capture.merchant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantResolverTest {
    @Test
    fun notificationAndPageAgreementIsHighConfidence() {
        val result = MerchantResolver.resolve(
            MerchantInput(notification = "星巴克", page = "星巴克（杭州店）"),
        )
        assertEquals("星巴克", result.canonical)
        assertEquals(MerchantSource.NOTIFICATION, result.source)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun notificationWinsButConflictNeedsConfirmation() {
        val result = MerchantResolver.resolve(
            MerchantInput(notification = "星巴克", page = "瑞幸咖啡"),
        )
        assertEquals("星巴克", result.canonical)
        assertEquals(MerchantSource.NOTIFICATION, result.source)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun ocrOnlyIsACandidateNotAFact() {
        val result = MerchantResolver.resolve(
            MerchantInput(ocr = "星巴克", ocrConfidence = 0.6f),
        )
        assertNull(result.canonical)
        assertEquals("星巴克", result.raw)
        assertEquals(MerchantSource.OCR, result.source)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun ocrOnlyCanUseAUserAlias() {
        val aliases = InMemoryMerchantAliasStore().apply { put("星巴克", "星巴克咖啡") }
        val result = MerchantResolver.resolve(
            MerchantInput(ocr = "星巴克"),
            aliases,
        )
        assertEquals("星巴克咖啡", result.canonical)
        assertEquals(MerchantSource.OCR, result.source)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun importAndUserOutrankOtherSources() {
        val imported = MerchantResolver.resolve(
            MerchantInput(notification = "微信支付商户", import = "盒马鲜生"),
        )
        assertEquals("盒马鲜生", imported.canonical)
        assertEquals(MerchantSource.IMPORT, imported.source)
        assertFalse(imported.needsConfirmation)

        val user = MerchantResolver.resolve(
            MerchantInput(notification = "星巴克", user = "星巴克臻选"),
        )
        assertEquals("星巴克臻选", user.canonical)
        assertEquals(MerchantSource.USER, user.source)
    }

    @Test
    fun normalizesFullWidthPrefixesAndStoreSuffixes() {
        assertEquals("星巴克", MerchantResolver.normalize("星巴克（杭州店）"))
        assertEquals("盒马鲜生", MerchantResolver.normalize("商户：盒马鲜生"))
        assertEquals("ABC", MerchantResolver.normalize("ＡＢＣ"))
        assertEquals("星巴克", MerchantResolver.normalize("星巴克  "))
    }
}
