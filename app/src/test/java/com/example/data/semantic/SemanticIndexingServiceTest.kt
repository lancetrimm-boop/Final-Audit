package com.example.data.semantic

import com.example.data.CompatibilityStatus
import com.example.data.ConversionStatus
import com.example.data.EnrichmentStatus
import com.example.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SemanticIndexingServiceTest {

    private lateinit var service: SemanticIndexingService
    private val embeddingProvider: EmbeddingProvider = mock()
    private val candidateRetriever: SemanticCandidateRetriever = mock()
    private val repository: SemanticRepresentationRepository = mock()

    private val descriptor = EmbeddingModelDescriptor(
        modelId = "test", modelVersion = 1, dimensionality = 384, primaryType = SemanticRepresentationType.CONTENT
    )

    @Before
    fun setUp() {
        service = DefaultSemanticIndexingService(embeddingProvider, candidateRetriever, repository)
    }

    @Test
    fun testIndexMediaItem_Success_PersistsAndUpdatesIndex() {
        runBlocking {
            val item = createTestMediaItem("m1", "Sunrise")
            val representation = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.CONTENT,
                modelDescriptor = descriptor,
                documentVersion = 1,
                dimensionality = 384,
                vector = FloatArray(384) { 0.1f },
                sourceDataHash = "hash"
            )

            whenever(embeddingProvider.generateEmbedding(any(), any(), any()))
                .thenReturn(EmbeddingResult.Success(representation))

            val result = service.indexMediaItem(item)

            assertTrue(result is EmbeddingResult.Success)
            
            // Verify coordination
            verify(repository).saveRepresentation(representation)
            verify(candidateRetriever).onRepresentationAdded(representation)
        }
    }

    @Test
    fun testIndexMediaItem_Failure_DoesNotPersist() {
        runBlocking {
            val item = createTestMediaItem("m1", "Sunrise")
            
            whenever(embeddingProvider.generateEmbedding(any(), any(), any()))
                .thenReturn(EmbeddingResult.Failure(EmbeddingErrorCode.INFERENCE_ERROR, "failed"))

            val result = service.indexMediaItem(item)

            assertTrue(result is EmbeddingResult.Failure)
            
            verify(repository, never()).saveRepresentation(any())
            verify(candidateRetriever, never()).onRepresentationAdded(any())
        }
    }

    @Test
    fun testIndexMediaItem_ReuseExisting_SkipsInference() {
        runBlocking {
            val item = createTestMediaItem("m1", "Sunrise")
            val document = SemanticDocumentBuilder.buildDocument(item)
            val hash = computeSha256(document)
            
            val existing = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.CONTENT,
                modelDescriptor = descriptor,
                documentVersion = SemanticDocumentBuilder.DOCUMENT_VERSION,
                dimensionality = 384,
                vector = FloatArray(384) { 0.1f },
                sourceDataHash = hash
            )

            whenever(repository.getSpecificRepresentation(eq("m1"), eq(SemanticRepresentationType.CONTENT), eq(descriptor)))
                .thenReturn(existing)

            val result = service.indexMediaItem(item)

            assertTrue(result is EmbeddingResult.Success)
            // Inference should NOT be called
            verify(embeddingProvider, never()).generateEmbedding(any(), any(), any())
            // Index should still be updated (hydrate in-memory index)
            verify(candidateRetriever).onRepresentationAdded(existing)
        }
    }

    @Test
    fun testIndexMediaItem_StaleVersion_GeneratesNew() {
        runBlocking {
            val item = createTestMediaItem("m1", "Sunrise")
            val oldVersion = SemanticDocumentBuilder.DOCUMENT_VERSION - 1
            
            val stale = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.CONTENT,
                modelDescriptor = descriptor,
                documentVersion = oldVersion,
                dimensionality = 384,
                vector = FloatArray(384) { 0.1f },
                sourceDataHash = "old_hash"
            )

            whenever(repository.getSpecificRepresentation(any(), any(), any()))
                .thenReturn(stale)
                
            val newRep = stale.copy(documentVersion = SemanticDocumentBuilder.DOCUMENT_VERSION, sourceDataHash = "new_hash")
            whenever(embeddingProvider.generateEmbedding(any(), any(), any()))
                .thenReturn(EmbeddingResult.Success(newRep))

            service.indexMediaItem(item)

            // Inference SHOULD be called due to version mismatch
            verify(embeddingProvider).generateEmbedding(any(), any(), any())
        }
    }

    private fun computeSha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createTestMediaItem(id: String, title: String): MediaItem {
        return MediaItem(
            id = id,
            title = title,
            mediaType = "PHOTO",
            year = 0,
            duration = "",
            genre = "",
            imageUrl = "",
            gradientColors = emptyList(),
            rating = 0f,
            isFavorite = false,
            progress = 0f,
            progressText = "",
            category = "",
            aiSummary = "",
            moodTags = emptyList(),
            uriPath = "/path/$id.jpg",
            itemCount = null,
            sizeBytes = 0,
            dateAdded = 0,
            durationMs = 0,
            width = 0,
            height = 0,
            lastViewedTimestamp = null,
            viewCount = 0,
            exposureCount = 0,
            lastExposedTimestamp = null,
            dateModified = 0,
            contentHash = null,
            parentContentId = null,
            eloRating = 1500.0,
            isDeleted = false,
            compatibilityStatus = CompatibilityStatus.PLAYABLE,
            containerFormat = "",
            videoCodec = "",
            audioCodec = "",
            compatibilityReason = "",
            conversionStatus = ConversionStatus.NONE,
            convertedUri = null,
            lastCompatibilityCheckTimestamp = null,
            selectionReason = null,
            creatorId = null,
            creatorName = null,
            sourcePlatform = null,
            replacedByMediaId = null,
            enrichmentStatus = EnrichmentStatus.PENDING,
            lastEnrichmentAttemptTimestamp = null,
            enrichmentFailureCount = 0
        )
    }
}
