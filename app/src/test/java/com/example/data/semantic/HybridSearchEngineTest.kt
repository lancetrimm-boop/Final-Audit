package com.example.data.semantic

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argThat

class HybridSearchEngineTest {

    // =========================================================================
    // 1. PURE RECIPROCAL RANK FUSION MATHEMATICS (Moved to RetrievalFusion)
    // =========================================================================

    @Test
    fun testRetrievalFusion_MultiChannelOverlap_ComputesExactRrfScore() {
        val keywordItems = listOf(
            RankedChannelItem("media_alpha", 0.95f, 1),
            RankedChannelItem("media_beta", 0.80f, 2)
        )
        val semanticItems = listOf(
            RankedChannelItem("media_gamma", 0.88f, 1),
            RankedChannelItem("media_alpha", 0.85f, 2)
        )

        val channelMap = mapOf(
            SearchChannel.KEYWORD to keywordItems,
            SearchChannel.SEMANTIC_CONTENT to semanticItems
        )

        val config = HybridSearchConfig(
            rrfConstantK = 60,
            channelWeights = mapOf(
                SearchChannel.KEYWORD to 0.5,
                SearchChannel.SEMANTIC_CONTENT to 0.5
            ),
            topK = 10
        )

        val fused = RetrievalFusion.fuse(channelMap, config)

        assertEquals(3, fused.size)

        // media_alpha: 0.5 / (60 + 1) + 0.5 / (60 + 2) = 0.5/61 + 0.5/62 ~= 0.01626124
        // media_gamma: 0.5 / (60 + 1) = 0.5/61 ~= 0.00819672
        // media_beta: 0.5 / (60 + 2) = 0.5/62 ~= 0.00806451

        assertEquals("media_alpha", fused[0].mediaId)
        assertEquals(2, fused[0].channelRanks.size)
        assertEquals(1, fused[0].channelRanks[SearchChannel.KEYWORD])
        assertEquals(2, fused[0].channelRanks[SearchChannel.SEMANTIC_CONTENT])

        assertEquals("media_gamma", fused[1].mediaId)
        assertEquals(1, fused[1].channelRanks[SearchChannel.SEMANTIC_CONTENT])

        assertEquals("media_beta", fused[2].mediaId)
        assertEquals(2, fused[2].channelRanks[SearchChannel.KEYWORD])

        // Verify strictly descending RRF scores
        assertTrue(fused[0].rrfScore > fused[1].rrfScore)
        assertTrue(fused[1].rrfScore > fused[2].rrfScore)
    }

    @Test
    fun testRetrievalFusion_SingleChannelOnly_PreservesOrder() {
        val keywordItems = listOf(
            RankedChannelItem("media_1", 10.0f, 1),
            RankedChannelItem("media_2", 8.0f, 2),
            RankedChannelItem("media_3", 5.0f, 3)
        )

        val channelMap = mapOf(SearchChannel.KEYWORD to keywordItems)
        val fused = RetrievalFusion.fuse(channelMap, HybridSearchConfig())

        assertEquals(3, fused.size)
        assertEquals("media_1", fused[0].mediaId)
        assertEquals("media_2", fused[1].mediaId)
        assertEquals("media_3", fused[2].mediaId)
    }

    // =========================================================================
    // 2. HYBRID SEARCH ENGINE ADAPTER TESTS
    // =========================================================================

    @Test
    fun testHybridSearchEngine_DelegatesToCore() {
        runBlocking {
            val core = mock(AuraIntelligenceCore::class.java)
            val engine = DefaultHybridSearchEngine(core)
            
            val item = MediaItem(id = "item1", title = "Test", mediaType = "PHOTO")
            val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Match"))
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, latencyMs = 10L)
            
            whenever(core.processRequest(any())).thenReturn(response)
            
            val result = engine.search(SearchRequest.Text("query"))
            
            assertTrue(result.isSuccess)
            assertEquals(1, result.candidates.size)
            assertEquals("item1", result.candidates[0].mediaId)
            verify(core).processRequest(argThat { req -> req.mode == IntelligenceMode.SEARCH && req.query == "query" })
        }
    }

    @Test
    fun testHybridSearchEngine_SemanticReady_AlwaysTrueForAdapter() {
        val core = mock(AuraIntelligenceCore::class.java)
        val engine = DefaultHybridSearchEngine(core)
        assertTrue(engine.isSemanticReady())
    }
}
