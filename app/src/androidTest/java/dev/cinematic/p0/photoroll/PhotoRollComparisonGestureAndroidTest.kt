package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import org.junit.Assert.*
import org.junit.Test

class PhotoRollComparisonGestureAndroidTest {
    @Test fun longPressReleaseCancelBackgroundAndNewPairRestoreTheDevelopedImage() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun image(color: Int) = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val blue = image(Color.BLUE)
        val red = image(Color.RED)
        val green = image(Color.GREEN)
        val after = mutableStateOf(blue)
        val foreground = mutableStateOf(true)
        var x = 0f
        var y = 0f
        var downTime = 0L
        var window: Window? = null
        // Draw the view tree into a software bitmap. ATD images disable hardware rendering, so display
        // screenshots and PixelCopy have no frames to read; this checks the composed UI, not the display.
        fun capture(): Bitmap {
            val decor = requireNotNull(window).decorView
            var bitmap: Bitmap? = null
            instrumentation.runOnMainSync {
                val drawn = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try { decor.draw(Canvas(drawn)) } catch (t: Throwable) { drawn.recycle(); throw t }
                bitmap = drawn
            }
            return requireNotNull(bitmap)
        }
        fun touch(action: Int) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            MotionEvent.obtain(downTime, now, action, x, y, 0).also {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
        }
        fun awaitColor(expected: Int) {
            val limit = SystemClock.uptimeMillis() + 3_000
            var actual = 0
            do {
                instrumentation.waitForIdleSync()
                val screenshot = capture()
                try { actual = screenshot.getPixel(x.toInt(), y.toInt()) } finally { screenshot.recycle() }
                if (actual == expected) return
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < limit)
            assertEquals(expected, actual)
        }
        fun imageBounds(color: Int): Rect {
            val screenshot = capture()
            try {
                val pixels = IntArray(screenshot.width * screenshot.height)
                screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
                var left = screenshot.width; var top = screenshot.height; var right = -1; var bottom = -1
                for (py in 0 until screenshot.height) for (px in 0 until screenshot.width) if (pixels[py * screenshot.width + px] == color) {
                    left = minOf(left, px); top = minOf(top, py); right = maxOf(right, px); bottom = maxOf(bottom, py)
                }
                check(right >= left && bottom >= top)
                return Rect(left, top, right + 1, bottom + 1)
            } finally { screenshot.recycle() }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { CaptureTheme {
                    PhotoRollComparisonImage(after.value, red, foreground.value, "現像結果",
                        "RAWの基準表示（撮影時WB・暫定色、露出補正・ルックなし）。同じ向きと画角で比較します。", Modifier.fillMaxSize())
                } }
                x = activity.window.decorView.width / 2f
                y = activity.window.decorView.height / 2f
            }
            instrumentation.waitForIdleSync()
            // onActivity may precede first layout; screen centre is still inside the common image frame.
            scenario.onActivity { activity ->
                x = activity.window.decorView.width / 2f
                y = activity.window.decorView.height / 2f
                window = activity.window
            }
            awaitColor(Color.BLUE)
            val frame = imageBounds(Color.BLUE)
            fun hold() { touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100); awaitColor(Color.RED) }
            hold(); assertEquals("Long caption must not move/resize a portrait image", frame, imageBounds(Color.RED))
            touch(MotionEvent.ACTION_UP); awaitColor(Color.BLUE)
            hold(); touch(MotionEvent.ACTION_CANCEL); awaitColor(Color.BLUE)
            hold(); scenario.onActivity { foreground.value = false }; awaitColor(Color.BLUE)
            touch(MotionEvent.ACTION_CANCEL)
            scenario.onActivity { foreground.value = true }
            hold(); scenario.onActivity { after.value = green }; awaitColor(Color.GREEN)
            touch(MotionEvent.ACTION_CANCEL)
        }
        // Test-owned source bitmaps only; the Activity/composition has been closed.
        listOf(blue, red, green).forEach { it.recycle() }
    }
}
