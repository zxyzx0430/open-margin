package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图点开大图的状态机。
 *
 * 真正的弹窗、双指缩放和点按关闭手感要在真机上验证——这里只锁住“什么时候允许点”
 * 和“点开/关掉之后状态是什么”：这两条错了会让用户点不开，或者关掉后第二张图
 * 还挂着上一张的状态。
 */
class CaptureImagePreviewStateTest {

    private val shot = "/data/user/0/files/shot.png"

    @Test
    fun thumbnailWithScreenshotCanBePreviewed() {
        assertTrue(canPreviewCaptureImage(shot, hasThumbnail = true))
    }

    @Test
    fun missingThumbnailCannotBePreviewed() {
        // 没解码出缩略图时卡片显示分类图标，本来就没有可点的图。
        assertFalse(canPreviewCaptureImage(shot, hasThumbnail = false))
    }

    @Test
    fun blankOrMissingScreenshotPathCannotBePreviewed() {
        // 解码结果还在但文件已被清理：点开只会看到空白，不如不给点。
        assertFalse(canPreviewCaptureImage(null, hasThumbnail = true))
        assertFalse(canPreviewCaptureImage("", hasThumbnail = true))
        assertFalse(canPreviewCaptureImage("   ", hasThumbnail = true))
    }

    @Test
    fun tappingThumbnailOpensThatScreenshot() {
        val opened = CaptureImagePreviewState().onThumbnailTapped(
            screenshotPath = shot,
            hasThumbnail = true,
        )
        assertTrue(opened.open)
        assertEquals(shot, opened.openPath)
    }

    @Test
    fun tappingWithoutThumbnailKeepsPreviewClosed() {
        val state = CaptureImagePreviewState().onThumbnailTapped(
            screenshotPath = shot,
            hasThumbnail = false,
        )
        assertFalse(state.open)
        assertNull(state.openPath)
        assertEquals(CaptureImagePreviewState(), state)
    }

    @Test
    fun dismissingReturnsToNullState() {
        val opened = CaptureImagePreviewState().onThumbnailTapped(
            screenshotPath = shot,
            hasThumbnail = true,
        )
        assertTrue(opened.open)

        val dismissed = opened.onDismissed()
        assertFalse(dismissed.open)
        // 必须回到干净的初始态，而不是只把 open 置 false 还留着旧路径。
        assertNull(dismissed.openPath)
        assertEquals(CaptureImagePreviewState(), dismissed)
    }

    @Test
    fun previewHintHasEnglishText() {
        // 放大镜与全屏图共用这句提示；英文模式漏了就会退回中文。
        assertEquals("View full size", EnglishUiText.translate("查看大图"))
    }
}
