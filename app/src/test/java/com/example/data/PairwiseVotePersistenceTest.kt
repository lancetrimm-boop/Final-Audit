package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AuraDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PairwiseVotePersistenceTest {

    private lateinit var repository: MediaRepository
    private lateinit var database: AuraDatabase
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        repository = MediaRepository(testDispatcher)
        repository.setDatabaseForTesting(database)
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
    fun testVotePersistenceAndSequencing() = runTest {
        val itemA = createMediaItem("A")
        val itemB = createMediaItem("B")
        repository.setMediaItemsForTesting(listOf(itemA, itemB))
        
        // 1. Trigger initial pair selection
        repository.refreshPairwiseCandidatePoolAndSelectNext(forceNextPair = true)
        
        val initialPair = repository.pairwiseState.value
        assertEquals("A", initialPair.optionA.id)
        assertEquals("B", initialPair.optionB.id)

        // 2. Cast vote
        repository.recordComparisonVote("A")
        
        // 3. Verify persistence
        val outcomes = database.pairwiseDao().getAllOutcomes().filter { it.isNotEmpty() }.first()
        assertEquals(1, outcomes.size)
        assertEquals("A", outcomes[0].chosenId)
        assertEquals("A", outcomes[0].optionAId)
        assertEquals("B", outcomes[0].optionBId)

        // 4. Verify counts updated
        val counts = repository.getComparisonCounts()
        assertEquals(1, counts["A"])
        assertEquals(1, counts["B"])
    }

    @Test
    fun testPersistenceFailure_DoesNotAdvanceAndDoesNotIncrementCount() = runTest {
        val items = (1..6).map { createMediaItem(it.toString()) }
        repository.setMediaItemsForTesting(items)
        repository.startCompareSelectionSession(setOf("1", "2", "3", "4", "5", "6"))

        val initialPair = repository.pairwiseState.value
        val initialRound = repository.compareSelectionSession.value.roundNumber
        assertEquals(1, initialRound)

        // Close database to simulate persistence failure
        database.close()

        // Attempt to vote
        repository.recordComparisonVote(initialPair.optionA.id)

        // Verify session did not advance and counts did not increment
        val counts = repository.getComparisonCounts()
        assertEquals(0, counts[initialPair.optionA.id] ?: 0)
        assertEquals(0, counts[initialPair.optionB.id] ?: 0)
        assertEquals(1, repository.compareSelectionSession.value.roundNumber)
    }
}
