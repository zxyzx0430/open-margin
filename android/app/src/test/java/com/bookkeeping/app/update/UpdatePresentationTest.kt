package com.bookkeeping.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePresentationTest {
    private val body = """
        <!-- open-margin-components: android -->

        # 清衡 v1.2.3

        三件事：采集不再重复录入、状态面板重做、详情页可以改全部字段。

        ## 采集自动合并

        - 同来源、同金额、同方向，**时间差 2 分钟内**合并
        - 保留先入库那条 id

        ## 详情页全字段编辑

        见 [发布说明](https://example.com/notes)。
    """.trimIndent()

    @Test fun summaryDropsCommentsMarkdownAndReleaseTitle() {
        val lines = UpdatePresentation.notes(body)
        assertEquals(3, lines.size)
        assertEquals("三件事：采集不再重复录入、状态面板重做、详情页可以改全部字段。", lines[0])
        assertEquals("采集自动合并", lines[1])
        assertEquals("同来源、同金额、同方向，时间差 2 分钟内合并", lines[2])
        assertTrue(lines.none { it.contains("open-margin-components") })
        assertTrue(lines.none { it.contains("清衡 v1.2.3") })
        assertTrue(lines.none { it.startsWith("#") || it.startsWith("-") })
    }

    @Test fun linksKeepTheirTextAndEmptyBodiesYieldNothing() {
        assertEquals(
            listOf("见 发布说明。"),
            UpdatePresentation.notes("见 [发布说明](https://example.com/notes)。", maxLines = 1),
        )
        assertTrue(UpdatePresentation.notes(null).isEmpty())
        assertTrue(UpdatePresentation.notes("   ").isEmpty())
        assertTrue(UpdatePresentation.notes("<!-- only a comment -->").isEmpty())
        assertTrue(UpdatePresentation.notes("# 只有标题").isEmpty())
    }

    @Test fun longLinesAreTruncated() {
        val long = "长".repeat(400)
        val line = UpdatePresentation.notes(long, maxLines = 1).single()
        assertEquals(140, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test fun sizeIsShownInMegabytes() {
        assertEquals("45.8 MB", UpdatePresentation.formatSize(48_000_000L))
        assertEquals("0.5 MB", UpdatePresentation.formatSize(524_288L))
        assertNull(UpdatePresentation.formatSize(0L))
        assertNull(UpdatePresentation.formatSize(-1L))
    }

    @Test fun publishDateIsReducedToTheDay() {
        assertEquals("2026-11-10", UpdatePresentation.formatDate("2026-11-10T09:31:00Z"))
        assertNull(UpdatePresentation.formatDate(null))
        assertNull(UpdatePresentation.formatDate(""))
        assertNull(UpdatePresentation.formatDate("yesterday"))
    }
}
