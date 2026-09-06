package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class SeeSimilarTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setUp() {
        repository = mock(MediaRepository::class.java)
        core = mock(AuraIntelligenceCore::class.java)
        whenever(repository.intelligenceCore).thenReturn(core)
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
    }

    @Test
    fun testSeeSimilar_DelegatesToCore() {
        runTest {
            val refItem = MediaItem(
                id = "ref_1",
                title = "Cinematic Sunset",
                mediaType = "VIDEO",
                compatibilityStatus = CompatibilityStatus.PLAYABLE
            )
            val candItem = MediaItem(id = "cand_1", title = "Similar", mediaType = "VIDEO", compatibilityStatus = CompatibilityStatus.PLAYABLE)

            whenever(repository.getMediaItemById("ref_1")).thenReturn(refItem)
            
            val candidates = listOf(
                IntelligenceCandidate(candItem, emptyList(), 1.0, 1.0f, 0f, "Visual Match")
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SIMILAR, candidates, 10L)
            whenever(core.processRequest(any())).thenReturn(response)

            val results = repository.getSimilarMedia(refItem)
            
            assertFalse("Results should not contain the reference item", results.any { it.id == refItem.id })
            assertEquals(1, results.size)
            assertEquals("cand_1", results[0].id)
            
            verify(core).processRequest(org.mockito.kotlin.argThat { req -> 
                req.mode == IntelligenceMode.SIMILAR && req.referenceItemId == "ref_1" 
            })
        }
    }
}
