package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThumbnailRepairTest {

    @Test
    fun testIsBlackFrame_SolidBlack() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        assertTrue(isBlackFrameLogic(bitmap))
    }

    @Test
    fun testIsBlackFrame_DarkGray() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // RGB (20,20,20) has luminance ~20, which is > 15.0 threshold
        bitmap.eraseColor(Color.rgb(20, 20, 20)) 
        assertFalse(isBlackFrameLogic(bitmap))
    }

    @Test
    fun testIsBlackFrame_PartialDark() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        // Set 5% of pixels to white
        for (i in 0 until 5) {
            for (j in 0 until 100) {
                bitmap.setPixel(i, j, Color.WHITE)
            }
        }
        // darkPixels = 95%, which is <= 98% threshold
        assertFalse(isBlackFrameLogic(bitmap))
    }

    @Test
    fun testIsBlackFrame_NormalContent() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        assertFalse(isBlackFrameLogic(bitmap))
    }

    private fun isBlackFrameLogic(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 5 
        var darkPixels = 0
        var totalSamples = 0
        
        for (x in 0 until width step sampleStep) {
            for (y in 0 until height step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                if (luminance < 15.0) {
                    darkPixels++
                }
                totalSamples++
            }
        }
        return (darkPixels.toFloat() / totalSamples) > 0.98f
    }
}
