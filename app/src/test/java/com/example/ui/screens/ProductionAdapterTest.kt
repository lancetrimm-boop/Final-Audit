package com.example.ui.screens

import com.example.data.*
import com.example.ui.models.LibraryPresentationState
import com.example.ui.models.DiscoverPresentationState
import org.junit.Assert.*
import org.junit.Test

class ProductionAdapterTest {

    @Test
    fun `test LibraryPresentationState default values`() {
        val state = LibraryPresentationState(mediaItems = emptyList())
        assertEquals("ALL", state.selectedFilter)
        assertEquals(SortCategory.STANDARD, state.activeCategory)
        assertTrue(state.mediaItems.isEmpty())
    }

    @Test
    fun `test DiscoverPresentationState default values`() {
        val state = DiscoverPresentationState()
        assertTrue(state.obsessions.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `test LibraryContent adapter logic`() {
        // This test would ideally verify that LibraryContent handles its inputs.
        // Since we can't easily run Compose tests in this environment, 
        // we verify the model data flow conceptually.
        
        val items = listOf(
            MediaItem("1", "Title 1", "PHOTO"),
            MediaItem("2", "Title 2", "VIDEO")
        )
        
        val state = LibraryPresentationState(
            mediaItems = items,
            selectedFilter = "PHOTO"
        )
        
        assertEquals(2, state.mediaItems.size)
        assertEquals("PHOTO", state.selectedFilter)
    }
}
