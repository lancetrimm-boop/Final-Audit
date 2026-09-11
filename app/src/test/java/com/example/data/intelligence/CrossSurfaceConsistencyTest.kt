package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class CrossSurfaceConsistencyTest {

    @Test
    fun testRelationshipBounding() = runBlocking {
        // Setup state
        val repository = mock(MediaRepository::class.java)
        val item1 = MediaItem(id = "item1", title = "A", mediaType = "PHOTO", compatibilityStatus = CompatibilityStatus.PLAYABLE)
        val item2 = MediaItem(id = "item2", title = "B", mediaType = "PHOTO", compatibilityStatus = CompatibilityStatus.PLAYABLE)
        
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(listOf(item1, item2)))
        whenever(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        whenever(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        whenever(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.getMediaItemById("item1")).thenReturn(item1)
        whenever(repository.getMediaItemById("item2")).thenReturn(item2)
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)
        whenever(repository.signatureStyleProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))

        val router = mock(RetrievalRouter::class.java)
        // Router returns item2 as #1, item1 as #2
        whenever(router.retrieve(any())).thenReturn(mapOf(
            SearchChannel.KEYWORD to listOf(
                RankedChannelItem("item2", 10f, 1),
                RankedChannelItem("item1", 5f, 2)
            )
        ))

        val core = AuraIntelligenceCore(repository, router)

        // Request with item1 as related anchor
        val request = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = "test",
            relatedMediaIds = listOf("item1")
        )
        
        val response = core.processRequest(request)
        
        // Item1 should get a relationship bonus because it IS the anchor (shared metadata/self-match logic in expanded core)
        // Verify that relationship bonus (0.3 max) doesn't allow item1 to leapfrog item2 if the base relevance gap is large.
        // item2 relevance = 10, item1 relevance = 5.
        // rankScore = rrf + ... + relBonus.
        
        assertTrue("Results must be sealed", response.visibilitySealed)
        assertEquals("Item2 should still be #1 if gap is large enough", "item2", response.candidates[0].item.id)
    }
}
