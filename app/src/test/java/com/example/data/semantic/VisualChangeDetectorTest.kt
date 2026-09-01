package com.example.data.semantic

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualChangeDetectorTest {

    @Test
    fun testCalculateChangeScore_IdenticalFrames_ReturnsZero() {
        val bitmap1 = createSolidBitmap(Color.RED)
        val bitmap2 = createSolidBitmap(Color.RED)
        
        val score = VisualChangeDetector.calculateChangeScore(bitmap1, bitmap2)
        assertEquals(0.0f, score, 1e-6f)
    }

    @Test
    fun testCalculateChangeScore_CompletelyDifferentFrames() {
        val white = createSolidBitmap(Color.WHITE)
        val black = createSolidBitmap(Color.BLACK)
        
        val score = VisualChangeDetector.calculateChangeScore(white, black)
        // MAE should be 255 (Max difference). Normalize / 255 = 1.0
        assertEquals(1.0f, score, 1e-6f)
    }

    @Test
    fun testCalculateChangeScore_SmallChange() {
        val red = createSolidBitmap(Color.RED)
        val partiallyRed = createSolidBitmap(Color.RED)
        // Modify one pixel in our 64x64 analysis window
        // Note: prepareForAnalysis uses 64x64, so creating at 64x64 ensures direct pixel mapping
        partiallyRed.setPixel(0, 0, Color.BLACK)
        
        val score = VisualChangeDetector.calculateChangeScore(red, partiallyRed)
        assertTrue("Score should be greater than zero for visual difference", score > 0.0f)
        assertTrue("Score should be extremely small for single pixel change", score < 0.001f)
    }

    @Test
    fun testCalculateChangeScore_Determinism() {
        val b1 = createSolidBitmap(Color.BLUE)
        val b2 = createSolidBitmap(Color.GREEN)
        
        val res1 = VisualChangeDetector.calculateChangeScore(b1, b2)
        val res2 = VisualChangeDetector.calculateChangeScore(b1, b2)
        assertEquals(res1, res2, 0.0f)
    }

    @Test
    fun testCalculateChangeScore_DifferentInputSizes() {
        val b1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val b2 = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        
        // Both should be scaled to 64x64 and compared correctly
        val score = VisualChangeDetector.calculateChangeScore(b1, b2)
        assertEquals(1.0f, score, 1e-6f)
    }

    private fun createSolidBitmap(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }
}
