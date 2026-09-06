package com.example.data.semantic

import com.example.data.intelligence.*
import com.example.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class MobileCLIPHybridSearchTest {

    @Test
    fun testThreeChannelFusion_DelegatesToCore() {
        runBlocking {
            val core: AuraIntelligenceCore = mock()
            val engine = DefaultHybridSearchEngine(core)
            
            val item = MediaItem(id = "media_shared", title = "Shared Match", mediaType = "PHOTO")
            val candidates = listOf(IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f, "Fused Match"))
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, candidates, 5L)
            
            whenever(core.processRequest(any())).thenReturn(response)

            val result = engine.search("sunset query", HybridSearchConfig())

            assertTrue(result.isSuccess)
            assertEquals("media_shared", result.candidates[0].mediaId)
            verify(core).processRequest(argThat { req -> 
                req.mode == IntelligenceMode.SEARCH && req.query == "sunset query" 
            })
        }
    }

    @Test
    fun testVisualEmpty_GracefulCoreResponse() {
        runBlocking {
            val core: AuraIntelligenceCore = mock()
            val engine = DefaultHybridSearchEngine(core)
            
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, emptyList(), 2L)
            whenever(core.processRequest(any())).thenReturn(response)

            val result = engine.search("query", HybridSearchConfig())

            assertTrue(result.isSuccess)
            assertTrue(result.candidates.isEmpty())
        }
    }
}
