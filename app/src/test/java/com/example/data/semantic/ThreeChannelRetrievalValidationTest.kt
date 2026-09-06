package com.example.data.semantic

import com.example.data.intelligence.*
import com.example.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ThreeChannelRetrievalValidationTest {

    // =========================================================================
    // 1. RRF MATHEMATICS & WEIGHT VALIDATION (Moved to RetrievalFusion)
    // =========================================================================

    @Test
    fun testRetrievalFusion_ThreeChannelOverlap() {
        val lexicalItems = listOf(
            RankedChannelItem("Media_A", 0.9f, 1),
            RankedChannelItem("Media_B", 0.8f, 2),
            RankedChannelItem("Media_C", 0.7f, 3)
        )
        val minilmItems = listOf(
            RankedChannelItem("Media_B", 0.85f, 1),
            RankedChannelItem("Media_C", 0.75f, 2),
            RankedChannelItem("Media_D", 0.65f, 3)
        )
        val clipItems = listOf(
            RankedChannelItem("Media_C", 0.95f, 1),
            RankedChannelItem("Media_D", 0.85f, 2),
            RankedChannelItem("Media_A", 0.75f, 3)
        )

        val channelMap = mapOf(
            SearchChannel.KEYWORD to lexicalItems,
            SearchChannel.SEMANTIC_CONTENT to minilmItems,
            SearchChannel.SEMANTIC_VISUAL to clipItems
        )

        val config = HybridSearchConfig(
            rrfConstantK = 60,
            channelWeights = mapOf(
                SearchChannel.KEYWORD to 0.4,
                SearchChannel.SEMANTIC_CONTENT to 0.4,
                SearchChannel.SEMANTIC_VISUAL to 0.2
            )
        )

        val fused = RetrievalFusion.fuse(channelMap, config)

        // Media_C should be #1 due to presence in all channels with high ranks
        assertEquals("Media_C", fused[0].mediaId)
        assertEquals(3, fused[0].channelRanks.size)
        
        // Media_B should be #2
        assertEquals("Media_B", fused[1].mediaId)
        assertEquals(2, fused[1].channelRanks.size)
        
        assertTrue(fused[0].rrfScore > fused[1].rrfScore)
    }

    // =========================================================================
    // 2. CORE DELEGATION VALIDATION
    // =========================================================================

    @Test
    fun testHybridSearchEngine_EndToEndDelegation() {
        runBlocking {
            val core: AuraIntelligenceCore = mock()
            val engine = DefaultHybridSearchEngine(core)
            
            val item = MediaItem(id = "media_1", title = "Test", mediaType = "PHOTO")
            val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Match"))
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, 10L)
            
            whenever(core.processRequest(any())).thenReturn(response)
            
            val result = engine.search(SearchRequest.Text("query"))
            
            assertTrue(result.isSuccess)
            assertEquals(1, result.candidates.size)
            assertEquals("media_1", result.candidates[0].mediaId)
            verify(core).processRequest(argThat { req -> 
                req.mode == IntelligenceMode.SEARCH && req.query == "query" 
            })
        }
    }
}
