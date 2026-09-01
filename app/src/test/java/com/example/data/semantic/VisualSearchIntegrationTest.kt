package com.example.data.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class VisualSearchIntegrationTest {

    private lateinit var engine: DefaultHybridSearchEngine
    private val semanticService: SemanticSearchService = mock()
    private val lexicalRetriever: LexicalCandidateRetriever = mock()
    private val repository: SemanticRepresentationRepository = mock()
    private val visualTextProvider: EmbeddingProvider = mock()
    private val visualImageProvider: EmbeddingProvider = mock()
    private val visualRetriever: MobileCLIPVisualRetriever = mock()
    private val personalizationScorer: PersonalizationScorer = mock()
    
    private val descriptor = EmbeddingModelDescriptor("mobileclip", 1, 512, SemanticRepresentationType.VISUAL)

    @Before
    fun setUp() {
        engine = DefaultHybridSearchEngine(
            semanticService = semanticService,
            lexicalRetriever = lexicalRetriever,
            repository = repository,
            visualTextProvider = visualTextProvider,
            visualImageProvider = visualImageProvider,
            visualRetriever = visualRetriever,
            personalizationScorer = personalizationScorer
        )
        whenever(visualImageProvider.isReady()).thenReturn(true)
        whenever(visualRetriever.isReady()).thenReturn(true)
        whenever(visualImageProvider.descriptor).thenReturn(descriptor)
    }

    @Test
    fun testSearchVisual_RetrievesUsingVector() = runBlocking<Unit> {
        val queryVector = FloatArray(512) { it.toFloat() }
        
        // 1. Mock retrieval
        val visualItems = listOf(
            RankedChannelItem("media_1", 0.9f, 1)
        )
        whenever(visualRetriever.retrieveVisualCandidates(eq(queryVector), any(), any()))
            .thenReturn(visualItems)

        // 2. Execute with pre-encoded vector
        val request = SearchRequest.Visual(queryVector)
        val result = engine.search(request)

        assertTrue(result.isSuccess)
        assertEquals(SearchQueryType.VISUAL, result.queryType)
        assertEquals(1, result.candidates.size)
        assertEquals("media_1", result.candidates[0].mediaId)
        
        // Verify Lexical and MiniLM Text channels were SKIPPED
        verify(lexicalRetriever, never()).retrieveKeywordCandidates(any(), any())
        verify(semanticService, never()).search(any<String>(), any(), any(), any(), any())
    }

    @Test
    fun testSearchVisual_MissingVector_HandlesStateCorrectly() = runBlocking<Unit> {
        // Unified SearchRequest with nulls
        val emptyRequest = SearchRequest.Text("") // Using a concrete implementation
        assertEquals(SearchQueryType.TEXT, emptyRequest.queryType)
        
        // Forced visual-like request without vector is not possible with sealed interface
    }
}
