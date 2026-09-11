package com.example.data.semantic

import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class PersonalizationHybridSearchTest {

    @Test
    fun testPersonalizationDelegation() {
        runBlocking {
            val core: AuraIntelligenceCore = mock()
            val engine = DefaultHybridSearchEngine(core)
            
            val response = IntelligenceResponse("req", IntelligenceMode.SEARCH, emptyList(), latencyMs = 1L)
            whenever(core.processRequest(any())).thenReturn(response)
            
            engine.search(SearchRequest.Text("query"))
            
            // Verify core is called with SEARCH mode
            verify(core).processRequest(argThat { req -> 
                req.mode == IntelligenceMode.SEARCH 
            })
        }
    }
}
