package com.bookkeeping.app.billimport

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual regression harness for the user's real Alipay annual export. The file
 * stays outside the repository; set ALIPAY_BILL_CSV to run it locally.
 */
class BillImportRealFileTest {

    @Test
    fun annualAlipayExportHasNoParseErrors() {
        val path = System.getenv("ALIPAY_BILL_CSV")
        assumeTrue("ALIPAY_BILL_CSV not set", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("sample file missing", file.isFile)

        val preview = BillImportParser.parse(file.readBytes(), file.name)
        val total = preview.validRows.size + preview.skippedRows.size +
            preview.duplicateRows.size + preview.errorRows.size
        println(
            "alipay-real: total=$total valid=${preview.validRows.size} " +
                "duplicate=${preview.duplicateRows.size} skipped=${preview.skippedRows.size} " +
                "errors=${preview.errorRows.size}",
        )
        assertEquals(415, total)
        assertEquals(0, preview.errorRows.size)
        assertEquals(346, preview.validRows.size)
        assertEquals(69, preview.skippedRows.size)
        assertEquals(0, preview.duplicateRows.size)
    }
}
