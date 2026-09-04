package com.example.data.semantic

import com.example.data.MediaItem
import com.example.data.MediaRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MultiReferenceSearchTest {

    private lateinit var repository: MediaRepository
    private val hybridEngine: HybridSearchEngine = mock()

    @Before
    fun setUp() {
        // We'll use a real repository but mock the engine if needed for end-to-end flow testing
        // Or just test the engine's multiVisual logic directly.
    }

    @Test
    fun `test SearchRequest MultiVisual properties`() {
        val v1 = floatArrayOf(0.1f, 0.2f)
        val v2 = floatArrayOf(0.3f, 0.4f)
        val u1 = "uri1"
        val u2 = "uri2"
        
        val request = SearchRequest.MultiVisual(
            visualVectors = listOf(v1, v2),
            referenceUris = listOf(u1, u2)
        )
        
        assertEquals(v1, request.visualVector)
        assertEquals(u1, request.referenceUri)
        assertEquals(SearchQueryType.MULTI_VISUAL, request.queryType)
    }
}
