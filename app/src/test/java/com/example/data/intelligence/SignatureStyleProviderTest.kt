package com.example.data.intelligence

import com.example.data.MediaItem
import com.example.data.TasteDNA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureStyleProviderTest {

    @Test
    fun `calculateStyleProfile returns meaningful results with neutral DNA`() {
        val dna = TasteDNA()
        val items = listOf(
            MediaItem(id = "1", title = "Test 1", mediaType = "PHOTO", category = "nature"),
            MediaItem(id = "2", title = "Test 2", mediaType = "VIDEO", category = "cinematic")
        )

        val profile = SignatureStyleProvider.calculateStyleProfile(dna, items)

        // Neutral DNA should have 0 confidence, so no active styles
        assertTrue(profile.activeStyles.isEmpty())
        // But it should have emerging styles based on alignment with the 0.5 baseline
        assertTrue(profile.emergingStyles.isNotEmpty())
    }

    @Test
    fun `calculateStyleProfile identifies active style with high confidence DNA`() {
        // Mock DNA with high Cinematic alignment and high confidence
        val dna = TasteDNA(
            isFineTuningEnabled = true,
            depth = 0.8,
            lighting = 0.8,
            confDepth = 0.9,
            confLighting = 0.9
        )
        val items = (1..10).map { 
            MediaItem(id = "it", title = "Item $it", mediaType = "VIDEO", category = "cinematic")
        }

        val profile = SignatureStyleProvider.calculateStyleProfile(dna, items)

        // Should now have Cinematic as active
        assertTrue(profile.activeStyles.any { it.anchor.id == "cinematic" })
    }

    @Test
    fun `scalability test with 1000 items`() {
        val dna = TasteDNA()
        val items = (1..1000).map { 
            MediaItem(id = "$it", title = "Item $it", mediaType = "PHOTO")
        }

        val start = System.currentTimeMillis()
        val profile = SignatureStyleProvider.calculateStyleProfile(dna, items)
        val duration = System.currentTimeMillis() - start

        println("Duration for 1000 items: ${duration}ms")
        assertTrue(profile.emergingStyles.isNotEmpty())
        assertTrue(duration < 2000) // Should be well under 2s for 1000 items on a modern machine
    }
}
