package com.bookkeeping.app.capture.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPageOwnAppTest {
    @Test fun ownAppChromeIsRejectedWithoutFilenamePackage() {
        assertTrue(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("清衡", "记一笔", "账单诊断日志", "支付宝"),
        ))
        assertTrue(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("记账与采集", "数据与同步", "商户分类记忆"),
        ))
        assertTrue(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("清衡(2)", "截图识别待确认", "自动记账服务正常运行中"),
        ))
        assertTrue(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("本月支出", "本月收入", "星巴克", "¥35.00", "账单", "统计", "预算", "订阅", "设置"),
        ))
        assertTrue(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("账单详情", "原图留证", "编辑账单", "删除账单"),
        ))
    }

    @Test fun realWechatOrAlipayBillsAreNotRejected() {
        assertFalse(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("支付宝", "交易成功", "付款方式", "¥ 26.00"),
        ))
        assertFalse(ScreenshotPageInterpreter.isOwnAppPageText(
            listOf("微信支付", "清衡", "收款成功", "¥ 26.00"),
        ))
    }
}
