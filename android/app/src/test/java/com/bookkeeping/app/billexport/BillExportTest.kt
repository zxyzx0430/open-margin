package com.bookkeeping.app.billexport

import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillExportTest {
    @Test
    fun csvEscapesQuotesCommasAndLineBreaks() {
        assertEquals("\"说\"\"好,继续\r\n下一行\"", BillExport.escapeCsv("说\"好,继续\r\n下一行"))
    }

    @Test
    fun amountUsesExactTwoDecimalPlacesWithoutGrouping() {
        assertEquals("0.01", BillExport.formatAmount(1L))
        assertEquals("1234.56", BillExport.formatAmount(123_456L))
        assertEquals("-0.01", BillExport.formatAmount(-1L))
    }

    @Test
    fun blankMerchantAndAccountStayEmptyInExportRow() {
        val row = BillExport.rows(
            listOf(
                BillExport.Record(
                    timestampMs = 0L,
                    type = "EXPENSE",
                    amountCents = 1L,
                    category = "FOOD",
                    merchant = "",
                    account = null,
                    source = "WECHAT",
                    note = "",
                ),
            ),
            timeZone = TimeZone.getTimeZone("UTC"),
        ).single()

        assertEquals("1970-01-01 00:00:00", row[0])
        assertEquals("支出", row[1])
        assertEquals("0.01", row[2])
        assertEquals("餐饮", row[3])
        assertEquals("", row[4])
        assertEquals("", row[5])
        assertEquals("微信", row[6])
        assertEquals("", row[7])
    }

    @Test
    fun csvStartsWithUtf8BomAndUsesChineseHeaders() {
        val bytes = BillExport.csvBytes(emptyList())

        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        assertTrue(String(bytes, Charsets.UTF_8).contains(BillExport.headers.joinToString(",")))
    }
}
