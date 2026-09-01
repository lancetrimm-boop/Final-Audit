package com.example.data

import android.graphics.Bitmap
import com.example.data.semantic.HybridSearchEngine
import com.example.data.semantic.HybridSearchResult
import com.example.data.semantic.SearchQueryType
import com.example.data.semantic.SearchRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositorySearchTest {
// ... (rest of the class)

    private lateinit var repository: MediaRepository
    private val hybridSearchEngine: HybridSearchEngine = mock()

    @Before
    fun setUp() {
        // MediaRepository is a singleton in this project structure
        repository = MediaRepository.instance
        
        // Inject mock engine
        val field = MediaRepository::class.java.getDeclaredField("hybridSearchEngine")
        field.isAccessible = true
        field.set(repository, hybridSearchEngine)
        
        whenever(hybridSearchEngine.isSemanticReady()).thenReturn(true)
    }

    @Test
    fun testTextSearch_WrapsInRequest() = runTest {
        val query = "beach"
        val mockResult = HybridSearchResult(
            query = query,
            queryType = SearchQueryType.TEXT,
            requestId = "req1",
            candidates = emptyList(),
            latencyMs = 10,
            totalCandidatesConsidered = 0,
            channelCandidateCounts = emptyMap(),
            isSuccess = true
        )

        whenever(hybridSearchEngine.search(any<SearchRequest>(), any())).thenReturn(mockResult)

        // Start collecting to trigger transformLatest
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.librarySearchQuery = query
        
        advanceTimeBy(400) // Pass debounce
        
        // Verify the engine call
        verify(hybridSearchEngine, timeout(1000)).search(check<SearchRequest> {
            assertTrue(it.queryType == SearchQueryType.TEXT && it.query == query)
        }, any())
        
        job.cancel()
    }

    @Test
    fun testVisualSearch_CallsEngine() = runTest {
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(100)
        whenever(mockBitmap.height).thenReturn(100)
        
        // Mock provider for encoding
        val mockProvider: com.example.data.semantic.MobileCLIPEmbeddingProvider = mock()
        val field = MediaRepository::class.java.getDeclaredField("mobileCLIPProvider")
        field.isAccessible = true
        field.set(repository, mockProvider)
        whenever(mockProvider.isReady()).thenReturn(true)
        
        val vector = FloatArray(512) { 0.5f }
        val mockRep = com.example.data.semantic.SemanticRepresentation(
            id = "q", mediaId = "q", type = com.example.data.semantic.SemanticRepresentationType.VISUAL,
            modelDescriptor = mock(), dimensionality = 512, vector = vector, sourceDataHash = "h"
        )
        whenever(mockProvider.generateEmbedding(any(), any(), any())).thenReturn(com.example.data.semantic.EmbeddingResult.Success(mockRep))

        val mockResult = HybridSearchResult(
            query = "Visual",
            queryType = SearchQueryType.VISUAL,
            requestId = "vreq1",
            candidates = emptyList(),
            latencyMs = 20,
            totalCandidatesConsidered = 0,
            channelCandidateCounts = emptyMap(),
            isSuccess = true
        )

        whenever(hybridSearchEngine.search(any<SearchRequest>(), any())).thenReturn(mockResult)

        // Start collecting to trigger transformLatest
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.searchByImage(mockBitmap, "content://test")
        
        // Pass some time for encoding and debounce logic
        advanceTimeBy(500)

        verify(hybridSearchEngine, timeout(2000)).search(check<SearchRequest> {
            assertTrue(it.queryType == SearchQueryType.VISUAL)
            assertNotNull(it.visualVector)
            assertEquals("content://test", it.referenceUri)
        }, any())
        
        job.cancel()
    }

    @Test
    fun testClearSearch_RestoresTextEmpty() = runTest {
        repository.clearSearch()
        assertEquals("", repository.librarySearchQuery)
        val currentRequest = repository.librarySearchRequest.value
        assertEquals(SearchQueryType.TEXT, currentRequest.queryType)
        assertEquals("", currentRequest.query)
    }

    @Test
    fun testTransition_VisualToCompound() = runTest {
        val vector = FloatArray(512) { 0.1f }
        // 1. Establish Visual Search
        val visualReq = SearchRequest.Visual(vector, "uri")
        val field = MediaRepository::class.java.getDeclaredField("_librarySearchRequest")
        field.isAccessible = true
        (field.get(repository) as MutableStateFlow<SearchRequest>).value = visualReq

        // 2. Set Text Query
        repository.librarySearchQuery = "sunset"

        // 3. Verify Compound State
        val current = repository.librarySearchRequest.value
        assertEquals(SearchQueryType.COMPOUND, current.queryType)
        assertEquals("sunset", current.query)
        assertArrayEquals(vector, current.visualVector, 0.0f)
    }

    @Test
    fun testTransition_CompoundToText() = runTest {
        val vector = FloatArray(512) { 0.1f }
        // 1. Establish Compound Search
        val compReq = SearchRequest.Compound("beach", vector, "uri")
        val field = MediaRepository::class.java.getDeclaredField("_librarySearchRequest")
        field.isAccessible = true
        (field.get(repository) as MutableStateFlow<SearchRequest>).value = compReq

        // 2. Remove Anchor
        repository.removeVisualAnchor()

        // 3. Verify Text-only State
        val current = repository.librarySearchRequest.value
        assertEquals(SearchQueryType.TEXT, current.queryType)
        assertEquals("beach", current.query)
        assertNull(current.visualVector)
    }
}
