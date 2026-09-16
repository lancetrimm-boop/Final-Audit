package com.example.data.intelligence

import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class FavoritesRankingConsolidationTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setup() {
        repository = mock()
        core = AuraIntelligenceCore(repository, mock())
        
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(repository.preferenceProfile).thenReturn(MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(MutableStateFlow(emptyMap()))
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)
    }

    private fun createItem(id: String, isFavorite: Boolean = true, durationMs: Long = 10000) = MediaItem(
        id = id,
        title = "Item $id",
        mediaType = "VIDEO",
        isFavorite = isFavorite,
        durationMs = durationMs,
        compatibilityStatus = CompatibilityStatus.PLAYABLE
    )

    @Test
    fun `test Favorites ranks by actual likes with duration adjustment`() = runBlocking {
        // Item 1: 10 likes, 10s duration -> Density = 10 / (10+10) = 0.5
        // Item 2: 10 likes, 100s duration -> Density = 10 / (100+10) = 0.09
        // Item 3: 20 likes, 100s duration -> Density = 20 / (110) = 0.18
        
        val items = listOf(
            createItem("1", durationMs = 10000),
            createItem("2", durationMs = 100000),
            createItem("3", durationMs = 100000)
        )
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(items))
        
        whenever(repository.getActualLikeCounts(any())).thenReturn(mapOf(
            "1" to 10,
            "2" to 10,
            "3" to 20
        ))

        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "FAVORITES"
        )

        val response = core.processRequest(request)
        val sortedIds = response.candidates.map { it.item.id }
        
        // Expected order: 1 (0.5), 3 (0.18), 2 (0.09)
        assertEquals("Item with higher density must rank first", "1", sortedIds[0])
        assertEquals("Item with more likes at same duration must rank next", "3", sortedIds[1])
        assertEquals("Item with lower density must rank last", "2", sortedIds[2])
    }

    @Test
    fun `test Favorites removes viewCount proxy dependence`() = runBlocking {
        // Item 1: 100 views but 0 likes
        // Item 2: 10 views but 1 like
        
        val items = listOf(
            createItem("1").copy(viewCount = 100),
            createItem("2").copy(viewCount = 10)
        )
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(items))
        
        // Actual likes: Item 2 has more
        whenever(repository.getActualLikeCounts(any())).thenReturn(mapOf(
            "1" to 0,
            "2" to 1
        ))

        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "FAVORITES"
        )

        val response = core.processRequest(request)
        val sortedIds = response.candidates.map { it.item.id }
        
        assertEquals("Item with actual likes must outrank item with only views", "2", sortedIds[0])
    }
}
