package com.bookkeeping.app.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CTC 输出按 token 分隔，中文会被塞进空格。解析“花了三十块”之前必须先把汉字之间的
 * 空格去掉；但中英混排和数字前后的空格要保留，否则会把 “iPhone 15” 粘成一坨。
 */
class AsrTextTest {

    @Test
    fun removesSpacesBetweenChineseCharacters() {
        assertEquals("花了三十块", AsrText.normalize("花 了 三 十 块"))
        assertEquals("中午吃饭花了二十五", AsrText.normalize("中午 吃饭 花了 二十五"))
    }

    @Test
    fun keepsSpacesNextToDigitsAndLatin() {
        assertEquals("买了 iPhone 15", AsrText.normalize("买了 iPhone 15"))
        assertEquals("打车花了 30 元", AsrText.normalize("打车 花了 30 元"))
        assertEquals("一共 3.5 元", AsrText.normalize("一共 3.5 元"))
    }

    @Test
    fun keepsPureEnglishTextUntouched() {
        val english = "spent twenty five yuan on lunch"
        assertEquals(english, AsrText.normalize(english))
    }

    @Test
    fun handlesEmptyAndShortInput() {
        assertEquals("", AsrText.normalize(""))
        assertEquals("好", AsrText.normalize("好"))
        assertEquals("a b", AsrText.normalize("a b"))
    }

    @Test
    fun collapsesOnlyCjkGapsNotLeadingOrTrailingSpaces() {
        assertEquals(" 吃饭 ", AsrText.normalize(" 吃饭 "))
    }
}
