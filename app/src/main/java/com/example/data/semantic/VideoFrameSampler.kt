package com.example.data.semantic

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Deterministic sampler for video frames to improve visual coverage.
 * Now evolved to support adaptive scene-aware sampling using visual change detection.
 */
object VideoFrameSampler {
    private const val TAG = "VideoFrameSampler"
    
    // --- STAGE 9 CONFIGURATION ---
    private const val ANALYSIS_SAMPLE_COUNT = 15
    private const val ANALYSIS_BITMAP_SIZE = 64
    private const val MAX_INFERENCE_SAMPLES = 8
    private const val MIN_FRAME_DISTANCE_MS = 3000L
    
    // Stage 8 Constants
    private const val MAX_SAMPLES_STAGE_8 = 5
    private const val MIN_MULTI_FRAME_DURATION = 2000L

    /**
     * Generates a list of timestamps (in microseconds) to sample from a video.
     * Uses adaptive sampling if context and URI are provided, otherwise falls back to deterministic.
     */
    suspend fun getSampleTimestamps(
        durationMs: Long,
        context: Context? = null,
        uriString: String? = null
    ): List<Long> = withContext(Dispatchers.IO) {
        if (context == null || uriString == null || durationMs <= MIN_MULTI_FRAME_DURATION) {
            return@withContext getDeterministicTimestamps(durationMs)
        }

        try {
            getAdaptiveTimestamps(context, uriString, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Adaptive sampling failed for $uriString, falling back to deterministic (DETERMINISTIC_FALLBACK)", e)
            getDeterministicTimestamps(durationMs)
        }
    }

    /**
     * Stage 8 baseline strategy: Evenly spaced temporal points.
     */
    private fun getDeterministicTimestamps(durationMs: Long): List<Long> {
        if (durationMs <= 0) return listOf(1_000_000L) // Default to 1s
        
        if (durationMs < MIN_MULTI_FRAME_DURATION) {
            return listOf((durationMs / 2) * 1000L)
        }

        val samples = mutableListOf<Long>()
        val margin = (durationMs * 0.05).toLong()
        val effectiveDuration = durationMs - (2 * margin)
        
        val count = MAX_SAMPLES_STAGE_8.coerceAtMost((durationMs / 1000).toInt().coerceAtLeast(1))
        
        if (count <= 1) {
            samples.add((durationMs / 2) * 1000L)
        } else {
            val interval = effectiveDuration / (count - 1)
            for (i in 0 until count) {
                samples.add((margin + (i * interval)) * 1000L)
            }
        }
        return samples
    }

    /**
     * Stage 9 adaptive strategy: Scan video for visual changes and select key frames.
     */
    private fun getAdaptiveTimestamps(context: Context, uriString: String, durationMs: Long): List<Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            
            // 1. Generate analysis timestamps (15 points)
            val analysisPoints = generateAnalysisPoints(durationMs)
            val changes = mutableListOf<Pair<Long, Float>>()
            
            var prevBitmap: Bitmap? = null
            
            for (timeUs in analysisPoints) {
                val currentBitmap = extractAnalysisFrame(retriever, timeUs)
                if (currentBitmap != null) {
                    if (prevBitmap != null) {
                        val score = VisualChangeDetector.calculateChangeScore(prevBitmap, currentBitmap)
                        changes.add(timeUs to score)
                        prevBitmap.recycle()
                    } else {
                        // First frame, use as baseline for next
                        changes.add(timeUs to 0f)
                    }
                    prevBitmap = currentBitmap
                }
            }
            prevBitmap?.recycle()

            if (changes.isEmpty()) return getDeterministicTimestamps(durationMs)

            // 2. Key Frame Selection
            val selected = mutableListOf<Long>()
            
            // Always include midpoint for temporal stability
            val midpointUs = (durationMs / 2) * 1000L
            selected.add(midpointUs)

            // Rank remaining by change score (highest change = most interesting)
            val candidates = changes.sortedByDescending { it.second }
            
            for (cand in candidates) {
                if (selected.size >= MAX_INFERENCE_SAMPLES) break
                
                val candUs = cand.first
                val minDistanceUs = MIN_FRAME_DISTANCE_MS * 1000L
                
                val isFarEnough = selected.none { abs(it - candUs) < minDistanceUs }
                if (isFarEnough) {
                    selected.add(candUs)
                }
            }

            selected.sorted()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun generateAnalysisPoints(durationMs: Long): List<Long> {
        val points = mutableListOf<Long>()
        val margin = (durationMs * 0.05).toLong()
        val effectiveDuration = durationMs - (2 * margin)
        
        val interval = effectiveDuration / (ANALYSIS_SAMPLE_COUNT - 1)
        for (i in 0 until ANALYSIS_SAMPLE_COUNT) {
            points.add((margin + (i * interval)) * 1000L)
        }
        return points
    }

    private fun extractAnalysisFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ANALYSIS_BITMAP_SIZE,
                    ANALYSIS_BITMAP_SIZE
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    Bitmap.createScaledBitmap(it, ANALYSIS_BITMAP_SIZE, ANALYSIS_BITMAP_SIZE, true).also { scaled ->
                        if (scaled !== it) it.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Helper to get video duration using MediaMetadataRetriever.
     */
    fun getVideoDuration(context: Context, uriString: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get video duration for $uriString: ${e.message}")
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
