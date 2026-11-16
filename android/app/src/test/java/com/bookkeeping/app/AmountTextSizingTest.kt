package com.bookkeeping.app

import com.bookkeeping.app.ui.components.AmountTextSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountTextSizingTest {
    @Test fun shortAmountsKeepTheDesignSize() {
        // 常见金额（≤11 字符，如 ¥26,825.14）保持各页原有字级，不因这次改动变小。
        assertEquals(22f, AmountTextSizing.fontSizeSp(22f, 8), 0.01f)
        assertEquals(16f, AmountTextSizing.fontSizeSp(16f, 11), 0.01f)
        assertEquals(28f, AmountTextSizing.fontSizeSp(28f, 1), 0.01f)
    }

    @Test fun longerAmountsStepDownThreeSpPerBucket() {
        // 22sp 基准上的档位就是设计给的 19 / 16 / 13sp，其他基准同样步进。
        assertEquals(19f, AmountTextSizing.fontSizeSp(22f, 12), 0.01f)
        assertEquals(19f, AmountTextSizing.fontSizeSp(22f, 14), 0.01f)
        assertEquals(16f, AmountTextSizing.fontSizeSp(22f, 15), 0.01f)
        assertEquals(16f, AmountTextSizing.fontSizeSp(22f, 18), 0.01f)
        assertEquals(13f, AmountTextSizing.fontSizeSp(22f, 19), 0.01f)
    }

    @Test fun fontSizeNeverDropsBelowTheElevenSpFloor() {
        assertEquals(11f, AmountTextSizing.fontSizeSp(11f, 20), 0.01f)
        assertEquals(11f, AmountTextSizing.fontSizeSp(16f, 24), 0.01f)
        assertEquals(11f, AmountTextSizing.fontSizeSp(14f, 40), 0.01f)
        assertEquals(11f, AmountTextSizing.fontSizeSp(16f, 60), 0.01f)
    }

    @Test fun longerTextNeverRendersLarger() {
        var previous = Float.MAX_VALUE
        for (length in 1..48) {
            val size = AmountTextSizing.fontSizeSp(22f, length)
            assertTrue("length $length got $size", size <= previous)
            previous = size
        }
    }
}
