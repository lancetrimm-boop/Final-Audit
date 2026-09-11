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

class AuraLibrarySortingTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore
    private val now = System.currentTimeMillis()

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
        
        // Mock getFilteredAndSortedMedia to use the real implementation for testing
        // This is tricky because it's a member function. 
        // We'll use a real instance but mock the internal core.
        // Actually, let's use the real MediaRepository but mock the core field.
        repository = MediaRepository()
        val coreField = MediaRepository::class.java.getDeclaredField("intelligenceCore")
        coreField.isAccessible = true
        coreField.set(repository, core)
    }

    private fun createItem(
        id: String,
        title: String,
        dateAdded: Long = now,
        lastViewed: Long? = null,
        viewCount: Int = 0,
        rating: Float = 0f,
        durationMs: Long = 0L,
        exposureCount: Int = 0
    ) = MediaItem(
        id = id,
        title = title,
        mediaType = "VIDEO",
        dateAdded = dateAdded,
        lastViewedTimestamp = lastViewed,
        viewCount = viewCount,
        rating = rating,
        durationMs = durationMs,
        exposureCount = exposureCount,
        compatibilityStatus = CompatibilityStatus.PLAYABLE
    )

    @Test
    fun testStandardSort_Title() = runBlocking {
        val items = listOf(
            createItem("1", "Banana"),
            createItem("2", "Apple"),
            createItem("3", "Cherry")
        )

        val sortedAsc = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.TITLE_ASC, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Apple", sortedAsc[0].title)
        assertEquals("Banana", sortedAsc[1].title)
        assertEquals("Cherry", sortedAsc[2].title)

        val sortedDesc = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.TITLE_DESC, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Cherry", sortedDesc[0].title)
        assertEquals("Banana", sortedDesc[1].title)
        assertEquals("Apple", sortedDesc[2].title)
    }

    @Test
    fun testStandardSort_Duration() = runBlocking {
        val items = listOf(
            createItem("1", "Short", durationMs = 1000),
            createItem("2", "Long", durationMs = 5000),
            createItem("3", "Medium", durationMs = 3000)
        )

        val sortedShort = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.SHORTEST_DURATION, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Short", sortedShort[0].title)
        assertEquals("Medium", sortedShort[1].title)
        assertEquals("Long", sortedShort[2].title)
    }

    @Test
    fun testIntelligentSort_Discover() {
        runBlocking {
            val items = listOf(
                createItem("1", "High Exposure", viewCount = 10, exposureCount = 20),
                createItem("2", "Low Exposure", viewCount = 1, exposureCount = 2),
                createItem("3", "Unseen", viewCount = 0, exposureCount = 0)
            )

            // Mock Core response for DISCOVER sort
            val candidates = listOf(
                IntelligenceCandidate(items[2], emptyList(), 1.0, 1.0f, 0f, "New Discovery"),
                IntelligenceCandidate(items[1], emptyList(), 0.5, 0.5f, 0f, "New Discovery")
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, latencyMs = 10L)
            whenever(core.processRequest(any())).thenReturn(response)

            val sorted = repository.getFilteredAndSortedMedia(
                "ALL", SortCategory.INTELLIGENT, StandardSortOption.NEWEST_FIRST, IntelligentSortOption.DISCOVER, inputItems = items
            )
            // Note: MediaRepository.kt:916 maps Core results and adds provenance.
            assertEquals("3", sorted[0].id)
            assertEquals("2", sorted[1].id)
        }
    }

    @Test
    fun testIntelligentSort_Rediscover() {
        runBlocking {
            val items = listOf(
                createItem("1", "Recent Liked", lastViewed = now - 1000, rating = 5f),
                createItem("2", "Old Liked", lastViewed = now - (1000L * 60 * 60 * 24 * 30), rating = 5f),
                createItem("3", "Old Not Liked", lastViewed = now - (1000L * 60 * 60 * 24 * 30), rating = 0f)
            )

            // Mock Core response for REDISCOVER sort
            val candidates = listOf(
                IntelligenceCandidate(items[1], emptyList(), 100.0, 5.0f, 10f, "Blast from the Past")
            )
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, latencyMs = 10L)
            whenever(core.processRequest(any())).thenReturn(response)

            val sorted = repository.getFilteredAndSortedMedia(
                "ALL", SortCategory.INTELLIGENT, StandardSortOption.NEWEST_FIRST, IntelligentSortOption.REDISCOVER, inputItems = items
            )
            assertEquals(1, sorted.size)
            assertEquals("Old Liked", sorted[0].title)
        }
    }
}
