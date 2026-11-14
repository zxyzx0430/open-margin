package com.bookkeeping.app.capture.merchant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconCropPolicyTest {
    @Test
    fun acceptsAReasonableMerchantLogoRegion() {
        assertTrue(IconCropPolicy.validate(1000, 1000, 100, 100, 164, 164))
    }

    @Test
    fun rejectsOutOfBoundsAndTinyRegions() {
        assertFalse(IconCropPolicy.validate(1000, 1000, -1, 0, 64, 64))
        assertFalse(IconCropPolicy.validate(1000, 1000, 0, 0, 10, 10))
        assertFalse(IconCropPolicy.validate(0, 1000, 0, 0, 64, 64))
    }

    @Test
    fun rejectsLargeOrNonSquareRegions() {
        assertFalse(IconCropPolicy.validate(1000, 1000, 0, 0, 400, 400))
        assertFalse(IconCropPolicy.validate(1000, 1000, 0, 0, 20, 80))
    }
}
