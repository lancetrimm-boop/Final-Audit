package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class LibraryDiscoveryTest {

    private lateinit var repo: MediaRepository
    private lateinit var core: AuraIntelligenceCore
    private val tasteDNA = TasteDNA()
    private val stats = IntelligenceStats()

    @Before
    fun setUp() {
        repo = MediaRepository()
        core = mock(AuraIntelligenceCore::class.java)
        
        val field = MediaRepository::class.java.getDeclaredField("intelligenceCore")
        field.isAccessible = true
        field.set(repo, core)
    }

    private val favoriteItem = MediaItem(
        id = "fav",
        title = "Favorite",
        mediaType = "VIDEO",
        genre = "Action",
        moodTags = listOf("vibrant"), 
        isFavorite = false,
        viewCount = 10,
        rating = 2.0f,
        lastViewedTimestamp = System.currentTimeMillis() - 4000000 // Not recent
    )

    private val unseenItem = MediaItem(
        id = "new",
        title = "New",
        mediaType = "VIDEO",
        genre = "Documentary",
        moodTags = listOf("muted"), 
        viewCount = 0,
        exposureCount = 0
    )

    @Test
    fun testLibrary_AISort_RespectsPolicy() = runBlocking {
        val items = listOf(favoriteItem, unseenItem)
        
        // 1. Personalized Policy
        val candidates1 = listOf(
            IntelligenceCandidate(favoriteItem.copy(selectionReason = "For You"), emptyList(), 1.0, 1.0f, 0f)
        )
        whenever(core.processRequest(any())).thenReturn(IntelligenceResponse("r1", IntelligenceMode.SORT, candidates1, isSuccess = true, latencyMs = 0L))

        val personalizedResults = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.PERSONALIZED,
            inputItems = items,
            policy = DiscoveryPolicy(mode = DiscoveryMode.PERSONALIZED),
            stats = stats
        )
        
        assertEquals("fav", personalizedResults[0].id)
        assertTrue(personalizedResults[0].selectionReason!!.contains("For You"))

        // 2. Exploratory Policy
        val candidates2 = listOf(
            IntelligenceCandidate(unseenItem.copy(selectionReason = "Best Match"), emptyList(), 1.0, 1.0f, 0f)
        )
        whenever(core.processRequest(any())).thenReturn(IntelligenceResponse("r2", IntelligenceMode.SORT, candidates2, isSuccess = true, latencyMs = 0L))

        val exploratoryResults = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.PERSONALIZED,
            inputItems = items,
            policy = DiscoveryPolicy(mode = DiscoveryMode.EXPLORATORY),
            stats = stats
        )
        
        assertEquals("new", exploratoryResults[0].id)
        assertTrue(exploratoryResults[0].selectionReason!!.contains("Best Match"))
    }

    @Test
    fun testLibrary_RediscoverSort_FocusesOnLiked() = runBlocking {
        val likedItem = favoriteItem.copy(id = "liked", isFavorite = true)
        val nonLikedItem = unseenItem.copy(id = "unseen", isFavorite = false, rating = 0f)
        
        val candidates = listOf(
            IntelligenceCandidate(likedItem, emptyList(), 1.0, 1.0f, 0f)
        )
        whenever(core.processRequest(any())).thenReturn(IntelligenceResponse("r1", IntelligenceMode.SORT, candidates, isSuccess = true, latencyMs = 0L))

        val results = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.REDISCOVER,
            inputItems = listOf(likedItem, nonLikedItem)
        )
        
        assertEquals(1, results.size)
        assertEquals("liked", results[0].id)
    }

    @Test
    fun testLibrary_FavoritesSort_IncludesLikedAndHighRated() = runBlocking {
        val favorite = favoriteItem.copy(id = "fav_true", isFavorite = true, rating = 0f)
        val highRated = unseenItem.copy(id = "rated_5", isFavorite = false, rating = 5f)
        val ordinary = unseenItem.copy(id = "normal", isFavorite = false, rating = 0f)
        
        val candidates = listOf(
            IntelligenceCandidate(favorite, emptyList(), 1.0, 1.0f, 0f),
            IntelligenceCandidate(highRated, emptyList(), 0.9, 0.9f, 0f)
        )
        whenever(core.processRequest(any())).thenReturn(IntelligenceResponse("r1", IntelligenceMode.SORT, candidates, isSuccess = true, latencyMs = 0L))

        val results = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.FAVORITES,
            inputItems = listOf(favorite, highRated, ordinary)
        )
        
        assertEquals(2, results.size)
        val ids = results.map { it.id }
        assertTrue(ids.contains("fav_true"))
        assertTrue(ids.contains("rated_5"))
    }

    @Test
    fun testLibrary_HiddenGemsSort_FocusesOnLowExposure() = runBlocking {
        val gem = unseenItem.copy(id = "gem", exposureCount = 1, viewCount = 0, rating = 0f)
        val exposed = favoriteItem.copy(id = "exposed", exposureCount = 10, viewCount = 5, rating = 5f)
        
        val candidates = listOf(
            IntelligenceCandidate(gem, emptyList(), 1.0, 1.0f, 0f)
        )
        whenever(core.processRequest(any())).thenReturn(IntelligenceResponse("r1", IntelligenceMode.SORT, candidates, isSuccess = true, latencyMs = 0L))

        val results = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.HIDDEN_GEMS,
            inputItems = listOf(gem, exposed)
        )
        
        assertEquals(1, results.size)
        assertEquals("gem", results[0].id)
    }

    @Test
    fun testLibrary_SurpriseMe_UsesStableSeed() = runBlocking {
        val items = (1..20).map { unseenItem.copy(id = "item_$it") }
        
        whenever(core.processRequest(any())).thenAnswer { invocation ->
            val req = invocation.arguments[0] as IntelligenceRequest
            println("MOCK CORE: mode=${req.mode} sort=${req.sortOption} seed=${req.seed}")
            val sorted = if (req.sortOption == "SURPRISE_ME") {
                items.shuffled(java.util.Random(req.seed ?: 0L))
            } else {
                items
            }
            val candidates = sorted.map { IntelligenceCandidate(it, emptyList(), 1.0, 1.0f, 0f) }
            IntelligenceResponse(req.requestId, req.mode, candidates, isSuccess = true, latencyMs = 0L)
        }

        val results1 = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.SURPRISE_ME,
            inputItems = items,
            sessionSeed = 123L
        )

        val results2 = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.SURPRISE_ME,
            inputItems = items,
            sessionSeed = 123L
        )

        val results3 = repo.getFilteredAndSortedMedia(
            filterType = "ALL",
            sortCategory = SortCategory.INTELLIGENT,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.SURPRISE_ME,
            inputItems = items,
            sessionSeed = 456L
        )

        assertEquals("Same seed must produce same order", results1.map { it.id }, results2.map { it.id })
        assertNotEquals("Different seed must produce different order", results1.map { it.id }, results3.map { it.id })
    }
}
