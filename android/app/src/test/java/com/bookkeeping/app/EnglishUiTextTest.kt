package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishUiTextTest {
    @Test
    fun transactionAndNavigationTranslations() {
        val expected = mapOf(
            "账单" to "Transactions",
            "统计" to "Statistics",
            "预算" to "Budget",
            "设置" to "Settings",
            "支出" to "Expense",
            "收入" to "Income",
            "餐饮" to "Food",
            "转账" to "Transfer",
            "同步关闭或尚未执行" to "Sync off or not yet run",
            "启用双向同步" to "Enable two-way sync",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun unknownUserNotesStayUntouched() {
        // Never translate free-form user data or bank/notification parser data.
        assertEquals("我的自定义商户", EnglishUiText.translate("我的自定义商户"))
    }
}
