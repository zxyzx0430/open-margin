package com.bookkeeping.app.ui.screen.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** 全屏预览的遮罩：0xF0 不透明，底下待确认列表只剩一点影子。 */
private val PreviewScrim = Color(0xF0000000)
private const val MinPreviewScale = 1f
private const val MaxPreviewScale = 6f
private const val DoubleTapScale = 2.5f
private const val FitScaleTolerance = 0.01f

/**
 * 预览解码的内存预算（像素数，8MP ≈ 32MB ARGB）。
 *
 * 旧实现固定 `inSampleSize = 4`，1080×2400 的截图只剩 270×600，再放大也只是把马赛克
 * 放大。现在只按这个预算做「2 的幂」降采样：普通手机截图（2–5MP）保持原分辨率，
 * 只有拼接长截图这类几十 MP 的图才降一档，避免低端机 OOM。
 */
internal const val MaxPreviewDecodedPixels = 8_000_000L

/**
 * 解码后单边上限（像素）。
 *
 * 极窄极长的拼接图（如 500×16000）像素不多，但高度会超过 GPU 的最大纹理尺寸，硬件
 * 画布可能整张画不出来。8192 覆盖常见手机截图与多数 GLES3 设备的纹理上限，只拦极端长图。
 */
internal const val MaxPreviewDecodedDimension = 8192

/**
 * 解码后的预览结果。
 *
 * 失败原因不在界面上细分：路径失效、文件被清理、解码失败都落到 [Unavailable]，
 * 只显示一句「图片读不出来」+ 关闭，不显示上一张图，也不崩。
 */
internal sealed interface CapturePreviewImage {
    data object Loading : CapturePreviewImage

    data class Loaded(val bitmap: ImageBitmap) : CapturePreviewImage

    data object Unavailable : CapturePreviewImage
}

/**
 * 待确认卡片缩略图点开后的全屏预览。
 *
 * 只收原图路径 [path]（`candidate.screenshotPath`），不再复用列表里那张 1/4 缩小的缩略图：
 * 那张图放大到几倍也看不清商户名。[loadCapturePreview] 在 IO 线程读盘，并按
 * [previewSampleSize] 只做必要的降采样。
 *
 * 手势：双指缩放 1x–6x，放大后可平移（边界由 [previewPanLimitPx] 限制在图片内，1x 时偏移
 * 归零），双击在整图/2.5x 之间切换，右下角「整图」按钮随时复位。关闭入口四个：X、系统返回、
 * 点背景、降级态里的关闭按钮。
 */
