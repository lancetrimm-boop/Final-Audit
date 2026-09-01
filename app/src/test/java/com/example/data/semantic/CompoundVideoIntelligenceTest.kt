package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CompoundVideoIntelligenceTest {

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
    fun testRerank_CompoundVector_PromotesVideoMatchingBothConstraints() {
        // Compound Query Vector: (Reference Image + Text Constraint)
        // Let's say Image is [1, 0, ...] and Text is [0, 1, ...]
        // Compound normalized sum will have components in both
        val compoundVector = FloatArray(512) { i ->
            when (i) {
                0 -> 0.707f // Image signal
                1 -> 0.707f // Text signal
                else -> 0f
            }
        }
        
        // Video A: matches image well (0.9), text poorly (0.1). Aggregate ~0.5
        // Best frame matches image well but not text.
        val framesA = listOf(
            createFrame("video_a", 1000, floatArrayOf(0.9f, 0.1f)) // dot product with compound: 0.9*0.707 + 0.1*0.707 ~= 0.707
        )
        val candidateA = HybridCandidate(
            mediaId = "video_a",
            rrfScore = 0.05,
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 5),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.5f)
        )

        // Video B: matches both reasonably well (0.8, 0.8). Aggregate ~0.6
        // Best frame matches both!
        val framesB = listOf(
            createFrame("video_b", 2000, floatArrayOf(0.85f, 0.85f)) // dot product with compound: 0.85*0.707 + 0.85*0.707 ~= 1.2
            // Wait, normalized vectors don't sum like that.
            // If frame is [0.707, 0.707], dot with compound [0.707, 0.707] is 1.0 (Exact match)
        )
        val candidateB = HybridCandidate(
            mediaId = "video_b",
            rrfScore = 0.04, // Lower initial score than A
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 10),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.6f)
        )

        val frameVectors = mapOf("video_a" to framesA, "video_b" to framesB)
        val result = reranker.rerank(listOf(candidateA, candidateB), compoundVector, frameVectors)

        // Video B should be promoted above A because its frame matches the COMPOUND query better
        assertEquals("video_b", result[0].mediaId)
        assertTrue(result[0].matchExplanation.contains("Max Frame Similarity"))
        assertTrue(result[0].matchExplanation.contains("Max Frame Promotion"))
    }

    @Test
    fun testRerank_CompoundVector_LateSceneDiscovery() {
        val compoundVector = FloatArray(512) { if (it == 0) 1.0f else 0.0f }
        
        // Video has a matching frame at the end
        val frames = listOf(
            createFrame("video_1", 0, floatArrayOf(0.1f)),
            createFrame("video_1", 5000, floatArrayOf(0.1f)),
            createFrame("video_1", 10000, floatArrayOf(0.98f)) // STRONG MATCH AT 10s
        )
        
        val candidate = HybridCandidate(
            mediaId = "video_1",
            rrfScore = 0.01,
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 50),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.2f) // Weak aggregate
        )
        
        val result = reranker.rerank(listOf(candidate), compoundVector, mapOf("video_1" to frames))
        
        assertTrue(result[0].rrfScore > 0.01)
        assertTrue(result[0].matchExplanation.contains("Max Frame Similarity: 0.980"))
    }

    private fun createFrame(mediaId: String, timestampUs: Long, vectorPrefix: FloatArray): VideoFrameRepresentation {
        val fullVector = FloatArray(512)
        // We normalize the prefix for math correctness in dot products
        val magnitude = kotlin.math.sqrt(vectorPrefix.sumOf { (it * it).toDouble() }).toFloat()
        val normalizedPrefix = vectorPrefix.map { it / magnitude }.toFloatArray()
        
        normalizedPrefix.copyInto(fullVector)
        return VideoFrameRepresentation(
            id = "f_$timestampUs",
            mediaId = mediaId,
            timestampUs = timestampUs,
            modelDescriptor = descriptor,
            documentVersion = 4,
            dimensionality = 512,
            vector = fullVector
        )
    }
}
