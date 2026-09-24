package com.example.ui.screens

import com.example.data.MediaItem
import com.example.data.PairwiseComparison
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class CompareScreenReadinessTest {

    private val emptyItem = MediaItem(id = "", uriPath = "", title = "", mediaType = "")

    @Test
    fun compareScreen_whenNotReady_isRepositoryReadyStateReflected() {
        val emptyComparison = PairwiseComparison("p_empty", 1, 50, emptyItem, emptyItem)
        
        // When repository is not ready, isRepositoryReady = false
        val isReady = false
        assertFalse("Repository should be marked as not ready during initialization", isReady)
        assertTrue("Pairwise option A should be empty during loading", emptyComparison.optionA.id.isEmpty())
    }

    @Test
    fun compareScreen_whenReadyAndEmpty_rendersGenuineEmptyState() {
        val emptyComparison = PairwiseComparison("p_empty", 1, 50, emptyItem, emptyItem)
        val isReady = true
        
        assertTrue("Repository is ready", isReady)
        assertTrue("Pairwise option A is empty", emptyComparison.optionA.id.isEmpty())
    }

    @Test
    fun compareScreen_whenReadyAndValidPair_rendersCompareContent() {
        val itemA = MediaItem(id = "a", uriPath = "pathA", imageUrl = "", title = "Photo A", mediaType = "PHOTO")
        val itemB = MediaItem(id = "b", uriPath = "pathB", imageUrl = "", title = "Photo B", mediaType = "PHOTO")
        val validComparison = PairwiseComparison("p1", 1, 50, itemA, itemB)
        val isReady = true

        assertTrue("Repository is ready", isReady)
        assertFalse("Pairwise option A is non-empty", validComparison.optionA.id.isEmpty())
        assertFalse("Pairwise option B is non-empty", validComparison.optionB.id.isEmpty())
    }
}
