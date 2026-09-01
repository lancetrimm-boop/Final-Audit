package com.example.data.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRequestTest {

    @Test
    fun testTextRequest_Properties() {
        val request = SearchRequest.Text("beach")
        assertEquals("beach", request.query)
        assertEquals(8, request.requestId.length)
        assertEquals(SearchQueryType.TEXT, request.queryType)
    }

    @Test
    fun testVisualRequest_Properties() {
        val vector = FloatArray(512) { 0.1f }
        val request = SearchRequest.Visual(vector)
        assertArrayEquals(vector, request.visualVector, 0.0f)
        assertEquals(8, request.requestId.length)
        assertEquals(SearchQueryType.VISUAL, request.queryType)
    }

    @Test
    fun testCompoundRequest_InferredCorrectly() {
        val vector = FloatArray(512) { 0.1f }
        val request = SearchRequest.Compound(query = "sunset", visualVector = vector)
        assertEquals(SearchQueryType.COMPOUND, request.queryType)
    }

    @Test
    fun testHybridSearchResult_QueryType_DefaultsToText() {
        val result = HybridSearchResult(
            query = "test",
            candidates = emptyList(),
            latencyMs = 10,
            totalCandidatesConsidered = 0,
            channelCandidateCounts = emptyMap()
        )
        assertEquals(SearchQueryType.TEXT, result.queryType)
    }
}
