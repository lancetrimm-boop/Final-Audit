package com.example.data.semantic

import com.example.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Service responsible for the end-to-end semantic indexing pipeline.
 *
 * Coordinates:
 * 1. MediaItem -> Semantic Document synthesis
 * 2. Text -> Neural Embedding generation
 * 3. Persistence to Repository
 * 4. Update to in-memory Vector Index
 */
interface SemanticIndexingService {
    /**
     * Generates and persists a semantic representation for the given MediaItem.
     * Updates the in-memory index upon success.
     * 
     * Optimizes by reusing existing valid embeddings if metadata and model have not changed.
     */
    suspend fun indexMediaItem(item: MediaItem): EmbeddingResult

    /**
     * Efficiently indexes a batch of media items.
     */
    suspend fun indexBatch(items: List<MediaItem>)
}

/**
 * Production implementation of [SemanticIndexingService].
 */
class DefaultSemanticIndexingService(
    private val embeddingProvider: EmbeddingProvider,
    private val candidateRetriever: SemanticCandidateRetriever,
    private val repository: SemanticRepresentationRepository
) : SemanticIndexingService {

    override suspend fun indexMediaItem(item: MediaItem): EmbeddingResult = withContext(Dispatchers.Default) {
        try {
            // 1. Synthesize structured document from available metadata
            val document = SemanticDocumentBuilder.buildDocument(item)
            val currentDocVersion = SemanticDocumentBuilder.DOCUMENT_VERSION
            
            // 2. Generate content hash for change detection
            val sourceHash = computeSha256(document)

            // 3. Optimization: Check for existing valid representation
            val descriptor = embeddingProvider.descriptor
            val existing = repository.getSpecificRepresentation(
                mediaId = item.id,
                type = descriptor.primaryType,
                descriptor = descriptor
            )

            if (existing != null && 
                existing.documentVersion == currentDocVersion && 
                existing.sourceDataHash == sourceHash) {
                
                android.util.Log.d("SemanticIndexing", "Reusing existing valid embedding for media: ${item.id}")
                candidateRetriever.onRepresentationAdded(existing)
                return@withContext EmbeddingResult.Success(existing)
            }

            // 4. Generate new neural embedding
            val result = embeddingProvider.generateEmbedding(
                mediaId = item.id,
                input = SemanticInput.Text(document),
                sourceDataHash = sourceHash
            )

            // 5. On success, persist and notify retriever for index update
            if (result is EmbeddingResult.Success) {
                val representation = result.representation.copy(documentVersion = currentDocVersion)
                repository.saveRepresentation(representation)
                candidateRetriever.onRepresentationAdded(representation)
            }

            result
        } catch (e: Exception) {
            EmbeddingResult.Failure(
                errorCode = EmbeddingErrorCode.INFERENCE_ERROR,
                message = "Indexing failed for media ${item.id}: ${e.message}",
                cause = e
            )
        }
    }

    override suspend fun indexBatch(items: List<MediaItem>) {
        items.forEach { item ->
            indexMediaItem(item)
        }
    }

    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
