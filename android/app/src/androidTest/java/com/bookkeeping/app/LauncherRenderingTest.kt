package com.bookkeeping.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator validation: resources must load as Android adaptive layers. */
@RunWith(AndroidJUnit4::class)
class LauncherRenderingTest {
    @Test
    fun adaptiveIconKeepsMarginsAndWhiteToPaleBlueBackground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Android API 26+ must expose an adaptive icon", icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable

        val fg = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        adaptive.foreground.setBounds(0, 0, 432, 432)
        adaptive.foreground.draw(Canvas(fg))

        assertEquals("Transparent overscan at top-left", 0, Color.alpha(fg.getPixel(0, 0)))
        assertEquals("Transparent overscan at bottom-right", 0, Color.alpha(fg.getPixel(431, 431)))
        assertEquals("Original source is inset 18dp horizontally", 0, Color.alpha(fg.getPixel(65, 216)))
        assertEquals(255, Color.alpha(fg.getPixel(216, 216)))

        val bg = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        adaptive.background.setBounds(0, 0, 432, 432)
        adaptive.background.draw(Canvas(bg))
        val topLeft = bg.getPixel(4, 4)
        val bottomRight = bg.getPixel(428, 428)
        assertTrue("Top-left must be white", Color.red(topLeft) >= 248)
        assertTrue("Bottom-right must be light blue", Color.blue(bottomRight) - Color.red(bottomRight) > 20)

        var count = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            val px = fg.getPixel(x, y)
            if (Color.alpha(px) > 230 && Color.blue(px) > Color.red(px) + 40 &&
                Color.blue(px) > Color.green(px) + 10 && Color.red(px) < 140
            ) {
                // Must fit within Android's central 72dp: no blue glass clipping.
                assertTrue("Blue panel x=$x outside mask", x in 72..359)
                assertTrue("Blue panel y=$y outside mask", y in 72..359)
                count++
            }
        }
        assertTrue("Glass pane artwork unexpectedly missing", count > 1000)
    }
}
