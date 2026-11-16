package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the Atom fallback parser, using the same shape GitHub
 * serves from `releases.atom`: the release body arrives as HTML escaped inside
 * XML.
 */
class AtomParseTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
          <id>tag:github.com,2008:https://github.com/zxyzx0430/open-margin/releases</id>
          <link type="text/html" rel="alternate" href="https://github.com/zxyzx0430/open-margin/releases"/>
          <title>Release notes from open-margin</title>
          <updated>2026-11-16T16:26:08Z</updated>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/v1.2.5</id>
            <updated>2026-11-16T16:26:14Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/zxyzx0430/open-margin/releases/tag/v1.2.5"/>
            <title>清衡 v1.2.5</title>
            <content type="html">
        &lt;h1&gt;清衡 v1.2.5&lt;/h1&gt;
        &lt;p&gt;视觉收尾版：导出 &amp; 备份重新排版。&lt;/p&gt;
        &lt;h2&gt;账单详情&lt;/h2&gt;
        &lt;ul&gt;
        &lt;li&gt;底部按钮改成统一栅格，同高 46dp&lt;/li&gt;
        &lt;li&gt;「编辑」用主按钮，「删除」用 &lt;code&gt;error&lt;/code&gt; 描边&lt;/li&gt;
        &lt;/ul&gt;
        &lt;p&gt;版本：versionCode 24，versionName 1.2.5&lt;/p&gt;</content>
            <author>
              <name>zxyzx0430</name>
            </author>
            <media:thumbnail height="30" width="30" url="https://avatars.githubusercontent.com/u/266718509?s=60&amp;v=4"/>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/v1.2.6-rc.1</id>
            <updated>2026-11-17T02:10:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/zxyzx0430/open-margin/releases/tag/v1.2.6-rc.1"/>
            <title>清衡 v1.2.6-rc.1</title>
            <content type="html">
        &lt;h1&gt;清衡 v1.2.6-rc.1&lt;/h1&gt;
        &lt;p&gt;预发布：更新检查增加 Atom 回退。&lt;/p&gt;</content>
            <author>
              <name>zxyzx0430</name>
            </author>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/v1.3.0</id>
            <updated>2026-11-18T03:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/zxyzx0430/open-margin/releases/tag/v1.3.0"/>
            <title>服务端 v1.3.0</title>
            <content type="html"></content>
            <author>
              <name>zxyzx0430</name>
            </author>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/v9.9.9</id>
            <updated>2026-11-18T04:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/attacker/open-margin/releases/tag/v9.9.9"/>
            <title>伪造的仓库</title>
            <content type="html">&lt;p&gt;不应被当成正式发布&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/nightly</id>
            <updated>2026-11-18T05:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/zxyzx0430/open-margin/releases/tag/nightly"/>
            <title>nightly</title>
            <content type="html">&lt;p&gt;不是版本号&lt;/p&gt;</content>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1411610493/wiki</id>
            <updated>2026-11-18T06:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/zxyzx0430/open-margin/wiki"/>
            <title>没有 release 链接</title>
            <content type="html">&lt;p&gt;不应出现&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    private fun releases() = parseAtom(feed)

    private fun candidates(): List<PublishedRelease> = releases().mapNotNull { release ->
        val version = ReleaseVersion.parse(release.tag) ?: return@mapNotNull null
        PublishedRelease(
            tag = release.tag,
            version = version,
            prerelease = version.releaseCandidate != null,
            // Atom has no asset list, so the checker assumes an APK exists and
            // proves it with a HEAD probe.
            hasSignedApk = true,
            componentScope = UpdateComponentScope.fromReleaseNotes(release.notes),
        )
    }

    @Test fun onlyVersionedProjectReleaseEntriesAreKept() {
        // v9.9.9 points at a look-alike repository, `nightly` is not a version,
        // and the wiki entry has no releases/tag link: all three are dropped.
        assertEquals(listOf("v1.2.5", "v1.2.6-rc.1", "v1.3.0"), releases().map { it.tag })
    }

    @Test fun updatedTimestampIsKept() {
        assertEquals("2026-11-16T16:26:14Z", releases().first().updated)
        assertEquals("2026-11-16", UpdatePresentation.formatDate(releases().first().updated))
    }

    @Test fun versionsSortNewestFirst() {
        val versions = releases().mapNotNull { ReleaseVersion.parse(it.tag) }.sortedDescending()
        assertEquals(
            listOf("v1.3.0", "v1.2.6-rc.1", "v1.2.5"),
            versions.map { version -> version.toStringValue() },
        )
        assertTrue(ReleaseVersion.parse("v1.2.5")!! < ReleaseVersion.parse("v1.2.6-rc.1")!!)
        assertTrue(ReleaseVersion.parse("v1.2.6-rc.1")!! < ReleaseVersion.parse("v1.3.0")!!)
    }

    @Test fun prereleaseTagsAreOnlyOfferedWhenAsked() {
        val current = ReleaseVersion.parse("v1.2.4")!!
        // The server-only tag still wins on version alone.
        assertEquals(
            "v1.3.0",
            UpdateVersionPolicy.latestNewer(candidates(), current, includePrereleases = false)?.tag,
        )
        val androidReleases = candidates().filter { it.tag != "v1.3.0" }
        assertEquals(
            "v1.2.5",
            UpdateVersionPolicy.latestNewer(
                androidReleases, current, includePrereleases = false
            )?.tag,
        )
        assertEquals(
            "v1.2.6-rc.1",
            UpdateVersionPolicy.latestNewer(
                androidReleases, current, includePrereleases = true
            )?.tag,
        )
    }

    @Test fun htmlIsDecodedAndTagsAreRemoved() {
        val notes = releases().first { it.tag == "v1.2.5" }.notes
        assertFalse(notes.contains("<"))
        assertFalse(notes.contains(">"))
        assertFalse(notes.contains("&amp;"))
        assertFalse(notes.contains("&quot;"))
        assertFalse(notes.contains("&lt;"))
        assertTrue(notes.contains("导出 & 备份重新排版"))
        assertTrue(notes.contains("「编辑」用主按钮，「删除」用 error 描边"))
        // Block boundaries become separate lines, so the dialog can show three.
        assertEquals("清衡 v1.2.5", notes.lines().first())
        val dialogNotes = UpdatePresentation.notes(notes)
        assertEquals(3, dialogNotes.size)
        assertEquals("视觉收尾版：导出 & 备份重新排版。", dialogNotes[1])
    }

    @Test fun emptyContentIsNotFatal() {
        assertEquals("", releases().first { it.tag == "v1.3.0" }.notes)
        // Both `<content ...></content>` and a self-closing tag must parse.
        val selfClosing = feed.replace(
            "<content type=\"html\"></content>",
            "<content type=\"html\"/>",
        )
        assertEquals(
            listOf("v1.2.5", "v1.2.6-rc.1", "v1.3.0"),
            parseAtom(selfClosing).map { it.tag },
        )
    }

    @Test fun nonAtomInputYieldsNothingInsteadOfThrowing() {
        assertTrue(parseAtom("").isEmpty())
        assertTrue(parseAtom("""{"message":"API rate limit exceeded"}""").isEmpty())
        assertTrue(parseAtom("<feed><title>empty</title></feed>").isEmpty())
        assertTrue(parseAtom("<html><body>502 Bad Gateway</body></html>").isEmpty())
    }

    /** A release entry whose tag parse fails must not hide the other entries. */
    @Test fun unparsableTagDoesNotHideOtherEntries() {
        val mixed = """
            <feed>
              <entry>
                <updated>2026-11-18T02:00:00Z</updated>
                <link href="https://github.com/zxyzx0430/open-margin/releases/tag/nightly"/>
                <content type="html">&lt;p&gt;x&lt;/p&gt;</content>
              </entry>
              <entry>
                <updated>2026-11-18T01:00:00Z</updated>
                <link href="https://github.com/zxyzx0430/open-margin/releases/tag/v1.2.7"/>
                <content type="html">&lt;p&gt;y&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf("v1.2.7"), parseAtom(mixed).map { it.tag })
    }
}

/** Test-only rendering of a version, so assertion failures print readable tags. */
private fun ReleaseVersion.toStringValue(): String = buildString {
    append("v").append(major).append('.').append(minor).append('.').append(patch)
    releaseCandidate?.let { append("-rc.").append(it) }
}
