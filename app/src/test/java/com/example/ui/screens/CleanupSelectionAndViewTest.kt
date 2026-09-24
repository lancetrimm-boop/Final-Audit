package com.example.ui.screens

import com.example.data.DatabaseState
import com.example.data.IntelligenceStats
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.TasteDNA
import com.example.data.cleanup.CleanupCategory
import com.example.data.cleanup.CleanupItemMetadata
import com.example.data.cleanup.CleanupReason
import com.example.data.cleanup.CleanupRecommendationEngine
import com.example.data.cleanup.KeepScoreEngine
import com.example.data.cleanup.KeepScoreInput
import com.example.data.cleanup.KeepScoreResult
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
class CleanupSelectionAndViewTest {

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
    fun keeping_one_item_does_not_select_keep_all() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()
        val mockDeleteManager: SafeDeleteManager = mock()

        val items = listOf(
            MediaItem(id = "item_a", uriPath = "path_a", imageUrl = "", title = "Item A", mediaType = "VIDEO", sizeBytes = 200 * 1024 * 1024L),
            MediaItem(id = "item_b", uriPath = "path_b", imageUrl = "", title = "Item B", mediaType = "VIDEO", sizeBytes = 300 * 1024 * 1024L),
            MediaItem(id = "item_c", uriPath = "path_c", imageUrl = "", title = "Item C", mediaType = "VIDEO", sizeBytes = 400 * 1024 * 1024L)
        )