@Composable
internal fun CaptureImagePreview(
    path: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        // 系统返回键与点 Dialog 外部都走这里，和 X、点背景是同一套关闭语义。
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 以 path 为 key：换一张候选就重新读盘并重置缩放，不残留上一张的位图。
        var image by remember(path) {
            mutableStateOf<CapturePreviewImage>(CapturePreviewImage.Loading)
        }
        LaunchedEffect(path) {
            image = loadCapturePreview(path)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PreviewScrim),
        ) {
            when (val current = image) {
                CapturePreviewImage.Loading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }

                is CapturePreviewImage.Loaded -> ZoomablePreviewImage(
                    bitmap = current.bitmap,
                    onBackgroundTap = onDismiss,
                )

                CapturePreviewImage.Unavailable -> PreviewUnavailable(onDismiss = onDismiss)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 全屏 Dialog 会画到状态栏下面，不避让的话 X 压在状态栏图标上点不到。
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = tr("关闭"),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomablePreviewImage(
    bitmap: ImageBitmap,
    onBackgroundTap: () -> Unit,
) {
    var scale by remember(bitmap) { mutableStateOf(MinPreviewScale) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var container by remember(bitmap) { mutableStateOf(IntSize.Zero) }

    val containerWidth = container.width.toFloat()
    val containerHeight = container.height.toFloat()
    // fit-to-screen 后图片实际占多大：平移边界按显示尺寸算，不按原图像素算。
    val fitScale = if (bitmap.width > 0 && bitmap.height > 0 &&
        containerWidth > 0f && containerHeight > 0f
    ) {
        min(containerWidth / bitmap.width, containerHeight / bitmap.height)
    } else {
        0f
    }
    val contentWidth = bitmap.width * fitScale
    val contentHeight = bitmap.height * fitScale

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            // 关闭用 detectTapGestures 而不是整屏 clickable：全屏无标签按钮会让 TalkBack
            // 读出“未命名按钮”。点击检测挂在父级、缩放/平移挂在子级图片上：子级先收到
            // Main pass 并消费掉拖动，父级就不会把一次双指缩放当成点击而误关预览。
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { onBackgroundTap() },
                    onDoubleTap = {
                        val zoomed = scale > MinPreviewScale + FitScaleTolerance
                        scale = if (zoomed) MinPreviewScale else DoubleTapScale
                        offset = Offset.Zero
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = tr("扫描截图"),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                // pointerInput 必须在 graphicsLayer 之前：放在缩放层里面的话，触摸坐标会被
                // 反向变换，pan 与缩放中心都会按缩放倍数漂移。
                .pointerInput(bitmap, containerWidth, containerHeight) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val previous = scale
                        val next = (previous * zoom).coerceIn(MinPreviewScale, MaxPreviewScale)
                        val factor = if (previous > 0f) next / previous else 1f
                        val limitX = previewPanLimitPx(contentWidth, next, containerWidth)
                        val limitY = previewPanLimitPx(contentHeight, next, containerHeight)
                        // 以双指中心为焦点缩放：先按焦点补偿上一帧的偏移，再叠加本帧平移。
                        val focusX = centroid.x - containerWidth / 2f
                        val focusY = centroid.y - containerHeight / 2f
                        val nextX = offset.x * factor + focusX * (1f - factor) + pan.x
                        val nextY = offset.y * factor + focusY * (1f - factor) + pan.y
                        scale = next
                        offset = Offset(
                            nextX.coerceIn(-limitX, limitX),
                            nextY.coerceIn(-limitY, limitY),
                        )
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    // 平移必须跟缩放一起生效：只放大不平移，长截图下面几行永远看不到。
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin.Center
                },
        )
        if (scale > MinPreviewScale + FitScaleTolerance) {
            // 放大后必须有一个可见的出口回到整图，不能只靠「再缩回去」。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable {
                        scale = MinPreviewScale
                        offset = Offset.Zero
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = tr("整图"),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun PreviewUnavailable(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = tr("图片读不出来"),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) {
                Text(text = tr("关闭"), color = Color.White)
            }
        }
    }
}

/**
 * 读原图做全屏预览。磁盘 IO 与解码都在 IO 线程，主线程只等结果。
 *
 * 先 `inJustDecodeBounds` 拿尺寸，再由 [previewSampleSize] 决定 `inSampleSize`：
 * 手机截图通常是 `1`（原分辨率），只有超过 [MaxPreviewDecodedPixels] 的超长截图才降一档。
 * 路径为空、文件被清理、解码返回 null、解码抛异常（含 OOM）都返回 [CapturePreviewImage.Unavailable]。
 */
internal suspend fun loadCapturePreview(path: String): CapturePreviewImage =
    withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return@withContext CapturePreviewImage.Unavailable
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = previewSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull() ?: return@withContext CapturePreviewImage.Unavailable
        CapturePreviewImage.Loaded(decoded.asImageBitmap())
    }

/**
 * 预览解码的降采样倍数：只降到「不超内存预算、不超纹理上限」为止，不做无脑 1/4。
 *
 * 1080×2400、1440×3200 这类手机截图都返回 1（原分辨率）——这正是修复前看不清的原因；
 * 只有像素数超过 [maxDecodedPixels]、或单边超过 [maxDecodedDimension] 的图才取 2、4…
 * （解码器只接受 2 的幂）。尺寸非法时返回 1，让解码器自己失败，走降级分支。
 */
