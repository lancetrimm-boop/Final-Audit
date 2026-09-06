package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class CrossSurfaceConsistencyTest {

    @Test
    fun testCrossSurfaceDeterminism() = runBlocking {
        // Setup shared state
        val repository = mock(MediaRepository::class.java)
        val tasteDNA = TasteDNA(isFineTuningEnabled = true)
        val item1 = MediaItem(id = "item1", title = "A", mediaType = "PHOTO", compatibilityStatus = CompatibilityStatus.PLAYABLE)
        val items = listOf(item1)
        
        `when`(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(items))
        `when`(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(tasteDNA))
        `when`(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        `when`(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        `when`(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        `when`(repository.getMediaItemById("item1")).thenReturn(item1)
        `when`(repository.isItemVisibleInLibrary(any())).thenReturn(true)
        `when`(repository.signatureStyleProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))

        val router = mock(RetrievalRouter::class.java)
        `when`(router.retrieve(any())).thenReturn(mapOf(
            SearchChannel.KEYWORD to listOf(RankedChannelItem("item1", 10f, 1))
        ))

        val core = AuraIntelligenceCore(repository, router)

        // Request 1: From Library
        val reqLibrary = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            contextualIntent = ContextualIntent.LIBRARY_BROWSE,
            query = "test",
            tasteDNA = tasteDNA
        )
        val resLibrary = core.processRequest(reqLibrary)

        // Request 2: From Discover (identical parameters)
        val reqDiscover = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            contextualIntent = ContextualIntent.LIBRARY_BROWSE, // Same intent for identity check
            query = "test",
            tasteDNA = tasteDNA
        )
        val resDiscover = core.processRequest(reqDiscover)

        assertEquals("Ordering must be identical for identical requests", 
            resLibrary.candidates.map { it.item.id }, 
            resDiscover.candidates.map { it.item.id }
        )
        
        assertEquals("Scores must be identical",
            resLibrary.candidates[0].rankScore,
            resDiscover.candidates[0].rankScore,
            1e-9
        )
    }
}
