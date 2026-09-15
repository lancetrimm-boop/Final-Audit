package com.example.ui.screens

import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.entitlement.EntitlementRepository
import com.example.data.entitlement.ProFeature
import com.example.data.cleanup.CleanupCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CleanupReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: MediaRepository
    private lateinit var entitlementRepository: EntitlementRepository
    private lateinit var viewModel: CleanupReviewViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = MediaRepository(testDispatcher)
        entitlementRepository = mock()
        
        // Default to Pro
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)
        
        // Mock media items
        val items = listOf(
            MediaItem(id = "1", title = "Forgotten", mediaType = "PHOTO", exposureCount = 50, viewCount = 0, sizeBytes = 5000L)
        )
        repository.setMediaItemsForTesting(items)
        
        viewModel = CleanupReviewViewModel(repository, entitlementRepository, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testViewModel_InitialLoad_GeneratesRecommendations() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isLocked)
        assertEquals(1, state.recommendations.size)
        assertEquals("1", state.recommendations[0].mediaId)
    }

    @Test
    fun testViewModel_NonPro_LockedState() = runTest {
        // Given a non-pro user
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(false)
        
        // When recommendations are loaded
        viewModel.loadRecommendations()
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Then state is locked
        val state = viewModel.uiState.value
        assertTrue(state.isLocked)
        assertFalse(state.isLoading)
    }
}
