package com.example.data.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SearchIntegrationTest {

    private lateinit var hybridEngine: HybridSearchEngine
    private val semanticService: SemanticSearchService = mock()
    private val lexicalRetriever: LexicalCandidateRetriever = mock()

    @Before
    fun setUp() {
        hybridEngine = DefaultHybridSearchEngine(
            semanticService = semanticService,
            lexicalRetriever = lexicalRetriever
        )
    }

    @Test
    fun testHybridSearch_CombinesBothChannels() = runBlocking {
        val query = "beach"
        
        // Lexical results: IMG_beach.jpg
        whenever(lexicalRetriever.retrieveKeywordCandidates(any<String>(), any<Int>()))
            .thenReturn(listOf(RankedChannelItem("m_lexical", 1.0f, 1)))

        // Semantic results: VID_vacation.mp4 (scored high for "beach")
        val descriptor = EmbeddingModelDescriptor("test", 1, 384, SemanticRepresentationType.CONTENT)
        val semanticCandidates = listOf(
            SemanticRetrievalCandidate("m_semantic", "r1", 0.85f, SemanticRepresentationType.CONTENT, descriptor, 1.0f)
        )
        whenever(semanticService.search(any<String>(), any<Int>(), any<Float>(), any<SemanticRepresentationType>(), any()))
            .thenReturn(SemanticSearchResult(query, semanticCandidates, descriptor, SemanticRepresentationType.CONTENT, 10, 1))

        val result = hybridEngine.search(query)

        assertTrue(result.isSuccess)
        assertEquals(2, result.candidates.size)
        
        val ids = result.candidates.map { it.mediaId }
        assertTrue(ids.contains("m_lexical"))
        assertTrue(ids.contains("m_semantic"))
    }

    @Test
    fun testSemanticMatch_ReachesResultsWithoutLexicalOverlap() = runBlocking {
        val query = "bikini"
        
        // No lexical matches
        whenever(lexicalRetriever.retrieveKeywordCandidates(any<String>(), any<Int>())).thenReturn(emptyList())

        // Strong semantic match
        val descriptor = EmbeddingModelDescriptor("test", 1, 384, SemanticRepresentationType.CONTENT)
        val semanticCandidates = listOf(
            SemanticRetrievalCandidate("m_visual", "r1", 0.9f, SemanticRepresentationType.CONTENT, descriptor, 1.0f)
        )
        whenever(semanticService.search(any<String>(), any<Int>(), any<Float>(), any<SemanticRepresentationType>(), any()))
            .thenReturn(SemanticSearchResult(query, semanticCandidates, descriptor, SemanticRepresentationType.CONTENT, 10, 1))

        val result = hybridEngine.search(query)

        assertEquals(1, result.candidates.size)
        assertEquals("m_visual", result.candidates[0].mediaId)
    }

    @Test
    fun testRanking_ExactLexicalOutranksWeakSemantic() = runBlocking {
        val query = "car"
        
        // Lexical: "My Car.jpg" (Rank 1)
        whenever(lexicalRetriever.retrieveKeywordCandidates(any<String>(), any<Int>()))
            .thenReturn(listOf(RankedChannelItem("m_car", 1.0f, 1)))

        // Semantic: "transport.mp4" (Score 0.4 - weak)
        val descriptor = EmbeddingModelDescriptor("test", 1, 384, SemanticRepresentationType.CONTENT)
        val semanticCandidates = listOf(
            SemanticRetrievalCandidate("m_transport", "r1", 0.4f, SemanticRepresentationType.CONTENT, descriptor, 1.0f)
        )
        whenever(semanticService.search(any<String>(), any<Int>(), any<Float>(), any<SemanticRepresentationType>(), any()))
            .thenReturn(SemanticSearchResult(query, semanticCandidates, descriptor, SemanticRepresentationType.CONTENT, 10, 1))

        val result = hybridEngine.search(query)

        // RRF should favor the Rank #1 match
        assertEquals("m_car", result.candidates[0].mediaId)
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
    ): com.example.data.MediaItem {
        return com.example.data.MediaItem(
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
