package com.example.data.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CompoundSearchRetrievalTest {

    private lateinit var engine: DefaultHybridSearchEngine
    private val semanticService: SemanticSearchService = mock()
    private val lexicalRetriever: LexicalCandidateRetriever = mock()
    private val visualTextProvider: EmbeddingProvider = mock()
    private val visualRetriever: MobileCLIPVisualRetriever = mock()
    private val reranker: MultimodalReranker = mock()
    
    private val descriptor = EmbeddingModelDescriptor("mobileclip", 1, 512, SemanticRepresentationType.VISUAL)

    @Before
    fun setUp() {
        engine = DefaultHybridSearchEngine(
            semanticService = semanticService,
            lexicalRetriever = lexicalRetriever,
            visualTextProvider = visualTextProvider,
            visualRetriever = visualRetriever,
            reranker = reranker
        )
        whenever(visualTextProvider.isReady()).thenReturn(true)
        whenever(visualRetriever.isReady()).thenReturn(true)
        
        // Default mock behavior for reranker
        whenever(reranker.rerank(any(), anyOrNull(), any())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun testSearchCompound_PassesCompoundVectorToReranker() = runBlocking<Unit> {
        val visualVector = FloatArray(512) { 1.0f }
        val textVector = FloatArray(512) { 0.5f }
        val request = SearchRequest.Compound("beach", visualVector)

        val mockRep = SemanticRepresentation(
            id = "q", mediaId = "q", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = textVector, sourceDataHash = "h"
        )
        whenever(visualTextProvider.generateEmbedding(any(), any(), any()))
            .thenReturn(EmbeddingResult.Success(mockRep))

        whenever(visualRetriever.retrieveVisualCandidates(any<FloatArray>(), any(), any()))
            .thenReturn(listOf(RankedChannelItem("media_1", 0.9f, 1)))

        engine.search(request)

        // Verify reranker received a vector that is NOT just the visual vector
        // (It should be the normalized sum)
        verify(reranker).rerank(any(), check {
            assertNotNull(it)
            val isSame = it.contentEquals(visualVector)
            assertFalse("Reranker should receive compound vector, not raw visual vector", isSame)
        }, any())
    }

    @Test
    fun testSearchCompound_ComposesVectorsAndQueriesChannels() = runBlocking<Unit> {
        val visualVector = FloatArray(512) { 0.5f }
        val textVector = FloatArray(512) { 0.5f }
        val request = SearchRequest.Compound("beach", visualVector)

        // 1. Mock text embedding generation
        val mockRep = SemanticRepresentation(
            id = "q", mediaId = "q", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = textVector, sourceDataHash = "h"
        )
        whenever(visualTextProvider.generateEmbedding(any(), any(), any()))
            .thenReturn(EmbeddingResult.Success(mockRep))

        // 2. Mock individual channel results
        whenever(visualRetriever.retrieveVisualCandidates(any<FloatArray>(), any(), any()))
            .thenReturn(listOf(RankedChannelItem("media_visual", 0.9f, 1)))
        
        whenever(lexicalRetriever.retrieveKeywordCandidates(any(), any()))
            .thenReturn(listOf(RankedChannelItem("media_lexical", 1.0f, 1)))
            
        whenever(semanticService.search(any<String>(), any(), any(), any(), any()))
            .thenReturn(SemanticSearchResult("beach", emptyList(), mock(), SemanticRepresentationType.CONTENT, 0, 0))

        // 3. Execute
        val result = engine.search(request)

        assertTrue(result.isSuccess)
        assertEquals(SearchQueryType.COMPOUND, result.queryType)
        
        // Verify composition: (0.5+0.5) normalized -> should be a unit vector
        verify(visualRetriever).retrieveVisualCandidates(check<FloatArray> {
            val mag = VectorMath.magnitude(it)
            assertEquals(1.0f, mag, 0.001f)
        }, any<Int>(), any<Float>())
    }

    @Test
    fun testSearchCompound_FallbackToVisualIfTextProviderUnready() = runBlocking<Unit> {
        whenever(visualTextProvider.isReady()).thenReturn(false)
        val visualVector = FloatArray(512) { 1.0f }
        val request = SearchRequest.Compound("beach", visualVector)

        // Should use searchVisual fallback logic
        whenever(visualRetriever.retrieveVisualCandidates(eq(visualVector), any(), any()))
            .thenReturn(listOf(RankedChannelItem("media_visual", 0.9f, 1)))

        val result = engine.search(request)
        
        assertTrue(result.isSuccess)
        // Note: fallback logic in 11.2 might have returned VISUAL queryType
        // But searchCompound currently calls searchVisual(request, config)
        // searchVisual returns queryType=VISUAL and query="Visual Reference..."
        assertEquals(SearchQueryType.VISUAL, result.queryType)
    }
}
