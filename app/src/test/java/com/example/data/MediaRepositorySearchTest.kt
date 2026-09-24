package com.example.data

import android.graphics.Bitmap
import com.example.data.intelligence.*
import com.example.data.semantic.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositorySearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MediaRepository
    private val intelligenceCore: AuraIntelligenceCore = mock()
    private val semanticRepo: SemanticRepresentationRepository = mock()
    private val mobileClipProvider: MobileCLIPEmbeddingProvider = mock()

    @Before
    fun setUp() {
        repository = MediaRepository(testDispatcher)
        repository.sortCategory = SortCategory.INTELLIGENT
        
        // Inject components
        val coreField = MediaRepository::class.java.getDeclaredField("intelligenceCore")
        coreField.isAccessible = true
        coreField.set(repository, intelligenceCore)

        val semRepoField = MediaRepository::class.java.getDeclaredField("semanticRepresentationRepository")
        semRepoField.isAccessible = true
        semRepoField.set(repository, semanticRepo)

        val providerField = MediaRepository::class.java.getDeclaredField("mobileCLIPProvider")
        providerField.isAccessible = true
        providerField.set(repository, mobileClipProvider)
        
        val engineField = MediaRepository::class.java.getDeclaredField("hybridSearchEngine")
        engineField.isAccessible = true
        engineField.set(repository, DefaultHybridSearchEngine(intelligenceCore))
        
        whenever(mobileClipProvider.isReady()).thenReturn(true)
        val mockDescriptor = mock<EmbeddingModelDescriptor> {
            on { dimensionality } doReturn 512
            on { primaryType } doReturn SemanticRepresentationType.VISUAL
        }
        whenever(mobileClipProvider.descriptor).thenReturn(mockDescriptor)
    }

    @org.junit.After
    fun tearDown() {
        if (::repository.isInitialized) {
            repository.close()
        }
    }

    @Test
    fun testTextSearch_WrapsInRequest() = runTest(testDispatcher) {
        val query = "beach"
        val mockResponse = IntelligenceResponse(
            requestId = "req1",
            mode = IntelligenceMode.SEARCH,
            candidates = emptyList(),
            latencyMs = 10L,
            isSuccess = true
        )

        whenever(intelligenceCore.processRequest(any())).thenReturn(mockResponse)

        // Start collecting
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        runCurrent()
        clearInvocations(intelligenceCore)

        repository.librarySearchQuery = query
        
        // Passing debounce (300ms)
        advanceTimeBy(400) 
        advanceUntilIdle()
        
        // Verify the core call
        verify(intelligenceCore, atLeastOnce()).processRequest(check {
            assertEquals(IntelligenceMode.SEARCH, it.mode)
            assertEquals(query, it.query)
        })
        
        job.cancel()
    }

    @Test
    fun testVisualSearch_CallsEngine() = runTest(testDispatcher) {
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(100)
        whenever(mockBitmap.height).thenReturn(100)
        
        val vector = FloatArray(512) { 0.5f }
        val mockDescriptor = mobileClipProvider.descriptor!!
        
        val mockRep = SemanticRepresentation(
            id = "q", mediaId = "q", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = mockDescriptor, dimensionality = 512, vector = vector, sourceDataHash = "h"
        )
        whenever(mobileClipProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(mockRep))
        whenever(semanticRepo.getSpecificRepresentation(any(), eq(SemanticRepresentationType.VISUAL), any())).thenReturn(mockRep)

        val mockResponse = IntelligenceResponse(
            requestId = "vreq1",
            mode = IntelligenceMode.SEARCH,
            candidates = emptyList(),
            latencyMs = 20L,
            isSuccess = true
        )
        whenever(intelligenceCore.processRequest(any())).thenReturn(mockResponse)

        // Start collecting
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        runCurrent()
        clearInvocations(intelligenceCore)

        repository.searchByImage(mockBitmap)
        
        // Pass coroutine inside searchByImage and combine
        advanceUntilIdle()

        verify(intelligenceCore, atLeastOnce()).processRequest(check {
            assertEquals(IntelligenceMode.SEARCH, it.mode)
            assertNotNull(it.visualVector)
        })
        
        job.cancel()
    }

    @Test
    fun testClearSearch_RestoresTextEmpty() = runTest(testDispatcher) {
        repository.librarySearchQuery = "beach"
        repository.clearSearch()
        assertEquals("", repository.librarySearchQuery)
        assertTrue(repository.activeVisualReferences.value.isEmpty())
    }

    @Test
    fun testTransition_VisualToCompound() = runTest(testDispatcher) {
        // 1. Start with visual
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(10)
        
        val mockDescriptor = mobileClipProvider.descriptor!!
        val mockRep = mock<SemanticRepresentation> {
            on { mediaId } doReturn "q"
            on { vector } doReturn FloatArray(512) { 0.1f }
            on { dimensionality } doReturn 512
            on { modelDescriptor } doReturn mockDescriptor
            on { type } doReturn SemanticRepresentationType.VISUAL
        }
        whenever(mobileClipProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(mockRep))
        whenever(semanticRepo.getSpecificRepresentation(any(), any(), any())).thenReturn(mockRep)
        
        whenever(intelligenceCore.processRequest(any())).thenReturn(IntelligenceResponse("r1", IntelligenceMode.SEARCH, emptyList(), latencyMs = 5L))

        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        runCurrent()
        clearInvocations(intelligenceCore)

        repository.searchByImage(mockBitmap)
        advanceUntilIdle()
        
        verify(intelligenceCore, atLeastOnce()).processRequest(check {
            assertNotNull(it.visualVector)
        })

        // 2. Add text -> Compound
        repository.librarySearchQuery = "sunset"
        advanceTimeBy(400)
        advanceUntilIdle()

        verify(intelligenceCore, atLeastOnce()).processRequest(check {
            assertNotNull(it.visualVector)
            assertEquals("sunset", it.query)
        })
        
        job.cancel()
    }

    @Test
    fun testTransition_CompoundToText() = runTest(testDispatcher) {
        // Compound state
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(10)
        
        val mockDescriptor = mobileClipProvider.descriptor!!
        val mockRep = mock<SemanticRepresentation> {
            on { mediaId } doReturn "q"
            on { vector } doReturn FloatArray(512) { 0.1f }
            on { dimensionality } doReturn 512
            on { modelDescriptor } doReturn mockDescriptor
            on { type } doReturn SemanticRepresentationType.VISUAL
        }
        whenever(mobileClipProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(mockRep))
        whenever(semanticRepo.getSpecificRepresentation(any(), any(), any())).thenReturn(mockRep)
        
        repository.searchByImage(mockBitmap)
        repository.librarySearchQuery = "ocean"
        
        whenever(intelligenceCore.processRequest(any())).thenReturn(IntelligenceResponse("r2", IntelligenceMode.SEARCH, emptyList(), latencyMs = 5L))
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        runCurrent()
        clearInvocations(intelligenceCore)

        advanceTimeBy(400)
        advanceUntilIdle()

        // Remove visual
        repository.removeVisualAnchor()
        advanceUntilIdle()

        verify(intelligenceCore, atLeastOnce()).processRequest(check {
            assertNull(it.visualVector)
            assertEquals("ocean", it.query)
        })
        
        job.cancel()
    }

    @Test
    fun testMultiVisualTransition_CallsCoreWithMultipleVectors() = runTest(testDispatcher) {
        val mockBitmapA: Bitmap = mock()
        whenever(mockBitmapA.width).thenReturn(100)
        whenever(mockBitmapA.height).thenReturn(100)

        val mockBitmapB: Bitmap = mock()
        whenever(mockBitmapB.width).thenReturn(100)
        whenever(mockBitmapB.height).thenReturn(100)

        val mockDescriptor = mobileClipProvider.descriptor!!
        val vectorA = FloatArray(512) { 0.1f }
        val vectorB = FloatArray(512) { 0.2f }

        val repA = SemanticRepresentation(
            id = "query_A", mediaId = "query_A", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = mockDescriptor, dimensionality = 512, vector = vectorA, sourceDataHash = "hA"
        )
        val repB = SemanticRepresentation(
            id = "query_B", mediaId = "query_B", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = mockDescriptor, dimensionality = 512, vector = vectorB, sourceDataHash = "hB"
        )

        whenever(mobileClipProvider.generateEmbedding(any(), any(), any()))
            .thenReturn(EmbeddingResult.Success(repA))
            .thenReturn(EmbeddingResult.Success(repB))

        whenever(semanticRepo.getSpecificRepresentation(eq("query_A"), eq(SemanticRepresentationType.VISUAL), any())).thenReturn(repA)
        whenever(semanticRepo.getSpecificRepresentation(eq("query_B"), eq(SemanticRepresentationType.VISUAL), any())).thenReturn(repB)

        whenever(intelligenceCore.processRequest(any())).thenReturn(
            IntelligenceResponse("multi_req", IntelligenceMode.SEARCH, emptyList(), latencyMs = 10L, isSuccess = true)
        )

        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        runCurrent()
        clearInvocations(intelligenceCore)

        // 1. Add visual reference A and allow state resolution
        repository.searchByImage(mockBitmapA, "uri_A")
        advanceUntilIdle()

        // 2. Add visual reference B
        repository.searchByImage(mockBitmapB, "uri_B")
        advanceUntilIdle()

        // 3. Verify visual references accumulate both A and B without discarding A
        val references = repository.activeVisualReferences.value
        assertEquals(2, references.size)
        assertEquals("query_A", references[0].id)
        assertEquals("query_B", references[1].id)

        // 4. Verify resulting SearchRequest is MultiVisual
        val currentRequest = repository.librarySearchRequest.value
        assertTrue("Request should be MultiVisual", currentRequest is SearchRequest.MultiVisual)
        val multiVisualRequest = currentRequest as SearchRequest.MultiVisual
        assertEquals(2, multiVisualRequest.visualVectors.size)
        assertArrayEquals(vectorA, multiVisualRequest.visualVectors[0], 0.0f)
        assertArrayEquals(vectorB, multiVisualRequest.visualVectors[1], 0.0f)

        // 5. Verify AuraIntelligenceCore.processRequest receives the expected multiple query vectors
        verify(intelligenceCore, atLeastOnce()).processRequest(check { req ->
            assertEquals(IntelligenceMode.SEARCH, req.mode)
            assertNotNull(req.queryVectors)
            val vectors = req.queryVectors!!
            assertEquals(2, vectors.size)
            assertArrayEquals(vectorA, vectors[0], 0.0f)
            assertArrayEquals(vectorB, vectors[1], 0.0f)
        })

        job.cancel()
    }

    @Test
    fun testClearSearch_IsolatesSearchState() = runTest(testDispatcher) {
        val itemA = MediaItem(id = "item_A", title = "Ref A", mediaType = "PHOTO", uriPath = "uri_A")
        val itemB = MediaItem(id = "item_B", title = "Ref B", mediaType = "PHOTO", uriPath = "uri_B")

        val mockDescriptor = mobileClipProvider.descriptor!!
        val vectorA = FloatArray(512) { 0.3f }
        val vectorB = FloatArray(512) { 0.4f }

        val repA = SemanticRepresentation(
            id = "rep_A", mediaId = "item_A", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = mockDescriptor, dimensionality = 512, vector = vectorA, sourceDataHash = "hA"
        )
        val repB = SemanticRepresentation(
            id = "rep_B", mediaId = "item_B", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = mockDescriptor, dimensionality = 512, vector = vectorB, sourceDataHash = "hB"
        )

        whenever(semanticRepo.getSpecificRepresentation(eq("item_A"), eq(SemanticRepresentationType.VISUAL), any())).thenReturn(repA)
        whenever(semanticRepo.getSpecificRepresentation(eq("item_B"), eq(SemanticRepresentationType.VISUAL), any())).thenReturn(repB)

        // 1. Set text query
        repository.librarySearchQuery = "vacation"

        // 2 & 3. Add visual reference A and B
        repository.addVisualReference(itemA)
        repository.addVisualReference(itemB)
        advanceUntilIdle()

        // 4. Confirm multimodal search state exists
        assertEquals("vacation", repository.librarySearchQuery)
        assertEquals(2, repository.activeVisualReferences.value.size)
        val multiReq = repository.librarySearchRequest.value
        assertTrue(multiReq is SearchRequest.MultiVisual)
        assertEquals("vacation", (multiReq as SearchRequest.MultiVisual).query)

        // 5. Call clearSearch()
        repository.clearSearch()
        advanceUntilIdle()

        // 6. Verify text state is empty
        assertEquals("", repository.librarySearchQuery)

        // 7. Verify ALL accumulated visual references are removed
        assertTrue(repository.activeVisualReferences.value.isEmpty())
        val resetReq = repository.librarySearchRequest.value
        assertTrue(resetReq is SearchRequest.Text)
        assertEquals("", (resetReq as SearchRequest.Text).query)

        // 8. Verify the next search does not inherit either previous visual reference
        repository.librarySearchQuery = "mountains"
        advanceUntilIdle()

        val newSearchReq = repository.librarySearchRequest.value
        assertTrue(newSearchReq is SearchRequest.Text)
        assertEquals("mountains", (newSearchReq as SearchRequest.Text).query)
        assertNull(newSearchReq.visualVector)
        assertNull(newSearchReq.visualVectors)
        assertTrue(repository.activeVisualReferences.value.isEmpty())
    }
}

