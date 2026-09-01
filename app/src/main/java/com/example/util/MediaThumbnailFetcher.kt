package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object MediaThumbnailFetcher {
    private const val TAG = "MediaThumbnailFetcher"
    private const val THUMBNAIL_DIR = "thumbnails"
    private const val MAX_CONCURRENT_EXTRACTIONS = 4
    private const val TARGET_THUMBNAIL_SIZE = 320

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Bounded concurrency for expensive extraction
    private val extractionSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    suspend fun getThumbnail(context: Context, uriString: String): Bitmap? = getFrameAtTime(context, uriString, 1_000_000L)

    /**
     * Extracts a frame at a specific timestamp (in microseconds).
     * Uses cache if available (key includes timestamp).
     */
    suspend fun getFrameAtTime(context: Context, uriString: String, timeUs: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext null
        
        val cacheKey = generateCacheKey("${uriString}_$timeUs")
        val cacheFile = getCacheFile(context, cacheKey)

        // 1. Disk Cache lookup
        if (cacheFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null) return@withContext bitmap
            } catch (_: Exception) {}
        }

        // 2. Extraction with bounded concurrency
        ensureActive()
        
        return@withContext try {
            extractionSemaphore.withPermit {
                ensureActive()
                val bitmap = extractFrame(context, uriString, timeUs)
                if (bitmap != null) {
                    saveToDiskCache(cacheFile, bitmap)
                }
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed for $uriString at $timeUs: ${e.message}")
            null
        }
    }

    private fun extractFrame(context: Context, uriString: String, timeUs: Long): Bitmap? {
        val uri = Uri.parse(uriString)
        val mimeType = context.contentResolver.getType(uri)
        
        // Use optimized path for photos
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

        // Standard path for videos/others
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
            Log.w(TAG, "MediaMetadataRetriever failed: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractThumbnail(context: Context, uriString: String): Bitmap? {
        return extractFrame(context, uriString, 1000000L)
    }

    private fun saveToDiskCache(file: File, bitmap: Bitmap) {
        val tempFile = File(file.parent, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            // Atomic rename to avoid partial files
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

    private fun generateCacheKey(uriString: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(uriString.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to hashcode string if digest fails
            uriString.hashCode().toString()
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
