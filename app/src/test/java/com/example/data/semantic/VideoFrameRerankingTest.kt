package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VideoFrameRerankingTest {

    private lateinit var reranker: VideoIntelligenceReranker
    private val descriptor = EmbeddingModelDescriptor(
        modelId = "mobileclip",
        modelVersion = 1,
        dimensionality = 512,
        primaryType = SemanticRepresentationType.VISUAL
    )

    @Before
    fun setUp() {
        reranker = VideoIntelligenceReranker()
    }

    @Test
    fun testRerank_StrongLateFrameMatch_PromotesVideo() {
        // Query: "car"
        val queryVector = FloatArray(512) { if (it == 0) 1.0f else 0.0f }
        
        // Video A: moderate aggregate similarity (0.6) but one very strong frame (0.95)
        val candidateA = HybridCandidate(
            mediaId = "video_a",
            rrfScore = 0.05,
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 5),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.6f),
            matchExplanation = "Initial RRF"
        )
        
        val framesA = listOf(
            createFrame("video_a", 1000, floatArrayOf(0.1f)), // Weak
            createFrame("video_a", 5000, floatArrayOf(0.95f)) // STRONG MATCH at 5s
        )

        // Video B: high aggregate similarity (0.8) but no single frame significantly better (0.82)
        val candidateB = HybridCandidate(
            mediaId = "video_b",
            rrfScore = 0.1, // Higher initial score
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 1),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.8f),
            matchExplanation = "Initial RRF"
        )
        
        val framesB = listOf(
            createFrame("video_b", 1000, floatArrayOf(0.82f))
        )

        val candidates = listOf(candidateB, candidateA)
        val frameVectors = mapOf("video_a" to framesA, "video_b" to framesB)

        val result = reranker.rerank(candidates, queryVector, frameVectors)

        // video_a should have received a significant boost and overtaken video_b
        assertEquals("video_a", result[0].mediaId)
        assertTrue(result[0].matchExplanation.contains("Max Frame Similarity: 0.950"))
    }

    @Test
    fun testRerank_NoFrameData_MaintainsOrder() {
        val candidate = HybridCandidate(
            mediaId = "m1",
            rrfScore = 0.1,
            channelRanks = mapOf(SearchChannel.KEYWORD to 1),
            channelScores = mapOf(SearchChannel.KEYWORD to 1.0f)
        )
        
        val result = reranker.rerank(listOf(candidate), null, emptyMap())
        
        assertEquals(1, result.size)
        assertEquals(0.1, result[0].rrfScore, 1e-6)
    }

    @Test
    fun testRerank_AlignmentBoost_Preserved() {
        // Item present in both Keyword and Semantic channels
        val candidate = HybridCandidate(
            mediaId = "m1",
            rrfScore = 1.0,
            channelRanks = mapOf(
                SearchChannel.SEMANTIC_CONTENT to 1,
                SearchChannel.SEMANTIC_VISUAL to 1
            ),
            channelScores = emptyMap()
        )

        val result = reranker.rerank(listOf(candidate), null, emptyMap())
        
        // 1.0 * 1.15 boost
        assertEquals(1.15, result[0].rrfScore, 1e-6)
        assertTrue(result[0].matchExplanation.contains("Aligned Multi-Modal Boost"))
    }

    private fun createFrame(mediaId: String, timestampUs: Long, vectorPrefix: FloatArray): VideoFrameRepresentation {
        val fullVector = FloatArray(512)
        vectorPrefix.copyInto(fullVector)
        return VideoFrameRepresentation(
            id = "f_$timestampUs",
            mediaId = mediaId,
            timestampUs = timestampUs,
            modelDescriptor = descriptor,
            documentVersion = 3,
            dimensionality = 512,
            vector = fullVector
        )
    }
}
