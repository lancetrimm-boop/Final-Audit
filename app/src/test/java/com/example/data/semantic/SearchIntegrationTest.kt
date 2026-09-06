package com.example.data.semantic

import com.example.data.intelligence.*
import com.example.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class SearchIntegrationTest {

    private lateinit var hybridEngine: HybridSearchEngine
    private val core: AuraIntelligenceCore = mock()

    @Before
    fun setUp() {
        hybridEngine = DefaultHybridSearchEngine(core)
    }

    @Test
    fun testHybridSearch_DelegatesToCore() {
        runBlocking {
            val query = "beach"
            
            val item = MediaItem(id = "item1", title = "Beach", mediaType = "PHOTO")
            val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Match"))
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, 10L)

            whenever(core.processRequest(any())).thenReturn(response)

            val result = hybridEngine.search(query)

            assertTrue(result.isSuccess)
            assertEquals(1, result.candidates.size)
            assertEquals("item1", result.candidates[0].mediaId)
            
            verify(core).processRequest(argThat { req -> 
                req.mode == IntelligenceMode.SEARCH && req.query == "beach" 
            })
        }
    }

    @Test
    fun testSemanticDocument_ContainsExpectedLabels() {
        val item = createMockMediaItem(
            title = "Family Picnic",
            uriPath = "/DCIM/Events/picnic.jpg",
            genre = "Family",
            moodTags = listOf("happy", "outdoor")
        )
        
        val doc = SemanticDocumentBuilder.buildDocument(item)
        
        // Verify structure
        assertTrue(doc.contains("Title: Family Picnic"))
        assertTrue(doc.contains("Folder: Events"))
        assertTrue(doc.contains("Tags: Family, happy, outdoor"))
    }

    private fun createMockMediaItem(
        title: String,
        uriPath: String,
        genre: String = "",
        moodTags: List<String> = emptyList()
    ): MediaItem {
        return MediaItem(
            id = "test",
            title = title,
            mediaType = "PHOTO",
            year = 0,
            duration = "",
            genre = genre,
            imageUrl = "",
            gradientColors = emptyList(),
            rating = 0f,
            isFavorite = false,
            progress = 0f,
            progressText = "",
            category = "",
            aiSummary = "",
            moodTags = moodTags,
            uriPath = uriPath,
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
            compatibilityStatus = com.example.data.CompatibilityStatus.PLAYABLE,
            containerFormat = "",
            videoCodec = "",
            audioCodec = "",
            compatibilityReason = "",
            conversionStatus = com.example.data.ConversionStatus.NONE,
            convertedUri = null,
            lastCompatibilityCheckTimestamp = null,
            selectionReason = null,
            creatorId = null,
            creatorName = null,
            sourcePlatform = null,
            replacedByMediaId = null,
            enrichmentStatus = com.example.data.EnrichmentStatus.PENDING,
            lastEnrichmentAttemptTimestamp = null,
            enrichmentFailureCount = 0
        )
    }
}