        whenever(mockRepo.databaseState).thenReturn(MutableStateFlow(DatabaseState.READY))
        whenever(mockRepo.mediaItems).thenReturn(MutableStateFlow(items))
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.safeDeleteManager).thenReturn(mockDeleteManager)
        whenever(mockDeleteManager.deletionState).thenReturn(MutableStateFlow(com.example.data.cleanup.DeletionState.IDLE))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockRepo.getMediaItemById("item_a")).thenReturn(items[0])
        whenever(mockEntitlementRepo.proState).thenReturn(MutableStateFlow(ProState.Pro()))
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupReviewViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        // Verify initial state: recommendations exist, but selectedIds is empty (Keep All NOT active)
        val initialSelected = viewModel.uiState.value.selectedIds
        assertTrue("Initial selection should be empty", initialSelected.isEmpty())

        // User keeps item A
        viewModel.keepItem("item_a")
        testScheduler.advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertFalse("Item A should be removed from recommendations", updatedState.recommendations.any { it.mediaId == "item_a" })
        assertFalse("Keep All should NOT be active and selectedIds should remain empty or unaffected for B/C", updatedState.selectedIds.contains("item_b"))
        assertFalse("Keep All should NOT be active for item C", updatedState.selectedIds.contains("item_c"))
        verify(mockDeleteManager).markAsKept(eq(items[0]), any())
    }

    @Test
    fun explicit_keep_all_selects_intended_items() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()
        val mockDeleteManager: SafeDeleteManager = mock()

        val items = listOf(
            MediaItem(id = "item_a", uriPath = "path_a", imageUrl = "", title = "Item A", mediaType = "VIDEO", sizeBytes = 200 * 1024 * 1024L),
            MediaItem(id = "item_b", uriPath = "path_b", imageUrl = "", title = "Item B", mediaType = "VIDEO", sizeBytes = 300 * 1024 * 1024L)
        )

        whenever(mockRepo.databaseState).thenReturn(MutableStateFlow(DatabaseState.READY))
        whenever(mockRepo.mediaItems).thenReturn(MutableStateFlow(items))
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.safeDeleteManager).thenReturn(mockDeleteManager)
        whenever(mockDeleteManager.deletionState).thenReturn(MutableStateFlow(com.example.data.cleanup.DeletionState.IDLE))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockRepo.getMediaItemById("item_a")).thenReturn(items[0])
        whenever(mockRepo.getMediaItemById("item_b")).thenReturn(items[1])
        whenever(mockEntitlementRepo.proState).thenReturn(MutableStateFlow(ProState.Pro()))
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupReviewViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        viewModel.selectCategory(CleanupCategory.SPACE_HOGS)
        testScheduler.advanceUntilIdle()

        // User explicitly selects all items in category
        viewModel.selectAllInCategory()
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.selectedIds.size)

        // User explicitly invokes Keep All (keepSelected)
        viewModel.keepSelected()
        testScheduler.advanceUntilIdle()

        verify(mockDeleteManager).markAsKept(eq(items[0]), any())
        verify(mockDeleteManager).markAsKept(eq(items[1]), any())
        assertTrue("Selection should be cleared after Keep All", viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun deleting_one_item_does_not_change_other_decisions() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()
        val mockDeleteManager: SafeDeleteManager = mock()

        val items = listOf(
            MediaItem(id = "item_a", uriPath = "path_a", imageUrl = "", title = "Item A", mediaType = "VIDEO", sizeBytes = 200 * 1024 * 1024L),
            MediaItem(id = "item_b", uriPath = "path_b", imageUrl = "", title = "Item B", mediaType = "VIDEO", sizeBytes = 300 * 1024 * 1024L)
        )

        whenever(mockRepo.databaseState).thenReturn(MutableStateFlow(DatabaseState.READY))
        whenever(mockRepo.mediaItems).thenReturn(MutableStateFlow(items))
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.safeDeleteManager).thenReturn(mockDeleteManager)
        whenever(mockDeleteManager.deletionState).thenReturn(MutableStateFlow(com.example.data.cleanup.DeletionState.IDLE))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockEntitlementRepo.proState).thenReturn(MutableStateFlow(ProState.Pro()))
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupReviewViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        viewModel.selectCategory(CleanupCategory.SPACE_HOGS)
        testScheduler.advanceUntilIdle()

        // Toggling selection on item A
        viewModel.toggleSelection("item_a")
        testScheduler.advanceUntilIdle()

        assertTrue("Item A should be selected", viewModel.uiState.value.selectedIds.contains("item_a"))
        assertFalse("Item B should remain unselected", viewModel.uiState.value.selectedIds.contains("item_b"))
    }

    @Test
    fun view_action_routes_without_mutating_decision_state() = runTest(testDispatcher) {
        val mockRepo: MediaRepository = mock()
        val mockEntitlementRepo: EntitlementRepository = mock()
        val mockDeleteManager: SafeDeleteManager = mock()

        val items = listOf(
            MediaItem(id = "item_a", uriPath = "path_a", imageUrl = "", title = "Item A", mediaType = "VIDEO", sizeBytes = 200 * 1024 * 1024L),
            MediaItem(id = "item_b", uriPath = "path_b", imageUrl = "", title = "Item B", mediaType = "PHOTO", sizeBytes = 5 * 1024 * 1024L)
        )

        whenever(mockRepo.databaseState).thenReturn(MutableStateFlow(DatabaseState.READY))
        whenever(mockRepo.mediaItems).thenReturn(MutableStateFlow(items))
        whenever(mockRepo.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(mockRepo.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(mockRepo.safeDeleteManager).thenReturn(mockDeleteManager)
        whenever(mockDeleteManager.deletionState).thenReturn(MutableStateFlow(com.example.data.cleanup.DeletionState.IDLE))
        whenever(mockRepo.getSkipCounts(any())).thenReturn(emptyMap())
        whenever(mockRepo.getContentHashFrequencies()).thenReturn(emptyList())
        whenever(mockEntitlementRepo.proState).thenReturn(MutableStateFlow(ProState.Pro()))
        whenever(mockEntitlementRepo.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)

        val viewModel = CleanupReviewViewModel(mockRepo, mockEntitlementRepo, testDispatcher)
        testScheduler.advanceUntilIdle()

        val initialSelected = viewModel.uiState.value.selectedIds
        val initialRecsCount = viewModel.uiState.value.recommendations.size

        // Simulate View Media callback firing (calling repository.setPlaylist)
        mockRepo.setPlaylist(listOf(items[0]), 0, "Smart Cleanup Review")
        testScheduler.advanceUntilIdle()

        // Verify setPlaylist was called with item_a
        verify(mockRepo).setPlaylist(eq(listOf(items[0])), eq(0), eq("Smart Cleanup Review"))

        // Verify ViewModel decision state was NOT mutated
        assertEquals("Selection should remain unchanged after viewing media", initialSelected, viewModel.uiState.value.selectedIds)
        assertEquals("Recommendations count should remain unchanged after viewing media", initialRecsCount, viewModel.uiState.value.recommendations.size)
        verify(mockDeleteManager, never()).markAsKept(any(), any())
    }

    @Test
    fun never_played_item_is_not_negative_evidence_by_itself() {
        val unplayedInput = KeepScoreInput(
            mediaId = "long_compilation",
            fileSize = 450 * 1024 * 1024L,
            dateAdded = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000L), // 30 days old
            exposureCount = 0,
            lastExposedTimestamp = null,
            viewCount = 0,
            playCount = 0,
            averageWatchDuration = 0f,
            completionPercentage = 0f,
            skipCount = 0,
            rating = 0f,
            isFavorite = false,
            tasteAlignmentScore = 0.50f,
            rarityScore = 1.0f,
            contentHash = "unique_hash"
        )

        val result = KeepScoreEngine.calculateScore(unplayedInput)

        // Engagement baseline is neutral (0.50f), so Keep Score is ~0.45+ (not penalized to 7%)
        assertTrue("Never played item should have a neutral Keep Score >= 0.40f, was ${result.keepScore}", result.keepScore >= 0.40f)
        assertFalse("Never played item should not be flagged with HIGH_EXPOSURE_NO_ENGAGEMENT", result.reasons.contains(CleanupReason.HIGH_EXPOSURE_NO_ENGAGEMENT))
    }

    @Test
    fun repeated_skips_and_bad_feedback_remain_negative_evidence() {
        val skippedInput = KeepScoreInput(
            mediaId = "skipped_item",
            fileSize = 50 * 1024 * 1024L,
            dateAdded = System.currentTimeMillis(),
            exposureCount = 10,
            lastExposedTimestamp = System.currentTimeMillis(),
            viewCount = 5,
            playCount = 5,
            averageWatchDuration = 1.0f, // Instant skip < 2s
            completionPercentage = 0.05f,
            skipCount = 4, // Repeated skips >= 3
            rating = 1.5f, // Low rating <= 2.0
            isFavorite = false,
            tasteAlignmentScore = 0.10f,
            rarityScore = 1.0f,
            contentHash = "hash1"
        )

        val result = KeepScoreEngine.calculateScore(skippedInput)

        assertTrue("Repeated skips and low rating should produce low Keep Score < 0.40f", result.keepScore < 0.40f)
        assertTrue("Should include REPEATED_SKIP reason", result.reasons.contains(CleanupReason.REPEATED_SKIP))
        assertTrue("Should include LOW_USER_RATING reason", result.reasons.contains(CleanupReason.LOW_USER_RATING))
    }

    @Test
    fun independent_space_hog_reason_remains_valid_for_unplayed_media() {
        val largeUnplayedInput = KeepScoreInput(
            mediaId = "huge_file",
            fileSize = 500 * 1024 * 1024L, // 500MB
            dateAdded = System.currentTimeMillis() - (400L * 24 * 60 * 60 * 1000L), // > 1 year old
            exposureCount = 0,
            lastExposedTimestamp = null,
            viewCount = 0,
            playCount = 0,
            averageWatchDuration = 0f,
            completionPercentage = 0f,
            skipCount = 0,
            rating = 0f,
            isFavorite = false,
            tasteAlignmentScore = 0.10f, // Low taste match
            rarityScore = 1.0f,
            contentHash = null
        )

        val result = KeepScoreEngine.calculateScore(largeUnplayedInput)
        val metadata = mapOf("huge_file" to CleanupItemMetadata("huge_file", "Huge File", 500 * 1024 * 1024L, 0, 0, "VIDEO", null, false, 1920, 1080, 0, 1000L))
        val recs = CleanupRecommendationEngine.generateRecommendations(listOf(result), metadata)
        
        // Storage impact remains categorized as SPACE_HOGS due to >100MB size and low taste match
        assertEquals(1, recs.size)
        assertEquals(CleanupCategory.SPACE_HOGS, recs[0].category)
        assertTrue("Should list LARGE_FILE_SIZE as reason", recs[0].reasons.contains(CleanupReason.LARGE_FILE_SIZE))
    }

    @Test
    fun duplicate_reason_remains_independent_of_playback_history() {
        val meta1 = CleanupItemMetadata("dup1", "Duplicate 1", 50 * 1024 * 1024L, 0, 0, "PHOTO", "hash_abc", false, 1920, 1080, 0, 1000L)
        val meta2 = CleanupItemMetadata("dup2", "Duplicate 2", 50 * 1024 * 1024L, 0, 0, "PHOTO", "hash_abc", false, 1920, 1080, 0, 1000L)

        val res1 = KeepScoreResult("dup1", 0.60f, 0.5f, emptyList(), CleanupCategory.NONE)
        val res2 = KeepScoreResult("dup2", 0.50f, 0.5f, emptyList(), CleanupCategory.NONE)

        val recommendations = CleanupRecommendationEngine.generateRecommendations(
            listOf(res1, res2),
            mapOf("dup1" to meta1, "dup2" to meta2)
        )

        assertEquals("Should produce 1 REDUNDANT recommendation for duplicate candidate", 1, recommendations.size)
        assertEquals("dup2", recommendations[0].mediaId)
        assertEquals(CleanupCategory.REDUNDANT, recommendations[0].category)
        assertTrue(recommendations[0].reasons.contains(CleanupReason.DUPLICATE_CONTENT))
    }

    @Test
    fun consumer_cleanup_card_displays_keep_score_without_confidence() {
        val res = KeepScoreResult("item_1", 0.25f, 0.88f, listOf(CleanupReason.LARGE_FILE_SIZE), CleanupCategory.SPACE_HOGS)
        val meta = CleanupItemMetadata("item_1", "Title", 200 * 1024 * 1024L, 0, 0, "VIDEO", null, false, 1920, 1080, 120000L, 1000L)

        val recommendation = CleanupRecommendationEngine.generateRecommendations(listOf(res), mapOf("item_1" to meta))[0]

        // Keep Score is primary metric for consumer card, while confidenceScore is preserved in the model
        assertEquals(0.25f, recommendation.keepScore, 0.01f)
        assertEquals(0.60f, recommendation.confidenceScore, 0.01f)
    }
}
