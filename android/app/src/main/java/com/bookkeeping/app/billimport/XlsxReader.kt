package com.bookkeeping.app.billimport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Minimal XLSX reader with no new dependency. It reads the first worksheet and
 * shared strings, which is enough for the single-sheet exports produced by
 * WeChat Pay and Alipay. Formulas are read through their cached <v> value.
 */
internal object XlsxReader {

    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    fun readRows(bytes: ByteArray, maxRows: Int = 50_000): List<List<String>> {
        val entries = readZipEntries(bytes)
        require(entries.isNotEmpty()) { "XLSX 文件为空或不是有效压缩包" }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheetName = entries.keys
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .sorted()
            .firstOrNull { it.endsWith("/sheet1.xml") }
            ?: entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted().firstOrNull()
            ?: throw IllegalArgumentException("XLSX 缺少工作表")
        return parseSheet(entries.getValue(sheetName), sharedStrings, maxRows)
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ENTRY_BYTES) { "XLSX 条目过大：${entry.name}" }
                        buffer.write(chunk, 0, read)
                    }
                    result[entry.name] = buffer.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val document = parseXml(bytes)
        val nodes = document.getElementsByTagName("si")
        return buildList(nodes.length) {
            for (index in 0 until nodes.length) {
                val texts = (nodes.item(index) as? Element)?.getElementsByTagName("t")
                val value = buildString {
                    if (texts != null) {
                        for (textIndex in 0 until texts.length) {
                            append(texts.item(textIndex).textContent.orEmpty())
                        }
                    }
                }
                add(value)
            }
        }
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>, maxRows: Int): List<List<String>> {
        val document = parseXml(bytes)
        val rowNodes = document.getElementsByTagName("row")
        val rows = mutableListOf<List<String>>()
        for (rowIndex in 0 until rowNodes.length) {
            if (rows.size >= maxRows) break
            val row = (rowNodes.item(rowIndex) as? Element) ?: continue
            val cells = row.getElementsByTagName("c")
            val values = mutableListOf<String>()
            var sequentialColumn = 0
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as? Element ?: continue
                val ref = cell.getAttribute("r").orEmpty()
                val column = if (ref.isNotBlank()) columnIndex(ref) else sequentialColumn
                sequentialColumn = column + 1
                val value = cellValue(cell, sharedStrings)
                if (values.size <= column) {
                    repeat(column + 1 - values.size) { values.add("") }
                }
                values[column] = value
            }
            rows.add(values)
        }
        return rows
    }

    private fun cellValue(cell: Element, sharedStrings: List<String>): String {
        val type = cell.getAttribute("t").orEmpty()
        if (type == "inlineStr") {
            val inline = cell.getElementsByTagName("t")
            return buildString {
                for (index in 0 until inline.length) append(inline.item(index).textContent.orEmpty())
            }
        }
        val value = firstText(cell, "v")
        return when (type) {
            "s" -> value.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "b" -> if (value == "1") "TRUE" else "FALSE"
            "e" -> ""
            else -> value
        }
    }

    private fun firstText(element: Element, tag: String): String {
        val nodes = element.getElementsByTagName(tag)
        return if (nodes.length == 0) "" else nodes.item(0).textContent.orEmpty()
    }

    private fun columnIndex(ref: String): Int {
        var value = 0
        var sawLetter = false
        for (char in ref) {
            if (char.isLetter()) {
                sawLetter = true
                value = value * 26 + (char.uppercaseChar() - 'A' + 1)
            } else if (sawLetter) {
                break
            }
        }
        return (value - 1).coerceAtLeast(0)
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
}
