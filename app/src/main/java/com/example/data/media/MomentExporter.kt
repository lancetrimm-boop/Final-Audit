package com.example.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import com.example.data.entitlement.EntitlementRepository
import com.example.data.entitlement.ProFeature
import com.example.data.MediaItem as AuraMediaItem
import kotlinx.coroutines.*
import java.io.File

/**
 * Service for exporting Aura Moments as MP4 video files.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MomentExporter(
    private val context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val engine: MomentEncodingEngine? = null
) {
    private val TAG = "MomentExporter"
    private val PHOTO_DURATION_US = 4_000_000L
    private val VIDEO_MAX_DURATION_MS = 15_000L

    private val encodingEngine: MomentEncodingEngine by lazy {
        engine ?: Media3MomentEncodingEngine(context)
    }

    sealed class ExportState {
        object Idle : ExportState()
        data class Exporting(val progress: Int) : ExportState()
        data class Success(val file: File) : ExportState()
        data class Failed(val message: String) : ExportState()
        object NotEntitled : ExportState()
        object Cancelled : ExportState()
    }

    suspend fun exportMoment(
        items: List<AuraMediaItem>,
        onProgress: (Int) -> Unit = {}
    ): ExportState = withContext(Dispatchers.IO) {
        if (!entitlementRepository.isFeatureAvailable(ProFeature.MOMENTS_EXPORT)) {
            return@withContext ExportState.NotEntitled
        }

        if (items.isEmpty()) {
            return@withContext ExportState.Failed("Moment is empty")
        }

        val outputDir = File(context.cacheDir, "aura_moments")
        if (!outputDir.exists()) outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val outputFile = File(outputDir, "Moment_${System.currentTimeMillis()}.mp4")
        
        try {
            val editedMediaItems = items.map { auraItem ->
                val uri = Uri.parse(auraItem.uriPath.ifBlank { auraItem.imageUrl })
                val isPhoto = auraItem.mediaType == "PHOTO"
                
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(uri)
                
                if (isPhoto) {
                    // CRITICAL: Must set image mime type for ImageAssetLoader to be selected
                    mediaItemBuilder.setMimeType(MimeTypes.IMAGE_JPEG)
                }
                
                if (auraItem.mediaType == "VIDEO" && auraItem.durationMs > VIDEO_MAX_DURATION_MS) {
                    mediaItemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(VIDEO_MAX_DURATION_MS)
                            .build()
                    )
                }
                
                val editedBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
                    .setRemoveAudio(isPhoto)
                
                if (isPhoto) {
                    editedBuilder.setDurationUs(PHOTO_DURATION_US)
                }
                
                editedBuilder.build()
            }

            val sequence = EditedMediaItemSequence(editedMediaItems)
            val composition = Composition.Builder(listOf(sequence)).build()

            val result = encodingEngine.encode(composition, outputFile, onProgress)
            
            if (result.isSuccess) {
                ExportState.Success(outputFile)
            } else {
                outputFile.delete()
                ExportState.Failed(result.exceptionOrNull()?.message ?: "Encoding failed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            if (outputFile.exists()) outputFile.delete()
            ExportState.Failed(e.message ?: "Unexpected error")
        }
    }
}
