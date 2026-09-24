package com.example.ui.screens

import com.example.data.DatabaseState
import com.example.data.IntelligenceStats
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.TasteDNA
import com.example.data.cleanup.SafeDeleteManager
import com.example.data.entitlement.EntitlementRepository
import com.example.data.entitlement.ProFeature
import com.example.data.entitlement.ProState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupStartupRaceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCleanupIntelligenceViewModel_StartupRace_StaysLoadingWhileInitializing() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()

        val dbStateFlow = MutableStateFlow(DatabaseState.INITIALIZING)
        val mediaItemsFlow = MutableStateFlow<List<MediaItem>>(emptyList())
        val proStateFlow = MutableStateFlow<ProState>(ProState.Pro())

        whenever(mockRepo.databaseState).thenReturn(dbStateFlow)
        whenever(mockRepo.mediaItems).thenReturn(mediaItemsFlow)
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockEntitlementRepo.proState).thenReturn(proStateFlow)
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupIntelligenceViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        // 1. While database is INITIALIZING, UI state must remain isLoading = true and NOT publish false healthy state
        assertTrue("Expected isLoading = true while database is initializing", viewModel.uiState.value.isLoading)

        // 2. Media items loaded, then Database transitions to READY
        mediaItemsFlow.value = listOf(
            MediaItem(id = "m1", uriPath = "uri1", imageUrl = "", title = "Item 1", mediaType = "VIDEO", sizeBytes = 500 * 1024 * 1024L)
        )
        dbStateFlow.value = DatabaseState.READY
        testScheduler.advanceUntilIdle()

        // 3. Analysis runs and completes after databaseState becomes READY
        assertFalse("Expected isLoading = false after database becomes READY", viewModel.uiState.value.isLoading)
    }

    @Test
    fun testCleanupReviewViewModel_StartupRace_StaysLoadingWhileInitializing() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()
        val mockDeleteManager: SafeDeleteManager = mock()

        val dbStateFlow = MutableStateFlow(DatabaseState.INITIALIZING)
        val mediaItemsFlow = MutableStateFlow<List<MediaItem>>(emptyList())
        val proStateFlow = MutableStateFlow<ProState>(ProState.Pro())

        whenever(mockRepo.databaseState).thenReturn(dbStateFlow)
        whenever(mockRepo.mediaItems).thenReturn(mediaItemsFlow)
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.safeDeleteManager).thenReturn(mockDeleteManager)
        whenever(mockDeleteManager.deletionState).thenReturn(MutableStateFlow(com.example.data.cleanup.DeletionState.IDLE))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockEntitlementRepo.proState).thenReturn(proStateFlow)
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupReviewViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        // 1. While database is INITIALIZING, UI state must remain isLoading = true
        assertTrue("Expected isLoading = true while database is initializing", viewModel.uiState.value.isLoading)

        // 2. Media items loaded, then Database transitions to READY
        mediaItemsFlow.value = listOf(
            MediaItem(id = "m1", uriPath = "uri1", imageUrl = "", title = "Item 1", mediaType = "VIDEO", sizeBytes = 500 * 1024 * 1024L)
        )
        dbStateFlow.value = DatabaseState.READY
        testScheduler.advanceUntilIdle()

        // 3. Recommendations load after databaseState becomes READY
        assertFalse("Expected isLoading = false after database becomes READY", viewModel.uiState.value.isLoading)
    }
}
