package com.example.data.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.MediaRepository
import com.example.data.semantic.EmbeddingProvider
import com.example.data.semantic.EmbeddingResult
import com.example.data.semantic.SemanticInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * VisualContextEngine (V1)
 *
 * Captures and asynchronously analyzes visual characteristics of video frames
 * surrounding the moment of a user Like event. Maps extracted features
 * (brightness, warmth, saturation, contrast, texture) into existing Taste DNA dimensions.
 */
class VisualContextEngine(
    private val repository: MediaRepository,
    private val visualProvider: EmbeddingProvider? = null,
    private val semanticRepo: com.example.data.semantic.SemanticRepresentationRepository? = null,
    private val candidateRetriever: com.example.data.semantic.SemanticCandidateRetriever? = null
) {
    companion object {
        private const val TAG = "VisualContextEngine"
        private const val TARGET_FRAME_SIZE = 256
        private const val DEDUPLICATION_WINDOW_MS = 5000L
        private const val DEDUPLICATION_BUCKET_MS = 2000L
    }

    private val extractionSemaphore = Semaphore(2)
    private val recentJobs = ConcurrentHashMap<String, Long>()

    data class VisualFeatures(
        val brightness: Double,
        val warmth: Double,
        val saturation: Double,
        val contrast: Double,
        val texture: Double
    )

    private fun isDuplicate(mediaId: String, playbackPositionMs: Long): Boolean {
        val bucket = playbackPositionMs / DEDUPLICATION_BUCKET_MS
        val dedupeKey = "${mediaId}_$bucket"
        val now = System.currentTimeMillis()
        recentJobs.entries.removeIf { now - it.value > 60_000L }
        val lastRun = recentJobs[dedupeKey]
        if (lastRun != null && (now - lastRun) < DEDUPLICATION_WINDOW_MS) {
            return true
        }
        recentJobs[dedupeKey] = now
        return false
    }

    suspend fun processLikeContext(
        mediaId: String,
        uri: String,
        playbackPositionMs: Long,
        durationMs: Long,
        context: Context?
    ) = withContext(Dispatchers.IO) {
        if (isDuplicate(mediaId, playbackPositionMs)) return@withContext
        try {
            val bitmaps = extractSampledFrames(mediaId, uri, playbackPositionMs, durationMs, context)
            if (bitmaps.isEmpty()) return@withContext

            val visualEmbeddings = mutableListOf<FloatArray>()
            val frameMetrics = mutableListOf<VisualFeatures>()
            for (bitmap in bitmaps) {
                visualProvider?.let { provider ->
                    if (provider.isReady()) {
                        val result = provider.generateEmbedding(
                            mediaId = mediaId,
                            input = SemanticInput.ExplicitBitmap(bitmap),
                            sourceDataHash = "frame_temp"
                        )
                        if (result is EmbeddingResult.Success) {
                            visualEmbeddings.add(result.representation.vector)
                        }
                    }
                }
                calculatePixelMetrics(bitmap)?.let { frameMetrics.add(it) }
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            if (visualEmbeddings.isNotEmpty() && semanticRepo != null && visualProvider != null) {
                val aggregatedVector = com.example.data.semantic.VectorMath.mean(visualEmbeddings)
                val normalizedVector = com.example.data.semantic.VectorMath.l2Normalize(aggregatedVector)
                val mediaItem = repository.mediaItemsMap.value[mediaId]
                val contentHash = mediaItem?.contentHash ?: "v1_visual_${System.currentTimeMillis()}"
                val descriptor = visualProvider.descriptor
                val representationId = "sem_${mediaId}_visual_${descriptor.modelId}_v${descriptor.modelVersion}"
                
                val representation = com.example.data.semantic.SemanticRepresentation(
                    id = representationId,
                    mediaId = mediaId,
                    type = com.example.data.semantic.SemanticRepresentationType.VISUAL,
                    modelDescriptor = descriptor,
                    dimensionality = descriptor.dimensionality,
                    vector = normalizedVector,
                    sourceDataHash = contentHash,
                    confidence = 1.0f
                )
                semanticRepo.saveRepresentation(representation)
                candidateRetriever?.onRepresentationAdded(representation)
            }

            if (frameMetrics.isNotEmpty()) {
                val aggregated = aggregateMetrics(frameMetrics)
                applyTasteDnaAdjustment(aggregated, playbackPositionMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Visual context analysis failed", e)
        }
    }

    internal suspend fun extractSampledFrames(
        mediaId: String,
        uri: String,
        playbackPositionMs: Long,
        durationMs: Long,
        context: Context?
    ): List<Bitmap> = extractionSemaphore.withPermit {
        if (context == null) return@withPermit emptyList()
        val uriObj = Uri.parse(uri)
        val mimeType = context.contentResolver.getType(uriObj)

        if (com.example.util.MediaCompatibility.isSupportedPhoto(mimeType, uri)) {
            return@withPermit try {
                context.contentResolver.openInputStream(uriObj)?.use { 
                    BitmapFactory.decodeStream(it)?.let { listOf(it) } ?: emptyList()
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val sampleTimesMs = listOf(
            (playbackPositionMs - 1000L).coerceAtLeast(0L),
            playbackPositionMs,
            if (durationMs > 0) (playbackPositionMs + 1000L).coerceAtMost(durationMs) else playbackPositionMs + 1000L
        ).distinct()
        
        val bitmaps = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uriObj)
            for (timeMs in sampleTimesMs) {
                val timeUs = timeMs * 1000L
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_FRAME_SIZE, TARGET_FRAME_SIZE)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        Bitmap.createScaledBitmap(it, TARGET_FRAME_SIZE, TARGET_FRAME_SIZE, true)
                    }
                }
                if (frame != null) bitmaps.add(frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Retriever failed for $mediaId")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        bitmaps
    }

    private fun calculatePixelMetrics(bitmap: Bitmap): VisualFeatures? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var lumSum = 0.0
        var warmSum = 0.0
        var satSum = 0.0
        val lums = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
            lums[i] = l
            lumSum += l
            val rgbSum = r + g + b
            warmSum += if (rgbSum > 0) (r - b) / (rgbSum + 0.001) else 0.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            satSum += if (max > 0) (max - min) / max else 0.0
        }
        val meanL = lumSum / pixels.size
        val meanW = ((warmSum / pixels.size) + 1.0) / 2.0
        val meanS = satSum / pixels.size
        var varSum = 0.0
        for (l in lums) varSum += (l - meanL) * (l - meanL)
        val contrast = (sqrt(varSum / pixels.size) * 2.0).coerceIn(0.0, 1.0)
        return VisualFeatures(meanL.coerceIn(0.0, 1.0), meanW.coerceIn(0.0, 1.0), meanS.coerceIn(0.0, 1.0), contrast, 0.5)
    }

    private fun aggregateMetrics(list: List<VisualFeatures>) = VisualFeatures(
        list.map { it.brightness }.average().coerceIn(0.0, 1.0),
        list.map { it.warmth }.average().coerceIn(0.0, 1.0),
        list.map { it.saturation }.average().coerceIn(0.0, 1.0),
        list.map { it.contrast }.average().coerceIn(0.0, 1.0),
        list.map { it.texture }.average().coerceIn(0.0, 1.0)
    )

    private fun applyTasteDnaAdjustment(f: VisualFeatures, pos: Long) {
        val dna = repository.tasteDNA.value
        if (!dna.isFineTuningEnabled) return
        val step = MediaRepository.MAX_ADJUSTMENT_PER_VOTE
        val limit = MediaRepository.TOTAL_ADJUSTMENT_LIMIT
        fun adj(v: Double) = (v - 0.5) * 2.0 * step
        var next = dna.updateLearnedDimension("lighting", adj(f.brightness), limit)
        next = next.updateLearnedDimension("contrast", adj(f.contrast), limit)
        next = next.updateLearnedDimension("warmth", adj(f.warmth), limit)
        next = next.updateLearnedDimension("saturation", adj(f.saturation), limit)
        if (next != dna) repository.updateTasteDNA(next, false, "Visual Context ($pos)")
    }

    suspend fun enrichMedia(id: String, uri: String, dur: Long, ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val prov = visualProvider ?: return@withContext false
        val repo = semanticRepo ?: return@withContext false
        if (!prov.isReady()) return@withContext false
        val descriptor = prov.descriptor
        val mediaItem = repository.mediaItemsMap.value[id]
        val hash = mediaItem?.contentHash ?: "v1_enrich_$id"
        if (repo.getSpecificRepresentation(id, descriptor.primaryType, descriptor)?.sourceDataHash == hash) return@withContext true
        try {
            val bitmaps = extractSampledFrames(id, uri, dur / 10, dur, ctx)
            if (bitmaps.isEmpty()) return@withContext false
            val result = prov.generateEmbedding(id, SemanticInput.ExplicitBitmap(bitmaps[0]), hash)
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            if (result is EmbeddingResult.Success) {
                repo.saveRepresentation(result.representation)
                candidateRetriever?.onRepresentationAdded(result.representation)
                return@withContext true
            }
        } catch (_: Exception) {}
        false
    }
}
