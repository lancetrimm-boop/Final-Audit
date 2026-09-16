package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AuraExploreRandomizationTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setup() {
        repository = mock()
        core = AuraIntelligenceCore(repository, mock())
        
        // Mock all required flows with default empty/initial states
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        whenever(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        
        // Use isA() to avoid nullability issues in Kotlin matchers
        whenever(repository.isItemVisibleInLibrary(isA())).thenReturn(true)
    }

    private fun createItem(id: String) = MediaItem(
        id = id,
        title = "Item $id",
        mediaType = "VIDEO",
        compatibilityStatus = CompatibilityStatus.PLAYABLE,
        viewCount = 0,
        exposureCount = 0,
        rating = 0f
    )

    @Test
    fun `test DISCOVER is stable within same session`() = runBlocking {
        val items = (1..50).map { createItem(it.toString()) }
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(items))
        // Already mocked in setup, but we can override if needed. 
        // Here we just make sure we provide the items.


        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "DISCOVER",
            seed = 12345L,
            limit = 10
        )

        val response = core.processRequest(request)
        val ids1 = response.candidates.map { it.item.id }
        
        // Use a second request with same seed
        val response2 = core.processRequest(request)
        val ids2 = response2.candidates.map { it.item.id }

        assertTrue("Should have candidates", ids1.isNotEmpty())
        assertEquals("Same session must produce identical order", ids1, ids2)
    }

    @Test
    fun `test DISCOVER varies between different sessions`() = runBlocking {
        val items = (1..50).map { createItem(it.toString()) }
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(items))
        // Already mocked in setup, but we can override if needed. 
        // Here we just make sure we provide the items.


        val request1 = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "DISCOVER",
            seed = 1L,
            limit = 10
        )

        val request2 = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "DISCOVER",
            seed = 2L,
            limit = 10
        )

        val response1 = core.processRequest(request1)
        val response2 = core.processRequest(request2)

        val ids1 = response1.candidates.map { it.item.id }
        val ids2 = response2.candidates.map { it.item.id }

        assertTrue("Should have candidates in response 1", ids1.isNotEmpty())
        assertTrue("Should have candidates in response 2", ids2.isNotEmpty())
        assertNotEquals("Different sessions should produce different orders for tied items", ids1, ids2)
    }

    @Test
    fun `test DISCOVER preserves strong relevance signals`() = runBlocking {
        val items = listOf(
            createItem("1").copy(viewCount = 10, exposureCount = 20),
            createItem("2")
        )
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(items))
        // Already mocked in setup, but we can override if needed. 
        // Here we just make sure we provide the items.


        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "DISCOVER",
            seed = 999L
        )

        val response = core.processRequest(request)
        assertEquals("Unseen item must still outrank frequently viewed item in DISCOVER", "2", response.candidates[0].item.id)
    }
}
