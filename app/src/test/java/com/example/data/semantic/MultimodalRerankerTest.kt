package com.example.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalRerankerTest {

    @Test
    fun testRerank_BoostsAlignedCandidates() {
        val candidate1 = HybridCandidate(
            mediaId = "m1",
            rrfScore = 1.0,
            channelRanks = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 1,
                SearchChannel.SEMANTIC_VISUAL to 1
            ),
            channelScores = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 0.9f,
                SearchChannel.SEMANTIC_VISUAL to 0.9f
            )
        )
        
        val candidate2 = HybridCandidate(
            mediaId = "m2",
            rrfScore = 1.1, // Higher initial score but only one channel
            channelRanks = mapOf(SearchChannel.SEMANTIC_CONTENT to 1),
            channelScores = mapOf(SearchChannel.SEMANTIC_CONTENT to 0.95f)
        )

        val result = CrossChannelAlignmentReranker.rerank(listOf(candidate1, candidate2))
        
        // m1 should now have score 1.0 * 1.15 = 1.15, which outranks m2 (1.1)
        assertEquals("m1", result[0].mediaId)
        assertTrue(result[0].matchExplanation.contains("Aligned Multi-Modal Boost"))
    }
}
