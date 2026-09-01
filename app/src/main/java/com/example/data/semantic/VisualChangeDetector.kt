package com.example.data.semantic

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Lightweight, deterministic utility for detecting visual changes between video frames.
 *
 * Uses pixel-based luminance delta on low-resolution thumbnails to provide a normalized
 * change score in the range [0.0, 1.0].
 */
object VisualChangeDetector {

    /**
     * Common resolution for comparison to ensure performance and noise reduction.
     */
    private const val ANALYSIS_SIZE = 64

    /**
     * Calculates a normalized visual change score between two bitmaps.
     *
     * @param bitmapA The reference frame.
     * @param bitmapB The target frame to compare.
     * @return A float score where 0.0 means identical and 1.0 means maximally different.
     */
    fun calculateChangeScore(bitmapA: Bitmap, bitmapB: Bitmap): Float {
        if (bitmapA.isRecycled || bitmapB.isRecycled) return 0.0f

        // 1. Scale to small analysis resolution
        val scaledA = prepareForAnalysis(bitmapA)
        val scaledB = prepareForAnalysis(bitmapB)

        // 2. Extract pixels
        val pixelsA = IntArray(ANALYSIS_SIZE * ANALYSIS_SIZE)
        val pixelsB = IntArray(ANALYSIS_SIZE * ANALYSIS_SIZE)
        
        try {
            scaledA.getPixels(pixelsA, 0, ANALYSIS_SIZE, 0, 0, ANALYSIS_SIZE, ANALYSIS_SIZE)
            scaledB.getPixels(pixelsB, 0, ANALYSIS_SIZE, 0, 0, ANALYSIS_SIZE, ANALYSIS_SIZE)
        } catch (e: Exception) {
            // Handle cases where getPixels might fail (e.g. invalid config or recycled during call)
            if (scaledA !== bitmapA) scaledA.recycle()
            if (scaledB !== bitmapB) scaledB.recycle()
            return 0.0f
        }

        // 3. Calculate Mean Absolute Luminance Difference
        var totalAbsDelta = 0.0
        for (i in pixelsA.indices) {
            val lumA = calculateLuminance(pixelsA[i])
            val lumB = calculateLuminance(pixelsB[i])
            totalAbsDelta += abs(lumA - lumB)
        }

        // Cleanup intermediate scaled bitmaps if they were created
        if (scaledA !== bitmapA) scaledA.recycle()
        if (scaledB !== bitmapB) scaledB.recycle()

        // 4. Normalize (MAE / 255.0)
        val mae = totalAbsDelta / (ANALYSIS_SIZE * ANALYSIS_SIZE)
        return (mae / 255.0).toFloat().coerceIn(0.0f, 1.0f)
    }

    /**
     * Returns a version of the bitmap suitable for pixel-level analysis.
     */
    private fun prepareForAnalysis(source: Bitmap): Bitmap {
        return if (source.width != ANALYSIS_SIZE || source.height != ANALYSIS_SIZE) {
            Bitmap.createScaledBitmap(source, ANALYSIS_SIZE, ANALYSIS_SIZE, true)
        } else {
            source
        }
    }

    /**
     * Calculates luminance from an ARGB_8888 pixel using Rec. 601 coefficients.
     * Output range: [0.0, 255.0]
     */
    private fun calculateLuminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
