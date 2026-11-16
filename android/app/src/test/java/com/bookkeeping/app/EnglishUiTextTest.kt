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
            "分类占比" to "By category",
            "已选中" to "Selected",
            "最高" to "Highest",
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
    fun updateInstallFlowLabelsTranslate() {
        val expected = mapOf(
            "发现新版本" to "New version available",
            "APK 大小" to "APK size",
            "发布日期" to "Release date",
            "下载" to "Download",
            "下载中" to "Downloading",
            "安装" to "Install",
            "下载失败，请重试" to "Download failed. Try again.",
            "没有可下载的正式版 APK" to "No stable APK is available for download.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
        // The removed supplementary notice must not linger as an orphan key.
        assertEquals(
            "检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。",
            EnglishUiText.translate("检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。"),
        )
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

    @Test
    fun calendarScreenLabelsTranslate() {
        // 日历页是「中文是 key」最容易漏的一块：底部 tab、标题、汇总卡、菜单、
        // 空状态、农历开关、周起始日、月份选择器，缺一条英文就会在英文模式里露出中文。
        val expected = mapOf(
            "日历" to "Calendar",
            "日历设置" to "Calendar settings",
            "上个月" to "Previous month",
            "下个月" to "Next month",
            "本月汇总" to "This month's summary",
            "支出" to "Expense",
            "收入" to "Income",
            "结余" to "Balance",
            "笔" to "transactions",
            "清除" to "Clear",
            "清除选择" to "Clear selection",
            "日期高亮：" to "Day highlight: ",
            "关闭" to "Close",
            "周起始日：" to "Week starts on: ",
            "周一" to "Monday",
            "周日" to "Sunday",
            "农历：" to "Lunar calendar: ",
            "显示" to "Show",
            "隐藏" to "Hide",
            "点选单日 · 长按日期拖动选区间，拖到空白处稍作停留可跨月" to
                "Tap a day · long-press and drag to select a range; pause on the empty space to cross months",
            "长按日期拖动选择区间，或点选单日" to
                "Press and drag on a day to select a range, or tap a single day",
            "这一天你还没有任何记账" to "No transactions recorded on this day",
            "这段时间你还没有任何记账" to "No transactions recorded during this period",
            "滑动日期选择区间，或点选单日" to "Swipe to select a date range, or tap a single day",
            "未知商户" to "Unknown merchant",
            "退款" to "Refund",
            "待确认重复" to "Possible duplicates",
            "转账" to "Transfer",
            "不计入" to "Excluded",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun monthPickerLabelsTranslate() {
        assertEquals("Select month", EnglishUiText.translate("选择月份"))
        assertEquals("Back to this month", EnglishUiText.translate("回到本月"))
        assertEquals("Previous year", EnglishUiText.translate("上一年"))
        assertEquals("Next year", EnglishUiText.translate("下一年"))
        assertEquals("Cancel", EnglishUiText.translate("取消"))
    }

    @Test
    fun merchantRulesPageLabelsTranslate() {
        // 商户分类记忆页：标题栏、搜索、列表状态、空状态、编辑面板、清除确认。
        val expected = mapOf(
            "商户分类记忆" to "Merchant category memory",
            "添加" to "Add",
            "搜索商户" to "Search merchants",
            "没有匹配的商户" to "No matching merchants",
            "已生效" to "Active",
            "已暂停学习" to "Learning paused",
            "还没有自定义规则" to "No custom rules yet",
            "清除全部分类记忆" to "Clear all category memory",
            "清除全部分类记忆？" to "Clear all category memory?",
            "已清除" to "Cleared",
            "已保存" to "Saved",
            "已删除" to "Deleted",
            "添加规则" to "Add rule",
            "编辑规则" to "Edit rule",
            "最近商户" to "Recent merchants",
            "已有规则" to "Has rule",
            "暂停学习此商户" to "Pause learning for this merchant",
            "删除这条规则？" to "Delete this rule?",
            "请先输入商户名" to "Enter a merchant name first",
            "请选择分类" to "Select a category",
            "改为编辑" to "Edit it instead",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }
}
