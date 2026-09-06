package com.example.data.intelligence

import com.example.data.*
import com.example.data.db.InteractionEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class PreferenceEngineTest {

    @Test
    fun testCalculateUpdatedDNA_EmptyEvents() {
        val dna = TasteDNA()
        val repository = mock(MediaRepository::class.java)
        val updated = PreferenceEngine.calculateUpdatedDNA(dna, emptyList(), repository)
        assertEquals(dna, updated)
    }

    @Test
    fun testCalculateUpdatedDNA_PositiveSignal() {
        val dna = TasteDNA(vibrancy = 0.5, learnedVibrancy = 0.5, isFineTuningEnabled = true)
        val repository = mock(MediaRepository::class.java)
        val mediaId = "item1"
        
        val item = MediaItem(id = mediaId, title = "Vibrant", mediaType = "PHOTO", moodTags = listOf("vibrant"))
        `when`(repository.getMediaItemById(mediaId)).thenReturn(item)

        val events = listOf(
            InteractionEventEntity(
                id = "e1",
                mediaId = mediaId,
                type = AuraInteractionType.FAVORITE.name,
                value = 1.0,
                timestamp = System.currentTimeMillis(),
                contextJson = null
            )
        )

        val updated = PreferenceEngine.calculateUpdatedDNA(dna, events, repository)
        
        // Target for vibrancy (vibrant tag -> vibrancy +1.0) is 1.0 (signal value = 0.5 + 0.5*1.0 = 1.0)
        // Damping: 0.5 + 0.1 * (1.0 - 0.5) = 0.55
        assertEquals(0.55, updated.learnedVibrancy, 0.01)
        assertTrue(updated.confVibrancy > 0.0)
    }

    @Test
    fun testCalculateUpdatedDNA_NegativeSignal() {
        val dna = TasteDNA(vibrancy = 0.5, learnedVibrancy = 0.5, isFineTuningEnabled = true)
        val repository = mock(MediaRepository::class.java)
        val mediaId = "item1"
        
        val item = MediaItem(id = mediaId, title = "Vibrant", mediaType = "PHOTO", moodTags = listOf("vibrant"))
        `when`(repository.getMediaItemById(mediaId)).thenReturn(item)

        val events = listOf(
            InteractionEventEntity(
                id = "e1",
                mediaId = mediaId,
                type = AuraInteractionType.MEDIA_ABANDONED.name,
                value = 1.0,
                timestamp = System.currentTimeMillis(),
                contextJson = null
            )
        )

        val updated = PreferenceEngine.calculateUpdatedDNA(dna, events, repository)
        
        // Target for vibrancy is 0.0 (signal value = 0.5 + 0.5*(-1.0) = 0.0 since weight is negative)
        // Damping: 0.5 + 0.1 * (0.0 - 0.5) = 0.45
        assertEquals(0.45, updated.learnedVibrancy, 0.01)
    }
}
