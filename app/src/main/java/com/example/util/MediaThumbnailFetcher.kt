package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

object MediaThumbnailFetcher {
    private const val TAG = "MediaThumbnailFetcher"
    private const val THUMBNAIL_DIR = "thumbnails"
    private const val MAX_CONCURRENT_EXTRACTIONS = 2
    private const val TARGET_THUMBNAIL_SIZE = 512
    private const val BLACK_FRAME_LUMINANCE_THRESHOLD = 15.0
    private const val BLACK_FRAME_PERCENTAGE_THRESHOLD = 0.98f

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Bounded concurrency for expensive extraction
    private val extractionSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    /**
     * Authoritative entry point for thumbnail acquisition.
     * Established Decoder Priority:
     * 1. ContentResolver.loadThumbnail (API 29+)
     * 2. MediaMetadataRetriever (with black-frame retry)
     * 3. Coil VideoFrameDecoder (via UI layer fallback)
     */
    suspend fun getThumbnail(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null

        // 1. Preferred Path: loadThumbnail (API 29+) for system-cached thumbnails
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriString.startsWith("content://")) {
            val cacheKey = generateCacheKey(uriString, -1L, TARGET_THUMBNAIL_SIZE)
            val cacheFile = getCacheFile(context, cacheKey)
            
            if (cacheFile.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (isValidBitmap(bitmap) && !isBlackFrame(bitmap!!)) return@withContext bitmap
                } catch (_: Exception) {}
            }

            try {
                val uri = Uri.parse(uriString)
                val bitmap = context.contentResolver.loadThumbnail(uri, Size(TARGET_THUMBNAIL_SIZE, TARGET_THUMBNAIL_SIZE), null)
                if (isValidBitmap(bitmap) && !isBlackFrame(bitmap)) {
                    saveToDiskCache(cacheFile, bitmap)
                    return@withContext bitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadThumbnail failed for $uriString, falling back to extractor: ${e.message}")
            }
        }

        // 2. Secondary Path: MediaMetadataRetriever with retries for bad representative frames
        // Start at 2s instead of 1s to avoid common fade-ins from black
        return@withContext getFrameAtTime(context, uriString, 2_000_000L)
    }

    /**
     * Extracts a frame at a specific timestamp (in microseconds).
     * Uses cache if available (key includes timestamp).
     */
    suspend fun getFrameAtTime(context: Context, uriString: String, timeUs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        
        val cacheKey = generateCacheKey(uriString, timeUs, TARGET_THUMBNAIL_SIZE)
        val cacheFile = getCacheFile(context, cacheKey)

        // Disk Cache lookup
        if (cacheFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (isValidBitmap(bitmap)) return@withContext bitmap
            } catch (_: Exception) {}
        }

        // 2. Extraction with bounded concurrency
        ensureActive()
        
        return@withContext try {
            // AURA REPAIR: Check isActive BEFORE acquiring semaphore to drop obsolete scrolling requests immediately
            if (!isActive) return@withContext null

            extractionSemaphore.withPermit {
                ensureActive()
                var bitmap = extractFrame(context, uriString, timeUs)
                
                // Black frame recovery logic (Retriever path only)
                if (isValidBitmap(bitmap) && isBlackFrame(bitmap!!)) {
                    val durationMs = getDuration(context, uriString)
                    if (durationMs > 0) {
                        Log.d(TAG, "Detected black frame at ${timeUs/1000000}s for $uriString. Retrying at midpoint...")
                        // Attempt 2: Midpoint (clamped to 10s to avoid deep seeks in very long videos)
                        val retry1Us = min(durationMs * 500L, 10_000_000L)
                        if (retry1Us > timeUs) {
                            val retryBitmap = extractFrame(context, uriString, retry1Us)
                            if (isValidBitmap(retryBitmap)) {
                                if (isBlackFrame(retryBitmap!!)) {
                                    Log.d(TAG, "Midpoint frame also black for $uriString. Final retry at 20%...")
                                    // Attempt 3: 20% point as an alternative to midpoint/start
                                    val retry2Us = durationMs * 200L
                                    if (retry2Us > 0 && retry2Us != retry1Us && retry2Us != timeUs) {
                                        val finalBitmap = extractFrame(context, uriString, retry2Us)
                                        if (isValidBitmap(finalBitmap)) {
                                            bitmap = finalBitmap
                                        }
                                    }
                                } else {
                                    bitmap = retryBitmap
                                }
                            }
                        }
                    }
                }

                if (isValidBitmap(bitmap)) {
                    saveToDiskCache(cacheFile, bitmap!!)
                }
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed for $uriString: ${e.message}")
            null
        }
    }

    private fun extractFrame(context: Context, uriString: String, timeUs: Long): Bitmap? {
        val uri = Uri.parse(uriString)
        val mimeType = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        
        // Optimized path for photos
        if (MediaCompatibility.isSupportedPhoto(mimeType, uriString)) {
            return try {
                context.contentResolver.openInputStream(uri)?.use { 
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photo decoding failed for $uriString: ${e.message}")
                null
            }
        }

        // Standard path for videos using MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        return try {
            if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(uriString)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs, 
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 
                    TARGET_THUMBNAIL_SIZE, 
                    TARGET_THUMBNAIL_SIZE
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.frameAtTime
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for $uriString: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun isBlackFrame(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 24 // Fast sampling for efficiency
        var darkPixels = 0
        var totalSamples = 0
        
        for (x in 0 until width step sampleStep) {
            for (y in 0 until height step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Standard Luminance Formula
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                if (luminance < BLACK_FRAME_LUMINANCE_THRESHOLD) {
                    darkPixels++
                }
                totalSamples++
            }
        }
        
        return if (totalSamples > 0) {
            (darkPixels.toFloat() / totalSamples) > BLACK_FRAME_PERCENTAGE_THRESHOLD
        } else false
    }

    private fun getDuration(context: Context, uriString: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
                retriever.setDataSource(context, Uri.parse(uriString))
            } else {
                retriever.setDataSource(uriString)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun isValidBitmap(bitmap: Bitmap?): Boolean {
        return bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
    }

    private fun saveToDiskCache(file: File, bitmap: Bitmap) {
        val tempFile = File(file.parent, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save thumbnail to disk: ${e.message}")
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun getCacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, THUMBNAIL_DIR)
        return File(dir, "$key.jpg")
    }

    private fun generateCacheKey(uriString: String, timeUs: Long, size: Int): String {
        val input = "${uriString}_${timeUs}_${size}"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    fun removeThumbnail(uriString: String) {
        memoryCache.remove(uriString)
    }
    
    fun clearDiskCache(context: Context) {
        try {
            val dir = File(context.cacheDir, THUMBNAIL_DIR)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear disk cache: ${e.message}")
        }
    }
}

object MediaCompatibility {
    fun isSupportedVideo(mimeType: String?, uriString: String): Boolean {
        if (mimeType != null && mimeType.startsWith("video/")) return true
        val lower = uriString.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".3gp") || lower.contains("video") || lower.startsWith("content://media/external/video")
    }

    fun isSupportedPhoto(mimeType: String?, uriString: String): Boolean {
        if (mimeType != null && mimeType.startsWith("image/")) return true
        val lower = uriString.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".heic") || lower.contains("image") ||
                lower.startsWith("content://media/external/images")
    }
}
