package com.example

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class DiscoverRecommendationTest {

    private val tasteDNA = TasteDNA()
    private val stats = IntelligenceStats()

    private val favoriteItem = MediaItem(
        id = "fav",
        title = "Favorite",
        mediaType = "VIDEO",
        genre = "Action",
        moodTags = listOf("vibrant"), 
        isFavorite = true,
        viewCount = 10,
        rating = 5.0f,
        compatibilityStatus = CompatibilityStatus.PLAYABLE
    )

    private val unseenItem = MediaItem(
        id = "new",
        title = "New",
        mediaType = "VIDEO",
        genre = "Documentary",
        moodTags = listOf("muted"),
        viewCount = 0,
        exposureCount = 0,
        compatibilityStatus = CompatibilityStatus.PLAYABLE
    )

    private fun setupMockRepository(): MediaRepository {
        val repository = mock(MediaRepository::class.java)
        val core = mock(AuraIntelligenceCore::class.java)
        
        whenever(repository.intelligenceCore).thenReturn(core)
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(listOf(favoriteItem, unseenItem)))
        
        return repository
    }

    @Test
    fun testDiscover_PersonalizedMode_FavorsFavorites() {
        runBlocking {
            val repository = setupMockRepository()
            val core = repository.intelligenceCore!!

            // Mock Core response for Hero category
            whenever(core.processRequest(any())).thenAnswer { invocation ->
                val req = invocation.arguments[0] as IntelligenceRequest
                val candidates = if (req.limit == 1) {
                    listOf(IntelligenceCandidate(favoriteItem.copy(selectionReason = "High predicted match"), emptyList(), 1.0, 1.0f, 0f, "High predicted match"))
                } else {
                    listOf(
                        IntelligenceCandidate(favoriteItem.copy(selectionReason = "Similar to favorites"), emptyList(), 1.0, 1.0f, 0f, "Similar to favorites"),
                        IntelligenceCandidate(unseenItem.copy(selectionReason = "Fresh for you"), emptyList(), 0.5, 0.5f, 0f, "Fresh for you")
                    )
                }
                IntelligenceResponse(req.requestId, req.mode, candidates, latencyMs = 0L)
            }

            val categories = RecommendationEngine.computeDiscoverCategories(
                repository = repository,
                tasteDNA = tasteDNA,
                stats = stats
            )

            // In personalized mode, the high predicted match (favorite) should be next obsession
            assertEquals("fav", categories.nextObsession?.id)
            assertEquals("High predicted match", categories.nextObsession?.selectionReason)
        }
    }

    @Test
    fun testDiscover_ExploratoryMode_FavorsUnseen() {
        runBlocking {
            val repository = setupMockRepository()
            val core = repository.intelligenceCore!!

            // Mock Core response where unseen item is hero
            whenever(core.processRequest(any())).thenAnswer { invocation ->
                val req = invocation.arguments[0] as IntelligenceRequest
                val candidates = if (req.limit == 1) {
                    listOf(IntelligenceCandidate(unseenItem.copy(selectionReason = "Aura is learning your preference"), emptyList(), 1.0, 1.0f, 0f, "Aura is learning your preference"))
                } else {
                    listOf(
                        IntelligenceCandidate(unseenItem.copy(selectionReason = "Fresh for you"), emptyList(), 1.0, 1.0f, 0f, "Fresh for you"),
                        IntelligenceCandidate(favoriteItem.copy(selectionReason = "Similar to favorites"), emptyList(), 0.5, 0.5f, 0f, "Similar to favorites")
                    )
                }
                IntelligenceResponse(req.requestId, req.mode, candidates, latencyMs = 0L)
            }

            val categories = RecommendationEngine.computeDiscoverCategories(
                repository = repository,
                tasteDNA = tasteDNA,
                stats = stats
            )

            // In exploratory mode, the unseen item should rise to Next Obsession
            assertEquals("new", categories.nextObsession?.id)
            assertTrue(categories.nextObsession?.selectionReason?.contains("Aura is learning") == true)
        }
    }
}
