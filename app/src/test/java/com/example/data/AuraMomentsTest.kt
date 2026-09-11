package com.example.data

import com.example.data.intelligence.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argThat

class AuraMomentsTest {

    private fun createSampleMediaLibrary(): List<MediaItem> {
        return listOf(
            MediaItem(
                id = "item_1",
                title = "Sunset Photo",
                mediaType = "PHOTO",
                category = "Nature",
                genre = "Landscape",
                imageUrl = "file://photo1.jpg",
                isFavorite = true,
                rating = 5.0f,
                viewCount = 10,
                dateAdded = 1700000000000L,
                year = 2024,
                moodTags = listOf("vivid", "warm", "nature"),
                compatibilityStatus = CompatibilityStatus.PLAYABLE
            ),
            MediaItem(
                id = "item_2",
                title = "Beach Photo",
                mediaType = "PHOTO",
                category = "Nature",
                genre = "Travel",
                imageUrl = "file://photo2.jpg",
                isFavorite = false,
                rating = 4.0f,
                viewCount = 2,
                dateAdded = 1690000000000L,
                year = 2023,
                moodTags = listOf("travel", "summer"),
                compatibilityStatus = CompatibilityStatus.PLAYABLE
            )
        )
    }

    @Test
    fun testIntelligentSlideshow_DelegatesToCore() {
        runBlocking {
            val repository = mock(MediaRepository::class.java)
            val core = mock(AuraIntelligenceCore::class.java)
            val library = createSampleMediaLibrary()
            
            whenever(repository.intelligenceCore).thenReturn(core)
            whenever(repository.mediaItems).thenReturn(MutableStateFlow(library))
            
            val candidates = library.map { IntelligenceCandidate(it, emptyList(), 1.0, 1.0f, 0f) }
            val response = IntelligenceResponse("req", IntelligenceMode.SORT, candidates, latencyMs = 10L)
            
            whenever(core.processRequest(any())).thenReturn(response)

            val result = AuraMomentsEngine.generateIntelligentSlideshow(repository, MomentsMode.FOR_YOU)

            assertTrue(result.isNotEmpty())
            assertEquals(2, result.size)
            verify(core).processRequest(argThat { req -> req.mode == IntelligenceMode.SORT })
        }
    }

    @Test
    fun testLegacySlideshow_FilteringInvariants() {
        val library = createSampleMediaLibrary()
        // Legacy slideshow currently filters for PHOTOS only
        val result = AuraMomentsEngine.generateSlideshow(library)
        assertTrue(result.all { it.mediaType == "PHOTO" })
    }

    @Test
    fun testIntelligentSlideshow_EmptyLibrary_ReturnsEmpty() {
        runBlocking {
            val repository = mock(MediaRepository::class.java)
            whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
            
            val result = AuraMomentsEngine.generateIntelligentSlideshow(repository, MomentsMode.FOR_YOU)
            assertTrue(result.isEmpty())
        }
    }
}
