package com.bookkeeping.app.update

import java.util.Locale

/**
 * Text shown in the update dialog. Release bodies are Markdown written by the
 * maintainers, so the dialog needs a short plain-text summary instead of a
 * scrollable document. Pure JVM code: covered by unit tests.
 */
object UpdatePresentation {
    private const val MAX_NOTE_LENGTH = 140

    private val HTML_COMMENT = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
    private val HTML_TAG = Regex("<[^>]+>")
    private val TITLE_LINE = Regex("^#\\s+")
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLET = Regex("^[-*+>]\\s+")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val EMPHASIS = Regex("[*_`]+")
    private val DATE_PREFIX = Regex("^(\\d{4}-\\d{2}-\\d{2})")

    /**
     * At most [maxLines] plain lines: HTML comments and Markdown markers are
     * removed, and the `# Release title` line is skipped because it only
     * repeats the version already shown in the dialog title.
     */
    fun notes(body: String?, maxLines: Int = 3): List<String> {
        if (body.isNullOrBlank() || maxLines <= 0) return emptyList()
        val plain = body.replace(HTML_COMMENT, "\n").replace(HTML_TAG, " ")
        val lines = mutableListOf<String>()
        for (raw in plain.lines()) {
            if (TITLE_LINE.containsMatchIn(raw.trim())) continue
            val text = cleanLine(raw)
            if (text.isEmpty()) continue
            lines += text
            if (lines.size == maxLines) break
        }
        return lines
    }

    private fun cleanLine(raw: String): String {
        var line = raw.trim()
        line = HEADING.replace(line, "")
        line = BULLET.replace(line, "")
        line = LINK.replace(line, "$1")
        line = EMPHASIS.replace(line, "")
        line = line.replace(Regex("\\s+"), " ").trim()
        if (line.isEmpty()) return ""
        if (line.length > MAX_NOTE_LENGTH) {
            line = line.take(MAX_NOTE_LENGTH - 1).trimEnd() + "…"
        }
        return line
    }

    /** APK size in MB, the unit users compare against the release page. */
    fun formatSize(bytes: Long): String? {
        if (bytes <= 0L) return null
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** `2026-11-10T09:00:00Z` -> `2026-11-10`; anything else is hidden. */
    fun formatDate(publishedAt: String?): String? {
        val value = publishedAt?.trim().orEmpty()
        return DATE_PREFIX.find(value)?.groupValues?.get(1)
    }
}
