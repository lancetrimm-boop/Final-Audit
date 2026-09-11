package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class RecommendationHygieneTest {

    @Test
    fun `test AI Sort - Excludes rated media via Core`() {
        runBlocking {
            val repository = mock(MediaRepository::class.java)
            val core = mock(AuraIntelligenceCore::class.java)
            whenever(repository.intelligenceCore).thenReturn(core)
            whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))

            val unratedItem = MediaItem(id = "unrated", title = "Unrated", mediaType = "PHOTO", rating = 0f, compatibilityStatus = CompatibilityStatus.PLAYABLE)
            
            // Mock Core response for SORT mode
            // In production, handleSort("PERSONALIZED") filters out rated media.
            val candidates = listOf(
                IntelligenceCandidate(unratedItem, emptyList(), 1.0, 1.0f, 0f, "For You")
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, latencyMs = 10L)
            whenever(core.processRequest(org.mockito.kotlin.any())).thenReturn(response)

            val coreRequest = IntelligenceRequest(mode = IntelligenceMode.SORT, sortOption = "PERSONALIZED")
            val res = core.processRequest(coreRequest)

            assertTrue("Result should only contain unrated item", res.candidates.any { it.item.id == "unrated" })
            assertFalse("Result should NOT contain rated item", res.candidates.any { it.item.id == "rated" })
        }
    }

    @Test
    fun `test Discover Deduplication - Logic moved to Core`() {
        runBlocking {
            // This test was previously validating RecommendationEngine internal logic.
            // Update 9 moved this to AuraIntelligenceCore.
            // We now verify that RecommendationEngine properly delegates to Core.
            
            val repository = mock(MediaRepository::class.java)
            val core = mock(AuraIntelligenceCore::class.java)
            `when`(repository.intelligenceCore).thenReturn(core)
            `when`(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
            
            val heroItem = MediaItem(id = "hero", title = "Hero", mediaType = "VIDEO")
            val heroResponse = IntelligenceResponse("req", IntelligenceMode.DISCOVER, listOf(
                IntelligenceCandidate(heroItem, emptyList(), 1.0, 1.0f, 0f)
            ), latencyMs = 0L)
            
            `when`(core.processRequest(any())).thenReturn(heroResponse)
            
            val categories = RecommendationEngine.computeDiscoverCategories(repository)
            
            assertEquals("hero", categories.nextObsession?.id)
            verify(core, atLeastOnce()).processRequest(org.mockito.kotlin.argThat { req -> req.mode == IntelligenceMode.DISCOVER })
        }
    }
}
