package com.example.data.semantic

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.data.MediaItem
import com.example.util.MediaThumbnailFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service responsible for generating and persisting visual embeddings for MediaItems.
 * 
 * Coordinates:
 * 1. Frame/Bitmap extraction from MediaItem
 * 2. Visual Embedding generation (MobileCLIP)
 * 3. Persistence to Repository
 * 4. Update to in-memory Vector Index
 */
interface VisualIndexingService {
    /**
     * Generates and persists a visual representation for the given MediaItem.
     * Updates the in-memory index upon success.
     */
    suspend fun indexVisual(context: Context, item: MediaItem): EmbeddingResult

    /**
     * Reconstructs the visual index from persisted representations.
     */
    suspend fun initializeIndex()
}

/**
 * Production implementation of [VisualIndexingService].
 */
class DefaultVisualIndexingService(
    private val visualProvider: EmbeddingProvider, // Expected to be MobileCLIP
    private val candidateRetriever: SemanticCandidateRetriever,
    private val repository: SemanticRepresentationRepository
) : VisualIndexingService {

    companion object {
        /**
         * Version for visual indexing strategy. 
         * Version 4: Adaptive scene-aware sampling for videos.
         */
        const val VISUAL_INDEX_VERSION = 4

        /**
         * Version for image visual indexing.
         * Images remain at version 3 as the adaptive sampling upgrade only affects videos.
         */
        const val IMAGE_INDEX_VERSION = 3
    }

    override suspend fun initializeIndex() {
        candidateRetriever.initializeIndex(
            type = SemanticRepresentationType.VISUAL,
            descriptor = visualProvider.descriptor
        )
    }

    override suspend fun indexVisual(context: Context, item: MediaItem): EmbeddingResult = withContext(Dispatchers.Default) {
        try {
            val descriptor = visualProvider.descriptor
            val isVideo = item.mediaType == "VIDEO" || item.mediaType == "Movie"
            val targetVersion = if (isVideo) VISUAL_INDEX_VERSION else IMAGE_INDEX_VERSION
            
            // 1. Content Hashing for Invalidation
            val sourceHash = item.contentHash ?: "v1_${item.id}_${item.sizeBytes}"

            Log.i("VisualIndexing", "START_INDEXING: mediaId=${item.id} type=${item.mediaType} version=$targetVersion")

            // 2. Optimization: Check for existing valid visual representation
            val existing = repository.getSpecificRepresentation(
                mediaId = item.id,
                type = SemanticRepresentationType.VISUAL,
                descriptor = descriptor
            )

            if (existing != null && 
                existing.sourceDataHash == sourceHash && 
                existing.documentVersion == targetVersion) {
                
                Log.i("VisualIndexing", "INDEX_REUSE: mediaId=${item.id}")
                candidateRetriever.onRepresentationAdded(existing)
                return@withContext EmbeddingResult.Success(existing)
            }

            // 3. Extraction & Embedding Generation
            val representation = if (isVideo) {
                indexVideoMultiFrame(context, item, sourceHash)
            } else {
                indexImageSingleFrame(context, item, sourceHash, targetVersion)
            }

            // 4. Persistence and Index Update
            if (representation != null) {
                repository.saveRepresentation(representation)
                candidateRetriever.onRepresentationAdded(representation)
                
                val strategy = if (isVideo) "ADAPTIVE_SCENE_AWARE" else "SINGLE_FRAME"
                Log.i("VisualIndexing", "INDEX_SUCCESS: mediaId=${item.id} dim=${representation.dimensionality} strategy=$strategy")
                EmbeddingResult.Success(representation)
            } else {
                Log.w("VisualIndexing", "INDEX_FAILURE_EXTRACTION: mediaId=${item.id}")
                EmbeddingResult.Failure(
                    EmbeddingErrorCode.INVALID_INPUT,
                    "Failed to extract visual content for media: ${item.id}"
                )
            }
        } catch (e: Exception) {
            Log.e("VisualIndexing", "INDEX_ERROR: mediaId=${item.id} error=${e.message}", e)
            EmbeddingResult.Failure(
                errorCode = EmbeddingErrorCode.INFERENCE_ERROR,
                message = "Visual indexing failed: ${e.message}",
                cause = e
            )
        }
    }

    private suspend fun indexImageSingleFrame(
        context: Context, 
        item: MediaItem, 
        sourceHash: String,
        version: Int
    ): SemanticRepresentation? {
        val bitmap = MediaThumbnailFetcher.getThumbnail(context, item.uriPath) ?: return null
        val result = visualProvider.generateEmbedding(
            mediaId = item.id,
            input = SemanticInput.ExplicitBitmap(bitmap),
            sourceDataHash = sourceHash
        )
        return (result as? EmbeddingResult.Success)?.representation?.copy(documentVersion = version)
    }

    private suspend fun indexVideoMultiFrame(context: Context, item: MediaItem, sourceHash: String): SemanticRepresentation? {
        val durationMs = if (item.durationMs > 0) item.durationMs else VideoFrameSampler.getVideoDuration(context, item.uriPath)
        val timestamps = VideoFrameSampler.getSampleTimestamps(durationMs, context, item.uriPath)
        
        val frames = mutableListOf<VideoFrameRepresentation>()
        
        for (timeUs in timestamps) {
            val bitmap = MediaThumbnailFetcher.getFrameAtTime(context, item.uriPath, timeUs) ?: continue
            val result = visualProvider.generateEmbedding(
                mediaId = item.id,
                input = SemanticInput.ExplicitBitmap(bitmap),
                sourceDataHash = sourceHash
            )
            if (result is EmbeddingResult.Success) {
                val rep = result.representation
                val frameId = "sem_frame_${item.id}_${timeUs}_${visualProvider.descriptor.modelId}_v${visualProvider.descriptor.modelVersion}"
                frames.add(VideoFrameRepresentation(
                    id = frameId,
                    mediaId = item.id,
                    timestampUs = timeUs,
                    modelDescriptor = visualProvider.descriptor,
                    documentVersion = VISUAL_INDEX_VERSION,
                    dimensionality = visualProvider.descriptor.dimensionality,
                    vector = rep.vector
                ))
            }
        }

        if (frames.isEmpty()) return null

        // 1. Persistence of individual frames (Stage 8 Phase 8.3)
        repository.deleteFramesForMedia(item.id)
        repository.saveFrames(frames)

        // 2. Aggregation: Mean Pooling for broad retrieval
        val frameVectors = frames.map { it.vector }
        val aggregatedVector = VectorMath.mean(frameVectors)
        val normalizedVector = VectorMath.l2Normalize(aggregatedVector)

        return SemanticRepresentation(
            id = "sem_${item.id}_visual_${visualProvider.descriptor.modelId}_v${visualProvider.descriptor.modelVersion}",
            mediaId = item.id,
            type = SemanticRepresentationType.VISUAL,
            modelDescriptor = visualProvider.descriptor,
            documentVersion = VISUAL_INDEX_VERSION,
            dimensionality = visualProvider.descriptor.dimensionality,
            vector = normalizedVector,
            sourceDataHash = sourceHash,
            confidence = 1.0f
        )
    }
}
