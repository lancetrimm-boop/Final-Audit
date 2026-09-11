package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class PairwiseSelectionTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setup() {
        repository = mock(MediaRepository::class.java)
        core = mock(AuraIntelligenceCore::class.java)
        whenever(repository.intelligenceCore).thenReturn(core)
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(repository.preferenceProfile).thenReturn(MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(MutableStateFlow(emptyMap()))
    }

    private fun createDummyMedia(
        id: String,
        title: String,
        rating: Float = 0f,
        isFavorite: Boolean = false,
        mediaType: String = "PHOTO"
    ): MediaItem {
        return MediaItem(
            id = id,
            title = title,
            mediaType = mediaType,
            rating = rating,
            isFavorite = isFavorite,
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )
    }

    @Test
    fun testPairwiseSelection_DelegatesToCore() {
        runBlocking {
            val item1 = createDummyMedia("m1", "Item 1")
            val item2 = createDummyMedia("m2", "Item 2")
            
            // Mock Core response for pool generation
            val candidates = listOf(
                IntelligenceCandidate(item1, emptyList(), 1.0, 1.0f, 0f),
                IntelligenceCandidate(item2, emptyList(), 0.9, 0.9f, 0f)
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, latencyMs = 10L)
            whenever(core.processRequest(any())).thenReturn(response)

            // We test the selectNextPairFromPool which still lives in RecommendationEngine
            // but takes the pool produced by Core (in production via MediaRepository)
            val pool = candidates.map { it.item to it.rankScore.toFloat() }
            val nextPair = RecommendationEngine.selectNextPairFromPool(
                top100Pool = pool,
                randomSeed = 42L
            )

            assertNotNull(nextPair)
            assertEquals("m1", nextPair!!.first.id)
            assertEquals("m2", nextPair.second.id)
        }
    }

    @Test
    fun testPairwise_DiversityConstraints() {
        val item1 = createDummyMedia("m1", "Item 1", rating = 3.0f)
        val item2 = createDummyMedia("m2", "Item 2", rating = 3.0f)
        val item3 = createDummyMedia("m3", "Item 3", rating = 3.0f)

        val pool = listOf(
            item1 to 10f,
            item2 to 10f,
            item3 to 10f
        )

        // Mock recent repetition
        val recentPairs = listOf("m1" to "m2")
        val nextPair = RecommendationEngine.selectNextPairFromPool(
            top100Pool = pool,
            recentPairs = recentPairs,
            randomSeed = 42L
        )

        assertNotNull(nextPair)
        val ids = setOf(nextPair!!.first.id, nextPair.second.id)
        // Should not be m1 vs m2
        assertFalse(ids.contains("m1") && ids.contains("m2"))
    }
}
