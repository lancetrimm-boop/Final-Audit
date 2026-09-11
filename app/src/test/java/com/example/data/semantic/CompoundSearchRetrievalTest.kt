package com.example.data.semantic

import com.example.data.intelligence.*
import com.example.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CompoundSearchRetrievalTest {

    private lateinit var engine: DefaultHybridSearchEngine
    private val core: AuraIntelligenceCore = mock()

    @Before
    fun setUp() {
        engine = DefaultHybridSearchEngine(core)
    }

    @Test
    fun testSearchCompound_DelegatesBothModalitiesToCore() = runBlocking<Unit> {
        val visualVector = FloatArray(512) { 1.0f }
        val request = SearchRequest.Compound("beach", visualVector)

        val item = MediaItem(id = "item1", title = "Beach", mediaType = "PHOTO")
        val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Match"))
        val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, latencyMs = 10L)

        whenever(core.processRequest(any())).thenReturn(response)

        engine.search(request)

        // Verify core received both text and visual components
        verify(core).processRequest(check {
            assertEquals(IntelligenceMode.SEARCH, it.mode)
            assertEquals("beach", it.query)
            assertArrayEquals(visualVector, it.visualVector, 1e-6f)
        })
    }

    @Test
    fun testSearchCompound_ReturnsFusedSuccess() = runBlocking<Unit> {
        val visualVector = FloatArray(512) { 0.5f }
        val request = SearchRequest.Compound("sunset", visualVector)

        val item = MediaItem(id = "item_fused", title = "Sunset", mediaType = "PHOTO")
        val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Fused Match"))
        val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, latencyMs = 10L)

        whenever(core.processRequest(any())).thenReturn(response)

        val result = engine.search(request)

        assertTrue(result.isSuccess)
        assertEquals(SearchQueryType.COMPOUND, result.queryType)
        assertEquals(1, result.candidates.size)
        assertEquals("item_fused", result.candidates[0].mediaId)
    }
}
