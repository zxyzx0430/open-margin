package com.bookkeeping.app.billimport

/**
 * Small RFC 4180-style CSV/TSV reader. It deliberately keeps the raw field
 * strings; interpretation (amounts, dates, direction) lives in BillImportParser.
 */
object CsvParser {

    fun parse(text: String, delimiter: Char? = null): List<List<String>> {
        val normalized = text.removePrefix("﻿")
        val actualDelimiter = delimiter ?: detectDelimiter(normalized)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun finishField() {
            row.add(field.toString())
            field.clear()
        }

        fun finishRow() {
            finishField()
            rows.add(row.toList())
            row.clear()
        }

        while (index < normalized.length) {
            val char = normalized[index]
            if (inQuotes) {
                when {
                    char == '"' && index + 1 < normalized.length && normalized[index + 1] == '"' -> {
                        field.append('"')
                        index++
                    }
                    char == '"' -> inQuotes = false
                    else -> field.append(char)
                }
            } else {
                when (char) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(char)
                    actualDelimiter -> finishField()
                    '\r' -> {
                        if (index + 1 < normalized.length && normalized[index + 1] == '\n') index++
                        finishRow()
                    }
                    '\n' -> finishRow()
                    else -> field.append(char)
                }
            }
            index++
        }

        // A trailing newline must not create a phantom empty row.
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    /** Chooses the delimiter that appears most often outside quoted fields. */
    fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf('\t' to 0, ',' to 0, ';' to 0)
        var inQuotes = false
        var index = 0
        val scanLimit = minOf(text.length, 20_000)
        while (index < scanLimit) {
            val char = text[index]
            if (inQuotes) {
                if (char == '"' && index + 1 < scanLimit && text[index + 1] == '"') index++ else if (char == '"') inQuotes = false
            } else {
                when (char) {
                    '"' -> inQuotes = true
                    '\t', ',', ';' -> counts[char] = (counts[char] ?: 0) + 1
                }
            }
            index++
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }
}
