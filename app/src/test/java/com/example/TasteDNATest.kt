package com.example.data.intelligence

import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class TasteDNATest {

    private lateinit var core: AuraIntelligenceCore

    @Before
    fun setup() {
        // Intelligence Core is now the authoritative scorer
        core = AuraIntelligenceCore(mock(MediaRepository::class.java), mock(RetrievalRouter::class.java))
    }

    @Test
    fun testTasteDnaEffectiveValues() {
        // Zero confidence -> manual
        val dnaZeroConf = TasteDNA(
            isFineTuningEnabled = true,
            vibrancy = 0.8,
            learnedVibrancy = 0.2,
            confVibrancy = 0.0
        )
        assertEquals(0.8, dnaZeroConf.effectiveVibrancy, 0.01)

        // Full confidence -> manual + (learned - manual) * 0.2
        // 0.8 + (0.2 - 0.8) * 1.0 * 0.2 = 0.8 - 0.6 * 0.2 = 0.8 - 0.12 = 0.68
        val dnaFullConf = TasteDNA(
            isFineTuningEnabled = true,
            vibrancy = 0.8,
            learnedVibrancy = 0.2,
            confVibrancy = 1.0
        )
        assertEquals(0.68, dnaFullConf.effectiveVibrancy, 0.01)
    }

    @Test
    fun testScoringWithTasteDna() {
        val item = MediaItem(
            id = "test_item",
            title = "Vibrant Sunset",
            mediaType = "PHOTO",
            rating = 4.0f,
            moodTags = listOf("Vibrant", "Nature")
        )

        // Baseline score with default TasteDNA (0.5)
        val dnaBaseline = TasteDNA(vibrancy = 0.5, learnedVibrancy = 0.5)
        val baselineScore = core.scorePersonalization(item, dnaBaseline)

        // Score with high vibrancy (1.0)
        val dnaHigh = TasteDNA(vibrancy = 1.0, learnedVibrancy = 1.0)
        val highVibrancyScore = core.scorePersonalization(item, dnaHigh)

        assertTrue("Score with higher vibrancy should be greater", highVibrancyScore > baselineScore)
    }
}
