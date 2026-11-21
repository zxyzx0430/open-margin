package com.bookkeeping.app.ui.screen.capture

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图点开大图的状态机与解码/平移参数。
 *
 * 真正的弹窗、位图解码、双指缩放和点按关闭手感只能在真机上验证——这里锁住能纯 JVM
 * 证明的部分：预览目标只由当前候选的原图路径决定、关掉后路径被清空、解码不再无脑缩
 * 1/4、平移边界不会把图拖出可视区。
 *
 * 需要真机确认：`loadCapturePreview` 的实际解码（android.graphics.BitmapFactory）、
 * 双指缩放手感、放大后平移、系统返回关闭、文件被清理时的降级页。
 */
class CaptureImagePreviewStateTest {

    private val shot = "/data/user/0/files/shot.png"
    private val otherShot = "/data/user/0/files/other-shot.png"

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

    /**
     * 预览目标必须是「这张候选自己的原图路径」。
     *
     * 修复前预览用的是列表里 `inSampleSize = 4` 的位图；现在只传路径，所以显示哪张、多少
     * 分辨率全部由候选的 screenshotPath 决定。第二张候选必须覆盖第一张，不能继续预览上一张。
     */
    @Test
    fun previewTargetFollowsTheTappedCandidateOriginalPath() {
        val first = CaptureImagePreviewState().onThumbnailTapped(
            screenshotPath = shot,
            hasThumbnail = true,
        )
        val second = first.onThumbnailTapped(
            screenshotPath = otherShot,
            hasThumbnail = true,
        )
        assertEquals(shot, first.openPath)
        assertEquals(otherShot, second.openPath)
        assertEquals(otherShot, previewPathForCandidate(otherShot, hasThumbnail = true))
    }

    /**
     * 路径不可用时不能保留上一张的路径。
     *
     * 从「正在预览 A」到「点了没有原图路径的 B」，如果沿用「保持原状」的写法，A 会继续挂在
     * 屏幕上，用户看到的是旧图配新卡片。
     */
    @Test
    fun tappingCandidateWithoutPathClearsPreviousPreview() {
        val opened = CaptureImagePreviewState().onThumbnailTapped(
            screenshotPath = shot,
            hasThumbnail = true,
        )
        val afterMissingPath = opened.onThumbnailTapped(
            screenshotPath = null,
            hasThumbnail = true,
        )
        assertFalse(afterMissingPath.open)
        assertNull(afterMissingPath.openPath)

        val afterNoThumbnail = opened.onThumbnailTapped(
            screenshotPath = otherShot,
            hasThumbnail = false,
        )
        assertFalse(afterNoThumbnail.open)
        assertNull(afterNoThumbnail.openPath)
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

    /** 关掉再点另一张：中间没有残留，重开会拿到新路径而不是上一张。 */
    @Test
    fun reopeningAfterDismissUsesTheNewCandidatePath() {
        val reopened = CaptureImagePreviewState()
            .onThumbnailTapped(shot, hasThumbnail = true)
            .onDismissed()
            .onThumbnailTapped(otherShot, hasThumbnail = true)
        assertEquals(otherShot, reopened.openPath)
    }

    /**
     * 普通手机截图必须按原分辨率解码。
     *
     * 修复前的 `inSampleSize = 4` 让 1080×2400 只剩 270×600，放大后仍是马赛克。
     * 常见截图尺寸都必须算出 1，否则这次修复等于没做。
     */
    @Test
    fun phoneScreenshotsDecodeAtFullResolution() {
        listOf(
            1080 to 2400,
            1080 to 1920,
            1440 to 3200,
            1260 to 2800,
            720 to 1280,
            // 两张典型长截图也在预算内。
            1080 to 5000,
            1440 to 4000,
        ).forEach { (w, h) ->
            assertEquals("${w}x$h 应保持原分辨率", 1, previewSampleSize(w, h))
        }
        assertTrue(decodedPreviewPixels(1080, 5000, 1) <= MaxPreviewDecodedPixels)
    }

    /** 超预算的超长截图才降采样，且只用 2 的幂、结果必须同时落回像素与单边预算内。 */
    @Test
    fun oversizedScreenshotsAreDownsampledIntoTheBudget() {
        val width = 1080
        val height = 20_000
        val sample = previewSampleSize(width, height)
        assertTrue("超预算的图必须降采样", sample > 1)
        assertEquals("解码器只接受 2 的幂", 0, sample and (sample - 1))
        assertTrue("降采样后必须不超过像素预算", fitsPreviewBudget(width, height, sample))
        // 再降一半就不满足预算（像素或单边），说明没有过度降采样。
        assertFalse("不应多降一档", fitsPreviewBudget(width, height, sample / 2))
    }

    /** 与 [previewSampleSize] 同一套预算判据，用来说明「没有多降一档」。 */
    private fun fitsPreviewBudget(width: Int, height: Int, sample: Int): Boolean {
        val s = sample.coerceAtLeast(1)
        val longestSide = maxOf((width + s - 1) / s, (height + s - 1) / s)
        return decodedPreviewPixels(width, height, s) <= MaxPreviewDecodedPixels &&
            longestSide <= MaxPreviewDecodedDimension
    }

    @Test
    fun invalidDimensionsFallBackToFullResolution() {
        // 尺寸读不出来时让解码器自己失败，进入「图片读不出来」降级页。
        assertEquals(1, previewSampleSize(0, 0))
        assertEquals(1, previewSampleSize(-1, 100))
        assertEquals(1, previewSampleSize(100, 0))
    }

    /**
     * 平移边界：1x 不许动，放大后可移动范围正好等于溢出的一半。
     *
     * 少了这条，放大后能把图拖出屏幕、再也回不到整图。
     */
    @Test
    fun panIsLockedToTheOverflowOfTheScaledImage() {
        val content = 1000f
        val container = 1000f
        // 1x：没有溢出，禁止平移。
        assertEquals(0f, previewPanLimitPx(content, 1f, container))
        // 2x：总宽 2000，溢出 1000，单侧 500。
        assertEquals(500f, previewPanLimitPx(content, 2f, container))
        // 图片比屏幕窄时即使放大也不许平移出界。
        assertEquals(0f, previewPanLimitPx(400f, 1.5f, container))
        // 非法输入不产生负边界。
        assertEquals(0f, previewPanLimitPx(0f, 3f, container))
        assertEquals(0f, previewPanLimitPx(content, 3f, 0f))
    }

    /** 长截图竖轴：fit 之后高度占满，放大后必须能沿竖轴平移。 */
    @Test
    fun tallScreenshotCanPanVerticallyAfterZoom() {
        assertEquals(0f, previewPanLimitPx(1000f, 1f, 1000f))
        assertEquals(1000f, previewPanLimitPx(1000f, 3f, 1000f))
    }

    @Test
    fun previewHintHasEnglishText() {
        // 放大镜与全屏图共用这句提示；英文模式漏了就会退回中文。
        assertEquals("View full size", EnglishUiText.translate("查看大图"))
    }

    /** 放大后的「整图」复位按钮是新文案，英文模式不能退回中文。 */
    @Test
    fun resetToFitLabelHasEnglishText() {
        assertEquals("Fit to screen", EnglishUiText.translate("整图"))
    }
}
