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
        val compoundVector = FloatArray(512) { i ->
            when (i) {
                0 -> 0.707f // Image signal
                1 -> 0.707f // Text signal
                else -> 0f
            }
        }
        
        // Video A: matches image well (0.9), text poorly (0.1). Aggregate ~0.5
        val framesA = listOf(
            createFrame("video_a", 1000, floatArrayOf(0.9f, 0.1f)) // maxSim ~= 0.78
        )
        val candidateA = HybridCandidate(
            mediaId = "video_a",
            rrfScore = 0.05,
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 5),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.5f)
        )

        // Video B: matches both constraints perfectly (0.707, 0.707)
        val framesB = listOf(
            createFrame("video_b", 2000, floatArrayOf(0.707f, 0.707f)) // maxSim = 1.0
        )
        val candidateB = HybridCandidate(
            mediaId = "video_b",
            rrfScore = 0.049, // Just slightly lower than A to ensure promotion works
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 6),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.5f)
        )

        val frameVectors = mapOf("video_a" to framesA, "video_b" to framesB)
        val result = reranker.rerank(
            candidates = listOf(candidateA, candidateB),
            queryVector = compoundVector,
            frameVectors = frameVectors
        )

        // Video B should be promoted above A because its frame matches the COMPOUND query better (1.0 vs 0.78)
        assertEquals("video_b", result[0].mediaId)
        assertTrue(result[0].matchExplanation.contains("Max Frame Promotion"))
    }

    @Test
    fun testRerank_CompoundVector_LateSceneDiscovery() {
        val compoundVector = FloatArray(512) { if (it == 0) 1.0f else 0.0f }
        
        // Video has a matching frame at the end
        val frames = listOf(
            createFrame("video_1", 0, floatArrayOf(0.1f)),
            createFrame("video_1", 5000, floatArrayOf(0.1f)),
            createFrame("video_1", 10000, floatArrayOf(1.0f)) // PERFECT MATCH AT 10s
        )
        
        val candidate = HybridCandidate(
            mediaId = "video_1",
            rrfScore = 0.01,
            channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 50),
            channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.2f) // Weak aggregate
        )
        
        val result = reranker.rerank(
            candidates = listOf(candidate),
            queryVector = compoundVector,
            frameVectors = mapOf("video_1" to frames)
        )
        
        assertTrue("Score should be boosted. Old: 0.01, New: ${result[0].rrfScore}", result[0].rrfScore > 0.01)
        assertTrue(result[0].matchExplanation.contains("Max Frame Similarity"))
    }

    private fun createFrame(mediaId: String, timestampUs: Long, vectorPrefix: FloatArray): VideoFrameRepresentation {
        val fullVector = FloatArray(512)
        // Ensure prefix is normalized
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
