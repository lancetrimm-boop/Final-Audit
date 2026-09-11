package com.example.data

import android.graphics.Bitmap
import com.example.data.semantic.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class VisualSearchAccumulationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MediaRepository
    private val mockProvider: MobileCLIPEmbeddingProvider = mock()
    private val semanticRepo: SemanticRepresentationRepository = mock()

    @Before
    fun setUp() {
        repository = MediaRepository(testDispatcher)
        
        // Inject dependencies using reflection
        val providerField = MediaRepository::class.java.getDeclaredField("mobileCLIPProvider")
        providerField.isAccessible = true
        providerField.set(repository, mockProvider)
        
        val repoField = MediaRepository::class.java.getDeclaredField("semanticRepresentationRepository")
        repoField.isAccessible = true
        repoField.set(repository, semanticRepo)

        whenever(mockProvider.isReady()).thenReturn(true)
        val descriptor = EmbeddingModelDescriptor("test", 1, 512, SemanticRepresentationType.VISUAL)
        whenever(mockProvider.descriptor).thenReturn(descriptor)
    }

    @Test
    fun testVisualSearch_AccumulationFlow() = runTest(testDispatcher) {
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(100)
        whenever(mockBitmap.height).thenReturn(100)

        val vectorA = FloatArray(512) { 0.1f }
        val descriptor = mockProvider.descriptor!!
        val repA = SemanticRepresentation(
            id = "query_A", mediaId = "query_A", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = vectorA, sourceDataHash = "h"
        )
        
        val vectorB = FloatArray(512) { 0.2f }
        val repB = SemanticRepresentation(
            id = "query_B", mediaId = "query_B", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = vectorB, sourceDataHash = "h"
        )

        // 1. First reference (Start)
        whenever(mockProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(repA))
        // Ensure deriveSearchRequest can find it
        whenever(semanticRepo.getSpecificRepresentation(eq("query_A"), any(), any())).thenReturn(repA)

        repository.searchByImage(mockBitmap, "uri_A")
        advanceUntilIdle()

        assertEquals(1, repository.activeVisualReferences.value.size)
        assertEquals("query_A", repository.activeVisualReferences.value[0].id)
        verify(semanticRepo).saveRepresentation(repA)

        // 2. Second reference (Accumulate)
        whenever(mockProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(repB))
        whenever(semanticRepo.getSpecificRepresentation(eq("query_B"), any(), any())).thenReturn(repB)

        repository.searchByImage(mockBitmap, "uri_B")
        advanceUntilIdle()

        assertEquals(2, repository.activeVisualReferences.value.size)
        assertEquals("query_A", repository.activeVisualReferences.value[0].id)
        assertEquals("query_B", repository.activeVisualReferences.value[1].id)
        verify(semanticRepo).saveRepresentation(repB)

        // 3. Verify SearchRequest is MultiVisual
        val request = repository.librarySearchRequest.value
        assertTrue(request is SearchRequest.MultiVisual)
        val multi = request as SearchRequest.MultiVisual
        assertEquals(2, multi.visualVectors.size)
        assertArrayEquals(vectorA, multi.visualVectors[0], 0.0f)
        assertArrayEquals(vectorB, multi.visualVectors[1], 0.0f)
    }

    @Test
    fun testMultiImageSearch_Accumulation() = runTest(testDispatcher) {
        val item1 = MediaItem(id = "item1", title = "T1", mediaType = "PHOTO", uriPath = "u1")
        val item2 = MediaItem(id = "item2", title = "T2", mediaType = "PHOTO", uriPath = "u2")
        val item3 = MediaItem(id = "item3", title = "T3", mediaType = "PHOTO", uriPath = "u3")

        // 1. Initial search with item1
        repository.addVisualReference(item1)
        assertEquals(listOf(item1), repository.activeVisualReferences.value)

        // 2. Accumulate multiple items (item2, item3)
        repository.searchByMultipleImages(listOf(item2, item3))
        assertEquals(3, repository.activeVisualReferences.value.size)
        assertEquals(listOf(item1, item2, item3), repository.activeVisualReferences.value)
    }

    @Test
    fun testCompoundSearch_MultiVisualAccumulation() = runTest(testDispatcher) {
        val item1 = MediaItem(id = "item1", title = "T1", mediaType = "PHOTO", uriPath = "u1")
        val vector1 = FloatArray(512) { 0.1f }
        val descriptor = mockProvider.descriptor!!
        val rep1 = SemanticRepresentation(
            id = "r1", mediaId = "item1", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = vector1, sourceDataHash = "h"
        )
        whenever(semanticRepo.getSpecificRepresentation(eq("item1"), any(), any())).thenReturn(rep1)

        // 1. Start with text
        repository.librarySearchQuery = "beach"
        
        // 2. Add first visual reference
        repository.addVisualReference(item1)
        advanceUntilIdle()

        val request1 = repository.librarySearchRequest.value
        assertTrue(request1 is SearchRequest.Compound)
        assertEquals("beach", (request1 as SearchRequest.Compound).query)
        assertArrayEquals(vector1, request1.visualVector, 0.0f)

        // 3. Add second visual reference (picks from disk)
        val mockBitmap: Bitmap = mock()
        whenever(mockBitmap.width).thenReturn(100)
        whenever(mockBitmap.height).thenReturn(100)
        val vector2 = FloatArray(512) { 0.2f }
        val rep2 = SemanticRepresentation(
            id = "query_2", mediaId = "query_2", type = SemanticRepresentationType.VISUAL,
            modelDescriptor = descriptor, dimensionality = 512, vector = vector2, sourceDataHash = "h"
        )
        whenever(mockProvider.generateEmbedding(any(), any(), any())).thenReturn(EmbeddingResult.Success(rep2))
        whenever(semanticRepo.getSpecificRepresentation(eq("query_2"), any(), any())).thenReturn(rep2)

        repository.searchByImage(mockBitmap, "uri_2")
        advanceUntilIdle()

        // 4. Verify results in MultiVisual with text
        val request2 = repository.librarySearchRequest.value
        assertTrue(request2 is SearchRequest.MultiVisual)
        val multi = request2 as SearchRequest.MultiVisual
        assertEquals("beach", multi.query)
        assertEquals(2, multi.visualVectors.size)
        assertArrayEquals(vector1, multi.visualVectors[0], 0.0f)
        assertArrayEquals(vector2, multi.visualVectors[1], 0.0f)
    }

    @Test
    fun testClearSearch_StartsClean() = runTest(testDispatcher) {
        val item1 = MediaItem(id = "item1", title = "T1", mediaType = "PHOTO", uriPath = "u1")
        repository.addVisualReference(item1)
        assertEquals(1, repository.activeVisualReferences.value.size)

        repository.clearSearch()
        assertEquals(0, repository.activeVisualReferences.value.size)

        val item2 = MediaItem(id = "item2", title = "T2", mediaType = "PHOTO", uriPath = "u2")
        repository.addVisualReference(item2)
        assertEquals(1, repository.activeVisualReferences.value.size)
        assertEquals("item2", repository.activeVisualReferences.value[0].id)
    }
}
