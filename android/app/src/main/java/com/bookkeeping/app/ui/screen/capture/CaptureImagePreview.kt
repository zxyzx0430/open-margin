package com.bookkeeping.app.ui.screen.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bookkeeping.app.i18n.tr

/** 全屏预览的遮罩：0xF0 不透明，底下待确认列表只剩一点影子。 */
private val PreviewScrim = Color(0xF0000000)
private const val MinPreviewScale = 1f
private const val MaxPreviewScale = 5f

/**
 * 待确认卡片缩略图点开后的全屏预览。
 *
 * 只收已经解码好的 [bitmap]：缩略图在列表里已经解过一遍，预览再按路径读一次既浪费
 * 一次解码，也会让大图在低端机上迟一拍才出来。缩放只做居中放大，不做平移——单张图
 * 平移会把图挪出屏幕，放大到能看清商户名就够了。
 */
@Composable
internal fun CaptureImagePreview(
    bitmap: ImageBitmap,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var scale by remember { mutableStateOf(MinPreviewScale) }
        // 关闭用 detectTapGestures 而不是整屏 clickable：全屏无标签按钮会让 TalkBack
        // 读出“未命名按钮”。X 是子节点，在 Main pass 先消费点击，父级收不到 down 就不会
        // 跟着再关一次——两个关闭入口不会互相打架。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PreviewScrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = tr("扫描截图"),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin.Center
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            // pan 不用：居中缩放随时能回到 1x，平移回不去。
                            scale = (scale * zoom).coerceIn(MinPreviewScale, MaxPreviewScale)
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
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

/**
 * 缩略图预览的开关状态：记住“当前放大的是哪张图”。
 *
 * 位图本身只能留在 Compose 状态里（JVM 单测造不出 ImageBitmap），这里只留能纯函数
 * 验证的部分：什么时候允许点、点开记的是哪张、关掉有没有回到 null。后两条错了会让
 * 用户点不开或者关不掉，比缩放参数更值得用测试钉住。
 */
internal data class CaptureImagePreviewState(
    val openPath: String? = null,
) {
    val open: Boolean get() = openPath != null

    /** 缩略图被点：能预览就记住这张图的路径，不能预览保持原状（别把上一张图弹出来）。 */
    fun onThumbnailTapped(
        screenshotPath: String?,
        hasThumbnail: Boolean,
    ): CaptureImagePreviewState =
        if (canPreviewCaptureImage(screenshotPath, hasThumbnail)) {
            copy(openPath = screenshotPath)
        } else {
            this
        }

    /** 点背景或右上角 X 关闭后回到 null，下一张图不会带着上一张的残留。 */
    fun onDismissed(): CaptureImagePreviewState = copy(openPath = null)
}

/**
 * 缩略图能不能点开大图。
 *
 * 既要缩略图已经解码出来，也要原图路径还在：只有路径没有解码结果时卡片显示的是分类
 * 图标，没有可点的图片；有解码结果但路径为空说明文件已被清理，点开也没图。
 */
internal fun canPreviewCaptureImage(screenshotPath: String?, hasThumbnail: Boolean): Boolean =
    hasThumbnail && !screenshotPath.isNullOrBlank()
