package com.bookkeeping.app.billimport

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {

    @Test
    fun parsesQuotedCommasQuotesAndNewlines() {
        val rows = CsvParser.parse("a,\"b,c\",\"d\"\"e\"\n\"x\ny\",z")
        assertEquals(listOf("a", "b,c", "d\"e"), rows[0])
        assertEquals(listOf("x\ny", "z"), rows[1])
    }

    @Test
    fun stripsBomAndKeepsTrailingEmptyField() {
        val rows = CsvParser.parse("﻿a,b,\n")
        assertEquals(listOf("a", "b", ""), rows[0])
        assertEquals(1, rows.size)
    }

    @Test
    fun detectsTabSeparatedText() {
        assertEquals('\t', CsvParser.detectDelimiter("时间\t商户\t金额\n1\t2\t3"))
        assertEquals(',', CsvParser.detectDelimiter("时间,商户,金额\n1,2,3"))
        assertEquals(listOf("时间", "商户", "金额"), CsvParser.parse("时间\t商户\t金额\n1\t2\t3")[0])
    }
}
