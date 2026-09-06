package com.example.data.semantic

import com.example.data.intelligence.*
import com.example.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class VisualSearchIntegrationTest {

    private lateinit var engine: DefaultHybridSearchEngine
    private val core: AuraIntelligenceCore = mock()

    @Before
    fun setUp() {
        engine = DefaultHybridSearchEngine(core)
    }

    @Test
    fun testSearchVisual_RetrievesUsingVector() = runBlocking<Unit> {
        val queryVector = FloatArray(512) { it.toFloat() }
        
        val item = MediaItem(id = "media_1", title = "Visual Match", mediaType = "PHOTO")
        val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Visual Match"))
        val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, 10L)

        whenever(core.processRequest(any())).thenReturn(response)

        // 2. Execute with pre-encoded vector
        val request = SearchRequest.Visual(queryVector)
        val result = engine.search(request)

        assertTrue(result.isSuccess)
        assertEquals(SearchQueryType.VISUAL, result.queryType)
        assertEquals(1, result.candidates.size)
        assertEquals("media_1", result.candidates[0].mediaId)
        
        verify(core).processRequest(check {
            assertEquals(IntelligenceMode.SEARCH, it.mode)
            assertArrayEquals(queryVector, it.visualVector, 1e-6f)
        })
    }
}
