package com.example.data.semantic

import android.content.Context
import com.example.data.CompatibilityStatus
import com.example.data.ConversionStatus
import com.example.data.EnrichmentStatus
import com.example.data.MediaItem
import com.example.util.MediaThumbnailFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class VisualIndexingServiceTest {

    private lateinit var service: VisualIndexingService
    private val visualProvider: EmbeddingProvider = mock()
    private val candidateRetriever: SemanticCandidateRetriever = mock()
    private val repository: SemanticRepresentationRepository = mock()
    private val context: Context = mock()

    private val descriptor = EmbeddingModelDescriptor(
        modelId = "mobileclip", 
        modelVersion = 1, 
        dimensionality = 512, 
        primaryType = SemanticRepresentationType.VISUAL
    )

    @Before
    fun setUp() {
        service = DefaultVisualIndexingService(visualProvider, candidateRetriever, repository)
        whenever(visualProvider.descriptor).thenReturn(descriptor)
    }

    @Test
    fun testIndexVisual_ReusesExisting_Image() {
        runBlocking {
            val item = createTestMediaItem("m1", type = "PHOTO")
            val existing = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.VISUAL,
                modelDescriptor = descriptor,
                dimensionality = 512,
                vector = FloatArray(512) { 0f },
                sourceDataHash = "v1_m1_0", // Default hash in test helper
                documentVersion = DefaultVisualIndexingService.IMAGE_INDEX_VERSION
            )

            whenever(repository.getSpecificRepresentation(eq("m1"), eq(SemanticRepresentationType.VISUAL), eq(descriptor)))
                .thenReturn(existing)

            val result = service.indexVisual(context, item)

            assertTrue(result is EmbeddingResult.Success)
            verify(visualProvider, never()).generateEmbedding(any(), any(), any())
            verify(candidateRetriever).onRepresentationAdded(existing)
        }
    }

    @Test
    fun testIndexVisual_ReusesExisting_Video() {
        runBlocking {
            val item = createTestMediaItem("m1", type = "VIDEO")
            val existing = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.VISUAL,
                modelDescriptor = descriptor,
                dimensionality = 512,
                vector = FloatArray(512) { 0f },
                sourceDataHash = "v1_m1_0",
                documentVersion = DefaultVisualIndexingService.VISUAL_INDEX_VERSION
            )

            whenever(repository.getSpecificRepresentation(eq("m1"), eq(SemanticRepresentationType.VISUAL), eq(descriptor)))
                .thenReturn(existing)

            val result = service.indexVisual(context, item)

            assertTrue(result is EmbeddingResult.Success)
            verify(visualProvider, never()).generateEmbedding(any(), any(), any())
        }
    }

    @Test
    fun testIndexVisual_StaleVersion_Video_TriggersReindex() {
        runBlocking {
            val item = createTestMediaItem("m1", type = "VIDEO")
            val stale = SemanticRepresentation(
                id = "rep_m1",
                mediaId = "m1",
                type = SemanticRepresentationType.VISUAL,
                modelDescriptor = descriptor,
                dimensionality = 512,
                vector = FloatArray(512) { 0f },
                sourceDataHash = "v1_m1_0",
                documentVersion = 3 // Stale (Old Version 3)
            )

            whenever(repository.getSpecificRepresentation(any(), any(), any())).thenReturn(stale)
            
            service.indexVisual(context, item)
            
            // Should NOT reuse the stale version 3 record
            verify(candidateRetriever, never()).onRepresentationAdded(stale)
        }
    }

    private fun createTestMediaItem(id: String, type: String = "PHOTO"): MediaItem {
        return MediaItem(
            id = id,
            title = "Test",
            mediaType = type,
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
