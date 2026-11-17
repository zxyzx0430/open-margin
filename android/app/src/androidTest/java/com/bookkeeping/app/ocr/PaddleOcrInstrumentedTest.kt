package com.bookkeeping.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleOcrInstrumentedTest {
    @Test
    fun bundledPaddleEngineLoadsAndRecognizesText() = runBlocking {
        // x86_64 模拟器的 SUPPORTED_ABIS 里也会列出 arm64-v8a（native bridge），
        // 但打包进来的 OpenCV 原生库在 x86_64 上初始化不了，会返回 opencv_init_failed。
        // 只在主 ABI 就是 arm64-v8a 的真机上跑，模拟器上跳过。
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(900, 420, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 76f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("支付成功 ¥8.40", 50f, 170f, paint)
        canvas.drawText("自来充", 50f, 300f, paint)
        val engine = PaddleOcrEngine(context)
        try {
            val result = withTimeout(60_000) { engine.recognize(bitmap) }
            assertNull(result.error)
            assertTrue(result.lines.isNotEmpty())
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }
}
