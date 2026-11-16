package com.bookkeeping.app.update

/**
 * One entry of `https://github.com/zxyzx0430/open-margin/releases.atom`.
 *
 * The feed has no asset list and no draft/prerelease flag, so [tag] is the only
 * trustworthy metadata: `-rc.N` in the tag is what makes a release a prerelease,
 * and whether a signed APK exists has to be probed separately.
 */
data class AtomRelease(
    val tag: String,
    /** `<updated>` exactly as published, e.g. `2026-11-16T16:26:14Z`. */
    val updated: String?,
    /** Release body as readable plain text (HTML decoded, tags removed). */
    val notes: String,
)

internal const val RELEASE_TAG_URL_PREFIX =
    "https://github.com/zxyzx0430/open-margin/releases/tag/"

private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
private val LINK_HREF = Regex("<link\\b[^>]*\\bhref=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
private val UPDATED = Regex("<updated>\\s*([^<]*?)\\s*</updated>", RegexOption.IGNORE_CASE)
private val CONTENT = Regex(
    "<content\\b[^>]*>(.*?)</content>",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
// Block boundaries become newlines so the dialog can show a few real lines
// instead of one 4000-character paragraph.
private val BLOCK_TAG = Regex(
    "</?(?:p|div|br|li|ul|ol|h[1-6]|tr|td|th|table|blockquote|pre|section)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)
private val ANY_TAG = Regex("<[^>]+>")
private val INLINE_SPACE = Regex("[ \\t\\u00a0]+")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

/**
 * Parses the Releases Atom feed without touching the network.
 *
 * Entries without a `…/releases/tag/` link, or whose tag is not a version this
 * app can compare, are dropped: a malformed entry must not hide the rest of the
 * feed. Feed order (newest first) is preserved; callers sort by version.
 */
internal fun parseAtom(xml: String): List<AtomRelease> {
    if (xml.isBlank()) return emptyList()
    val releases = mutableListOf<AtomRelease>()
    for (entry in ENTRY.findAll(xml)) {
        val body = entry.groupValues[1]
        val tag = tagOf(body) ?: continue
        if (ReleaseVersion.parse(tag) == null) continue
        val updated = UPDATED.find(body)?.groupValues?.get(1)?.trim()
        val content = CONTENT.find(body)?.groupValues?.get(1).orEmpty()
        releases += AtomRelease(
            tag = tag,
            updated = updated?.takeIf { it.isNotEmpty() },
            notes = htmlToText(content),
        )
    }
    return releases
}

/** Only the project's own release links count; any other href is ignored. */
private fun tagOf(entry: String): String? {
    for (link in LINK_HREF.findAll(entry)) {
        val href = link.groupValues[1]
        if (!href.startsWith(RELEASE_TAG_URL_PREFIX)) continue
        val tag = href.removePrefix(RELEASE_TAG_URL_PREFIX).trimEnd('/')
        if (tag.isEmpty()) continue
        if (tag.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) continue
        return tag
    }
    return null
}

private fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    // `<content type="html">` holds HTML source inside XML, so entities can be
    // escaped twice. Decode the XML layer first to recover the tags, then the
    // HTML layer to recover the text; both layers also cover feeds that only
    // escape once.
    val source = decodeEntities(html)
    val stripped = source
        .replace(HTML_COMMENT, "\n")
        .replace(BLOCK_TAG, "\n")
        .replace(ANY_TAG, "")
    return stripped.lines()
        .map { INLINE_SPACE.replace(decodeEntities(it), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

private fun decodeEntities(value: String): String {
    var text = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
    if (text.contains("&#")) {
        text = NUMERIC_ENTITY.replace(text) { match ->
            val digits = match.groupValues[2]
            val code = if (match.groupValues[1].isEmpty()) {
                digits.toIntOrNull(10)
            } else {
                digits.toIntOrNull(16)
            }
            if (code == null || code <= 0) match.value else String(Character.toChars(code))
        }
    }
    // `&amp;` last: decoding it first would turn `&amp;lt;` into `<`.
    return text.replace("&amp;", "&")
}
