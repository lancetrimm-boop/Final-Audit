package com.example.data

import com.example.data.intelligence.AuraIntelligenceCore
import com.example.data.intelligence.RetrievalRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PairwiseCoverageTest {

    private lateinit var repository: MediaRepository
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        com.example.data.intelligence.IntelligenceCache.clear()
        repository = MediaRepository(testDispatcher)
        val mockRouter = mock<RetrievalRouter>()
        val core = AuraIntelligenceCore(repository, mockRouter, dispatcher = testDispatcher)
        repository.setIntelligenceCoreForTesting(core)
    }

    private fun createMediaItem(id: String): MediaItem {
        return MediaItem(
            id = id,
            title = "Item $id",
            mediaType = "PHOTO",
            year = 2024,
            duration = "",
            genre = "Media",
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )
    }

    @Test
    fun testPoolExceeds100Items() = runTest {
        // 1. Setup 150 items
        val allItems = (1..150).map { createMediaItem(it.toString()) }
        repository.setMediaItemsForTesting(allItems)

        // 2. Refresh pool
        val pool = RecommendationEngine.getTop100PairwiseCandidates(repository)

        // 3. Verify pool size is > 100
        assertTrue("Pool size should be > 100. Got: ${pool.size}", pool.size > 100)
        assertEquals(150, pool.size)
    }

    @Test
    fun testLowComparisonCountPrioritization() = runTest {
        // 1. Setup 10 items
        val allItems = (1..10).map { createMediaItem(it.toString()) }
        repository.setMediaItemsForTesting(allItems)

        // 2. Set high comparison count for first 5 items (Directly, to avoid automatic refresh logic in test)
        repeat(5) { i ->
            val id = (i + 1).toString()
            repository.setComparisonCountForTesting(id, 10)
        }

        // 3. Get candidates
        val candidates = RecommendationEngine.getTop100PairwiseCandidates(repository)
        
        // 4. Verify low-count items (6-10) are at the top
        val topIds = candidates.take(5).map { it.first.id }.toSet()
        val lowCountIds = (6..10).map { it.toString() }.toSet()
        assertEquals(lowCountIds, topIds)
    }

    @Test
    fun testSessionJitterRotation() = runTest {
        val allItems = (1..10).map { createMediaItem(it.toString()) }
        repository.setMediaItemsForTesting(allItems)

        // Same seed should produce same order
        val pool1 = RecommendationEngine.getTop100PairwiseCandidates(repository, seed = 123L)
        val pool2 = RecommendationEngine.getTop100PairwiseCandidates(repository, seed = 123L)
        assertEquals(pool1.map { it.first.id }, pool2.map { it.first.id })

        // Different seed should produce different order for equal counts
        val pool3 = RecommendationEngine.getTop100PairwiseCandidates(repository, seed = 456L)
        assertNotEquals(pool1.map { it.first.id }, pool3.map { it.first.id })
    }
}
