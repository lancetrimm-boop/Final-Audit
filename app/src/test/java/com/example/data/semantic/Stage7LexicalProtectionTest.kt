package com.example.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage7LexicalProtectionTest {

    private val reranker = VideoIntelligenceReranker()

    @Test
    fun `test exact lexical match outranks aligned multimodal candidate`() {
        // SCENARIO: 
        // m1: Exact Keyword Match (Authoritative). 
        //     RRF Score for Keyword #1 with weight 0.5 = 0.5 / 61 ≈ 0.0082
        
        val lexicalCandidate = HybridCandidate(
            mediaId = "m1-filename-match",
            rrfScore = 0.0082,
            channelRanks = mapOf(SearchChannel.KEYWORD to 1),
            channelScores = mapOf(SearchChannel.KEYWORD to 100.0f),
            isAuthoritativeLexical = true,
            matchExplanation = "Exact match"
        )
        
        // m2: Aligned candidate (Semantic Content #1 + Semantic Visual #1).
        //     RRF Score with weights 0.4 and 0.2 = (0.4 / 61) + (0.2 / 61) ≈ 0.0098
        //     This item receives the 1.15x Alignment Boost.
        
        val alignedCandidate = HybridCandidate(
            mediaId = "m2-aligned",
            rrfScore = 0.0098,
            channelRanks = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 1,
                SearchChannel.SEMANTIC_VISUAL to 1
            ),
            channelScores = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 0.8f,
                SearchChannel.SEMANTIC_VISUAL to 0.8f
            ),
            isAuthoritativeLexical = false,
            matchExplanation = "Semantic overlap"
        )

        val candidates = listOf(lexicalCandidate, alignedCandidate)
        
        // EXECUTE RERANKING
        val results = reranker.rerank(candidates)

        // VERIFY ORDER
        // m2 boosted: 0.0098 * 1.15 = 0.01127
        // m1 boosted: 0.0082 * 1.40 = 0.01148 (Wins!)
        
        assertEquals("m1-filename-match", results[0].mediaId)
        assertTrue(results[0].matchExplanation.contains("Lexical Protection Boost"))
    }

    @Test
    fun `test non-authoritative lexical match does not receive protection boost`() {
        // m1: Substring match (Score 60.0f)
        val weakLexical = HybridCandidate(
            mediaId = "m1-substring",
            rrfScore = 0.0082,
            channelRanks = mapOf(SearchChannel.KEYWORD to 1),
            channelScores = mapOf(SearchChannel.KEYWORD to 60.0f),
            isAuthoritativeLexical = false
        )
        
        val aligned = HybridCandidate(
            mediaId = "m2-aligned",
            rrfScore = 0.0098,
            channelRanks = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 1,
                SearchChannel.SEMANTIC_VISUAL to 1
            ),
            channelScores = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 0.8f,
                SearchChannel.SEMANTIC_VISUAL to 0.8f
            )
        )

        val results = reranker.rerank(listOf(weakLexical, aligned))

        // Aligned should win (it gets 1.15x, weakLexical gets 0x)
        assertEquals("m2-aligned", results[0].mediaId)
        assertTrue(results[0].matchExplanation.contains("Aligned Multi-Modal Boost"))
    }
}