internal fun previewSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxDecodedPixels: Long = MaxPreviewDecodedPixels,
    maxDecodedDimension: Int = MaxPreviewDecodedDimension,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    var sample = 1
    while (sample < 64) {
        val pixels = decodedPreviewPixels(sourceWidth, sourceHeight, sample)
        val longestSide = maxOf(
            (sourceWidth + sample - 1) / sample,
            (sourceHeight + sample - 1) / sample,
        )
        if (pixels <= maxDecodedPixels && longestSide <= maxDecodedDimension) break
        sample *= 2
    }
    return sample
}

/**
 * 按采样倍数算解码后的像素数；用于预算校验与单测，不参与绘制。
 *
 * 用向上取整（`(w + s - 1) / s`），与 `BitmapFactory` 的实际行为一致；用整除会低估像素，
 * 让预算检查看起来比实际宽松。
 */
internal fun decodedPreviewPixels(sourceWidth: Int, sourceHeight: Int, sample: Int): Long {
    val safeSample = sample.coerceAtLeast(1)
    val width = (sourceWidth + safeSample - 1) / safeSample
    val height = (sourceHeight + safeSample - 1) / safeSample
    return width.toLong() * height
}

/**
 * 放大后单轴允许的最大平移像素（相对中心）。
 *
 * 内容放大后仍不足一屏时返回 0——不许把图拖出可视区；1x 时也返回 0，这样任何倍率都能
 * 回到整图且不留偏移。
 */
internal fun previewPanLimitPx(contentPx: Float, scale: Float, containerPx: Float): Float {
    if (contentPx <= 0f || containerPx <= 0f || scale <= MinPreviewScale) return 0f
    val scaled = contentPx * scale
    return if (scaled <= containerPx) 0f else (scaled - containerPx) / 2f
}

/**
 * 缩略图预览的开关状态：记住“当前放大的是哪张图”。
 *
 * 位图不进这个状态——预览自己去读 [openPath] 指向的原图。这里只留能纯函数验证的部分：
 * 什么时候允许点、点开记的是哪张原图、关掉有没有回到 null。后两条错了会让用户看到上一张
 * 图的残留，或者关不掉。
 */
internal data class CaptureImagePreviewState(
    val openPath: String? = null,
) {
    val open: Boolean get() = openPath != null

    /**
     * 缩略图被点：预览目标永远是「当前这张候选自己的原图路径」。
     *
     * 路径缺失/空白、或缩略图没解出来时把 openPath 置回 null，而不是保留上一张——
     * 宁可什么都不显示，也不能把旧图当新图弹给用户。
     */
    fun onThumbnailTapped(
        screenshotPath: String?,
        hasThumbnail: Boolean,
    ): CaptureImagePreviewState =
        copy(openPath = previewPathForCandidate(screenshotPath, hasThumbnail))

    /** 点背景、X、系统返回关闭后回到 null，下一张图不会带着上一张的残留。 */
    fun onDismissed(): CaptureImagePreviewState = copy(openPath = null)
}

/** 候选交给预览的原图路径：只有非空白路径才算数。 */
internal fun previewPathForCandidate(screenshotPath: String?, hasThumbnail: Boolean): String? =
    screenshotPath?.takeIf { hasThumbnail && it.isNotBlank() }

/**
 * 缩略图能不能点开大图。
 *
 * 既要缩略图已经解码出来，也要原图路径还在：只有路径没有解码结果时卡片显示的是分类
 * 图标，没有可点的图片；有解码结果但路径为空说明文件已被清理，点开也没图。
 */
internal fun canPreviewCaptureImage(screenshotPath: String?, hasThumbnail: Boolean): Boolean =
    hasThumbnail && !screenshotPath.isNullOrBlank()
