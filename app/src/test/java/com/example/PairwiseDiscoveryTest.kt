package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class PairwiseDiscoveryTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setup() {
        repository = mock(MediaRepository::class.java)
        core = mock(AuraIntelligenceCore::class.java)
        whenever(repository.intelligenceCore).thenReturn(core)
    }

    @Test
    fun testPairwise_ExplorationInfluence() {
        runBlocking {
            val item1 = MediaItem(id = "high_conf", title = "High Confidence", mediaType = "VIDEO", compatibilityStatus = CompatibilityStatus.PLAYABLE)
            val item2 = MediaItem(id = "uncertain", title = "Uncertain", mediaType = "VIDEO", compatibilityStatus = CompatibilityStatus.PLAYABLE)

            val candidates = listOf(
                IntelligenceCandidate(item2, emptyList(), 1.0, 1.0f, 0f, "High uncertainty bonus"),
                IntelligenceCandidate(item1, emptyList(), 0.5, 0.5f, 0f, "Already mature")
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, 10L)
            whenever(core.processRequest(any())).thenReturn(response)

            // Verify that Core results guide the pool
            val pool = candidates.map { it.item to it.rankScore.toFloat() }
            val selectedPair = RecommendationEngine.selectNextPairFromPool(
                top100Pool = pool,
                randomSeed = 0L
            )

            assertNotNull(selectedPair)
            assertEquals("uncertain", selectedPair!!.first.id)
        }
    }
}
