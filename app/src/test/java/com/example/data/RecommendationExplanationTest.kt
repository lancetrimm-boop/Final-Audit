package com.example.data

import com.example.data.intelligence.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationExplanationTest {

    private val tasteDNA = TasteDNA(
        vibrancy = 0.8, // User likes vibrant
        learnedVibrancy = 0.8,
        isFineTuningEnabled = true
    )

    @Test
    fun testGenerate_HighMatch_Vibrant() {
        val item = MediaItem(
            id = "test1",
            title = "Vibrant Scene",
            mediaType = "PHOTO",
            genre = "Nature",
            moodTags = listOf("vibrant"),
            rating = 5.0f // Boost match score
        )
        
        // High rating takes priority in generator logic
        val candidate = IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f)

        val explanation = RecommendationExplanationGenerator.generate(candidate, tasteDNA)
        
        assertNotNull(explanation)
        assertEquals("Based on your high rating", explanation?.primaryReason)
    }

    @Test
    fun testGenerate_HighMatch_DNA_Alignment() {
        val creatorId = "creator1"
        val item = MediaItem(
            id = "test1_dna",
            title = "Vibrant Scene",
            mediaType = "PHOTO",
            genre = "Nature",
            moodTags = listOf("vibrant"),
            eloRating = 2000.0,
            creatorId = creatorId,
            viewCount = 10,
            exposureCount = 10
        )
        
        val evidence = listOf(
            EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, 0.8f, 0.9f, EvidenceStatus.INFERRED, "TasteDNA")
        )
        val candidate = IntelligenceCandidate(item, evidence, 1.0, 1.0f, 0.8f)

        val explanation = RecommendationExplanationGenerator.generate(candidate, tasteDNA)
        
        assertNotNull(explanation)
        assertEquals("High predicted match", explanation?.primaryReason)
        assertTrue(explanation?.detailPoints?.any { it.contains("Vibrancy") } == true)
    }

    @Test
    fun testGenerate_SimilarToFavorites() {
        val item = MediaItem(
            id = "test2",
            title = "Favorite Item",
            mediaType = "PHOTO",
            genre = "Nature",
            isFavorite = true
        )
        
        val candidate = IntelligenceCandidate(item, emptyList(), 1.0, 1.0f, 0f)

        val explanation = RecommendationExplanationGenerator.generate(candidate, tasteDNA)
        
        assertNotNull(explanation)
        assertEquals("Similar to your favorites", explanation?.primaryReason)
        assertTrue(explanation?.detailPoints?.contains("You previously favorited this item") == true)
    }

    @Test
    fun testGenerate_Novelty() {
        val item = MediaItem(
            id = "test3",
            title = "New Style",
            mediaType = "PHOTO",
            genre = "Sci-Fi", 
            viewCount = 0,
            exposureCount = 0,
            moodTags = listOf("experimental")
        )
        
        val evidence = listOf(
            EvidenceItem(EvidenceType.EXPLORATION_VALUE, 0.8f, 0.7f, EvidenceStatus.INFERRED, "ExplorationEngine")
        )
        val candidate = IntelligenceCandidate(item, evidence, 0.9, 0.0f, 0.8f)

        val explanation = RecommendationExplanationGenerator.generate(candidate, tasteDNA)
        
        assertNotNull(explanation)
        assertEquals("Expand your taste", explanation?.primaryReason)
        assertTrue(explanation?.isExploratory == true)
        assertTrue(explanation?.detailPoints?.any { it.contains("A new discovery") } == true)
    }

    @Test
    fun testGenerate_LowConfidence_ReturnsNull() {
        val item = MediaItem(
            id = "test4",
            title = "Generic Item",
            mediaType = "PHOTO",
            genre = "Action",
            viewCount = 10,
            exposureCount = 20,
            rating = 0f
        )
        
        // Neutral DNA
        val neutralDNA = TasteDNA()
        
        // Low score candidate
        val candidate = IntelligenceCandidate(item, emptyList(), 0.1, 0.1f, 0f)

        val explanation = RecommendationExplanationGenerator.generate(candidate, neutralDNA)
        
        // Should be null if we can't find a strong reason
        assertNull(explanation)
    }
}
