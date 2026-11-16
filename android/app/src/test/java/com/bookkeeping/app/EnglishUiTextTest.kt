package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishUiTextTest {
    @Test
    fun transactionAndNavigationTranslations() {
        val expected = mapOf(
            "账单" to "Records",
            "统计" to "Statistics",
            "预算" to "Budget",
            "设置" to "Settings",
            "支出" to "Expense",
            "收入" to "Income",
            "餐饮" to "Food",
            "转账" to "Transfer",
            "同步关闭或尚未执行" to "Sync off or not yet run",
            "启用双向同步" to "Enable two-way sync",
            "待更新组件：" to "Update components: ",
            "关于与更新" to "About & updates",
            "检查更新" to "Check for updates",
            "浏览全部发布版本" to "Browse all releases",
            "仅 Android APP 更新" to "Android app only",
            "仅 Windows / VPS 更新" to "Windows / VPS only",
            "Android APP 与 Windows / VPS 都有更新" to "Both Android app and Windows / VPS",
            "更新范围未注明" to "Update scope not specified",
            "点选单日 · 长按日期拖动选区间，拖到空白处稍作停留可跨月" to
                "Tap a day · long-press and drag to select a range; pause on the empty space to cross months",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun historicalSyncStatusesWithDynamicNumbersTranslate() {
        assertEquals(
            "Local transaction 12 is invalid and cannot sync",
            EnglishUiText.translate("本地交易 12 数据异常，无法同步")
        )
        assertEquals(
            "Server returned 503; retrying later",
            EnglishUiText.translate("服务器返回 503，稍后重试")
        )
        assertEquals(
            "Synced non-conflicting records; 3 changes still need review. Do not uninstall.",
            EnglishUiText.translate("已同步无冲突交易；仍有 3 笔修改待核对，请不要卸载应用")
        )
    }

    @Test
    fun unknownUserNotesStayUntouched() {
        // Never translate free-form user data or bank/notification parser data.
        assertEquals("我的自定义商户", EnglishUiText.translate("我的自定义商户"))
    }

    @Test
    fun transactionDialogAndImportMessagesTranslate() {
        assertEquals("Edit transaction", EnglishUiText.translate("编辑账单"))
        assertEquals("Date", EnglishUiText.translate("日期"))
        assertEquals("Invalid amount", EnglishUiText.translate("金额格式不正确"))
        assertEquals("Refund", EnglishUiText.translate("退款"))
        assertEquals(
            "Could not parse amount: 12.3.4",
            EnglishUiText.translate("无法解析金额：12.3.4"),
        )
        assertEquals(
            "Duplicate in this file; first occurrence on row 7",
            EnglishUiText.translate("文件内重复，首次出现在第 7 行"),
        )
        assertEquals("Daily average income", EnglishUiText.translate("日均收入"))
        assertEquals("Total expenses this month", EnglishUiText.translate("该月总支出"))
        assertEquals("Set this month's budget", EnglishUiText.translate("设置该月预算"))
        assertEquals("No data this month", EnglishUiText.translate("本月没有数据"))
    }

    @Test
    fun statsReadabilityLabelsTranslate() {
        // 统计页环形图标签、图例展开与分类明细跳转文案：中文是 key，缺一条英文就会静默显示中文。
        assertEquals("Last month's total", EnglishUiText.translate("上月金额"))
        assertEquals("Last year's total", EnglishUiText.translate("上一年金额"))
        assertEquals("Collapse", EnglishUiText.translate("收起"))
        assertEquals("View transaction details", EnglishUiText.translate("查看账单详情"))
        assertEquals("categories", EnglishUiText.translate("类"))
    }
}
