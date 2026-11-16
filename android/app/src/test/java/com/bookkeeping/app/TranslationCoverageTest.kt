package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * English mode is keyed by the Chinese label, so a missing English entry does not
 * crash or warn — the screen just shows Chinese. This scans every tr("…") literal
 * in main sources and fails when one has no English counterpart, instead of relying
 * on hand-picked assertions.
 */
class TranslationCoverageTest {
    private val chinese = Regex("[\\u4e00-\\u9fff]")

    // tr("…") and AppLanguage.tr(context, "…"); dynamic arguments are skipped.
    private val call =
        Regex("\\btr\\(\\s*(?:[A-Za-z_][\\w.]*\\s*,\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)")

    @Test
    fun everyTranslatedLiteralHasAnEnglishEntry() {
        val root = sourceRoot()
        val missing = linkedMapOf<String, MutableSet<String>>()
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "EnglishUiText.kt" }
        for (file in sources) {
            val text = file.readText()
            for (match in call.findAll(text)) {
                val literal = match.groupValues[1]
                if (!chinese.containsMatchIn(literal)) continue
                // Interpolated keys are built at runtime; they cannot be map keys.
                if (literal.contains('$')) continue
                if (!EnglishUiText.hasTranslation(literal)) {
                    missing.getOrPut(literal) { sortedSetOf() }
                        .add(file.relativeTo(root).invariantSeparatorsPath)
                }
            }
        }
        assertTrue(
            "Missing English UI text (add it to EnglishUiText and assert it in EnglishUiTextTest): " +
                missing.entries.joinToString("; ") { (label, files) -> "$label <- $files" },
            missing.isEmpty(),
        )
    }

    private fun sourceRoot(): File {
        val direct = listOf(
            File("src/main/java/com/bookkeeping/app"),
            File("app/src/main/java/com/bookkeeping/app"),
            File("android/app/src/main/java/com/bookkeeping/app"),
        )
        direct.firstOrNull { it.isDirectory }?.let { return it }
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "android/app/src/main/java/com/bookkeeping/app")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: break
        }
        throw AssertionError(
            "Cannot locate main sources from ${System.getProperty("user.dir")}; looked for " +
                direct.joinToString { it.path },
        )
    }
}
