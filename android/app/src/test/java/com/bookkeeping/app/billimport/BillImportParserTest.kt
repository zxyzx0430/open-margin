package com.bookkeeping.app.billimport

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillImportParserTest {

    private val wechatCsv = """
        微信支付账单明细
        导出时间：2026-11-14
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2026-11-13 10:08:00,商户消费,星巴克,咖啡,支出,"¥38.00",零钱,支付成功,4200001,SH001,
        2026-11-12 09:52:00,转账,张三,转账,收入,200.00,零钱,已收款,4200002,,
        2026-11-11 09:00:00,商户消费,盒马,购物,支出,"1,120.30",银行卡,已全额退款,4200003,SH003,
        2026-11-10 08:00:00,商户消费,失败商户,购物,支出,10.00,银行卡,支付失败,4200004,SH004,
        2026-11-09 08:00:00,商户消费,重复商户,购物,支出,5.00,银行卡,支付成功,4200005,SH005,
        2026-11-09 08:00:00,商户消费,重复商户,购物,支出,5.00,银行卡,支付成功,4200005,SH005,
    """.trimIndent() + "\n"

    @Test
    fun parsesWechatCsvAndClassifiesRows() {
        val preview = BillImportParser.parse(wechatCsv.toByteArray(), "wechat.csv")
        assertEquals(BillFormat.CSV, preview.format)
        assertEquals(BillSource.WECHAT, preview.source)
        assertTrue(preview.requiresConfirmation)
        assertEquals(3, preview.validRows.size)
        assertEquals(1, preview.duplicateRows.size)
        assertEquals(2, preview.errorRows.size)

        val starbucks = preview.validRows.first { it.merchant == "星巴克" }
        assertEquals(3_800L, starbucks.amountCents)
        assertEquals(BillDirection.EXPENSE, starbucks.direction)
        assertEquals(BillStatus.SUCCESS, starbucks.status)

        val income = preview.validRows.first { it.merchant == "张三" }
        assertEquals(20_000L, income.amountCents)
        assertEquals(BillDirection.INCOME, income.direction)

        val refund = preview.errorRows.first { it.parsed?.status == BillStatus.REFUND }
        assertEquals("盒马", refund.parsed?.merchant)
        assertNotNull(refund.message)

        assertTrue(preview.errorRows.any { it.parsed?.status == BillStatus.FAILED })
    }

    @Test
    fun skipsFooterSummaryRows() {
        val withFooter = wechatCsv + "共6笔,支出：1,173.30,收入：200.00,,,,,,,\n"
        val preview = BillImportParser.parse(withFooter.toByteArray(), "wechat.csv")
        assertEquals(3, preview.validRows.size)
        assertEquals(2, preview.errorRows.size)
        assertTrue(preview.rows.none { it.message?.contains("交易时间为空") == true })
    }

    @Test
    fun parsesAlipayCsv() {
        val csv = """
            交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-13 20:11:12,餐饮,海底捞,火锅,支出,238.00,余额宝,交易成功,20261113001,AL001,
            2026-11-12 08:32:00,交通,地铁,乘车,支出,6.00,余额宝,交易成功,20261112001,AL002,
        """.trimIndent() + "\n"
        val preview = BillImportParser.parse(csv.toByteArray(), "alipay.csv")
        assertEquals(BillSource.ALIPAY, preview.source)
        assertEquals(2, preview.validRows.size)
        assertEquals(23_800L, preview.validRows.first().amountCents)
        assertEquals("海底捞", preview.validRows.first().merchant)
    }

    @Test
    fun detectsBomAndGbkEncodedExports() {
        val text = "交易时间,交易对方,金额,收/支,当前状态,交易单号\n" +
            "2026-11-13 10:00:00,测试商户,12.34,支出,支付成功,GBK1\n"
        val bom = BillImportParser.parse(("﻿$text").toByteArray(Charsets.UTF_8), "wechat.csv")
        assertEquals(1, bom.validRows.size)
        assertEquals(1_234L, bom.validRows.single().amountCents)

        val gbk = BillImportParser.parse(text.toByteArray(Charset.forName("GBK")), "wechat.csv")
        assertEquals(1, gbk.validRows.size)
        assertEquals("测试商户", gbk.validRows.single().merchant)
    }

    @Test
    fun flagsRowsAlreadyPresentInTheLedger() {
        val first = BillImportParser.parse(wechatCsv.toByteArray(), "wechat.csv")
        val key = BillImportParser.duplicateKey(first.validRows.first())
        val again = BillImportParser.parse(
            wechatCsv.toByteArray(),
            "wechat.csv",
            BillParserOptions(existingDuplicateKeys = setOf(key)),
        )
        assertEquals(2, again.validRows.size)
        assertTrue(again.duplicateRows.any { it.message?.contains("账本中已存在") == true })
    }

    @Test
    fun parsesMinimalXlsx() {
        val bytes = buildXlsx()
        val preview = BillImportParser.parse(bytes, "wechat.xlsx")
        assertEquals(BillFormat.XLSX, preview.format)
        assertEquals(1, preview.validRows.size)
        assertEquals("星巴克", preview.validRows.single().merchant)
        assertEquals(3_800L, preview.validRows.single().amountCents)
    }

    @Test
    fun parsesTabSeparatedText() {
        val text = "交易时间\t交易对方\t金额\t收/支\t当前状态\t交易单号\n" +
            "2026-11-13 10:00:00\t测试商户\t12.34\t支出\t支付成功\tT1\n"
        val preview = BillImportParser.parseText(text, "export.txt")
        assertEquals(BillFormat.TEXT, preview.format)
        assertEquals(1, preview.validRows.size)
    }

    private fun buildXlsx(): ByteArray {
        val strings = listOf(
            "交易时间", "交易对方", "金额(元)", "收/支", "当前状态", "交易单号",
            "2026-11-13 10:08:00", "星巴克", "支出", "支付成功", "X1",
        )
        val shared = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            strings.forEach { append("<si><t>").append(xmlEscape(it)).append("</t></si>") }
            append("</sst>")
        }
        val sheet = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            append("""<row r="1">""")
            append("""<c r="A1" t="s"><v>0</v></c>""")
            append("""<c r="B1" t="s"><v>1</v></c>""")
            append("""<c r="C1" t="s"><v>2</v></c>""")
            append("""<c r="D1" t="s"><v>3</v></c>""")
            append("""<c r="E1" t="s"><v>4</v></c>""")
            append("""<c r="F1" t="s"><v>5</v></c>""")
            append("</row>")
            append("""<row r="2">""")
            append("""<c r="A2" t="s"><v>6</v></c>""")
            append("""<c r="B2" t="s"><v>7</v></c>""")
            append("""<c r="C2"><v>38.00</v></c>""")
            append("""<c r="D2" t="s"><v>8</v></c>""")
            append("""<c r="E2" t="s"><v>9</v></c>""")
            append("""<c r="F2" t="s"><v>10</v></c>""")
            append("</row>")
            append("</sheetData></worksheet>")
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheet.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
