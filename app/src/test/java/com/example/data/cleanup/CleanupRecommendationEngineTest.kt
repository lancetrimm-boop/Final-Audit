package com.example.data.cleanup

import org.junit.Assert.*
import org.junit.Test

class CleanupRecommendationEngineTest {

    private fun createResult(
        id: String, 
        category: CleanupCategory = CleanupCategory.NONE,
        reasons: List<CleanupReason> = emptyList(),
        score: Float = 0.5f
    ) = KeepScoreResult(
        mediaId = id,
        keepScore = score,
        confidenceScore = 0.5f,
        reasons = reasons,
        category = category
    )

    private fun createMetadata(
        id: String,
        size: Long = 1024 * 1024,
        exposure: Int = 5,
        views: Int = 1,
        type: String = "PHOTO",
        hash: String? = null,
        width: Int = 1920,
        height: Int = 1080,
        duration: Long = 0,
        added: Long = 1000L
    ) = CleanupItemMetadata(
        mediaId = id,
        sizeBytes = size,
        exposureCount = exposure,
        viewCount = views,
        mediaType = type,
        contentHash = hash,
        isFavorite = false,
        width = width,
        height = height,
        durationMs = duration,
        dateAdded = added
    )

    @Test
    fun testDeleteRecommendations_CategorizedCorrectly_OnRepeatedSkip() {
        val id = "delete-1"
        val results = listOf(createResult(id, CleanupCategory.NONE, listOf(CleanupReason.REPEATED_SKIP), score = 0.15f))
        val metadata = mapOf(id to createMetadata(id, exposure = 50, views = 0))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.DELETE_RECOMMENDATIONS, recommendations[0].category)
        assertTrue(recommendations[0].explanation.contains("Repeatedly skipped"))
    }

    @Test
    fun testHighExposureZeroViews_IsNotDeleteRecommendation() {
        val id = "ghost-1"
        val results = listOf(createResult(id, CleanupCategory.NONE, listOf(CleanupReason.HIGH_EXPOSURE_NO_ENGAGEMENT), score = 0.10f))
        val metadata = mapOf(id to createMetadata(id, exposure = 50, views = 0))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals("High exposure with zero views must NOT independently trigger Delete Recommendation",
            0, recommendations.size)
    }

    @Test
    fun testUnplayedUnratedMedia_IsNotDeleteRecommendation() {
        val id = "unplayed-1"
        val results = listOf(createResult(id, CleanupCategory.NONE, emptyList(), score = 0.20f))
        val metadata = mapOf(id to createMetadata(id, exposure = 0, views = 0))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals("Unplayed/unrated media without explicit negative feedback must NOT be a Delete Recommendation",
            0, recommendations.size)
    }

    @Test
    fun testLowUserRating_IsDeleteRecommendation() {
        val id = "low-rated-1"
        val results = listOf(createResult(id, CleanupCategory.NONE, listOf(CleanupReason.LOW_USER_RATING), score = 0.20f))
        val metadata = mapOf(id to createMetadata(id, exposure = 5, views = 1))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.DELETE_RECOMMENDATIONS, recommendations[0].category)
        assertTrue(recommendations[0].explanation.contains("Low user rating"))
    }

    @Test
    fun testUnplayableFiles_CategorizedCorrectly() {
        val id = "unplayable-1"
        val results = listOf(createResult(id, CleanupCategory.NONE, score = 0.8f))
        val metadata = mapOf(id to createMetadata(id))
        val unplayableIds = setOf(id)
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata, unplayableIds)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.UNPLAYABLE_FILES, recommendations[0].category)
        assertTrue(recommendations[0].explanation.contains("Confirmed playback failure"))
    }

    @Test
    fun testSpaceHog_CategorizedCorrectly() {
        val id = "huge-1"
        val size = 2L * 1024 * 1024 * 1024 // 2GB
        val results = listOf(createResult(id, CleanupCategory.NONE, score = 0.2f))
        val metadata = mapOf(id to createMetadata(id, size = size, type = "VIDEO"))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.SPACE_HOGS, recommendations[0].category)
        assertTrue(recommendations[0].explanation.contains("Large file — 2.0 GB"))
    }

    @Test
    fun testCategoryPrecedence_UnplayableOverridesSpaceHogsAndRedundant() {
        val masterId = "master"
        val dupId = "unplayable-and-dup"
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(masterId, score = 0.9f),
            createResult(dupId, score = 0.1f)
        )
        val metadata = mapOf(
            masterId to createMetadata(masterId, hash = hash),
            dupId to createMetadata(dupId, size = 500 * 1024 * 1024L, hash = hash)
        )
        val unplayableIds = setOf(dupId)
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata, unplayableIds)
        
        assertEquals(1, recommendations.size)
        assertEquals(dupId, recommendations[0].mediaId)
        assertEquals(CleanupCategory.UNPLAYABLE_FILES, recommendations[0].category)
    }

    @Test
    fun testRedundantItem_RetainsOneMaster() {
        val id1 = "dup-1" // Master (Higher score)
        val id2 = "dup-2"
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.8f), 
            createResult(id2, score = 0.5f)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        // Only one should be recommended (the non-master)
        assertEquals(1, recommendations.size)
        assertEquals(id2, recommendations[0].mediaId)
        assertEquals(CleanupCategory.REDUNDANT, recommendations[0].category)
    }

    @Test
    fun testRedundantItem_TieBreakQuality() {
        val id1 = "low-res" 
        val id2 = "high-res" // Master (Higher resolution)
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.5f), 
            createResult(id2, score = 0.5f)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash, width = 100, height = 100),
            id2 to createMetadata(id2, hash = hash, width = 2000, height = 2000)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(id1, recommendations[0].mediaId)
    }

    @Test
    fun testRedundantItem_ThreeDuplicates_RetainsOne() {
        val id1 = "m1" // Master
        val id2 = "m2"
        val id3 = "m3"
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.9f),
            createResult(id2, score = 0.8f),
            createResult(id3, score = 0.7f)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash),
            id3 to createMetadata(id3, hash = hash)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(2, recommendations.size)
        assertTrue(recommendations.any { it.mediaId == id2 })
        assertTrue(recommendations.any { it.mediaId == id3 })
        assertFalse(recommendations.any { it.mediaId == id1 })
    }

    @Test
    fun testRedundantItem_DeterministicTie() {
        val id1 = "b"
        val id2 = "a" // Master (alphabetically first ID)
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.5f), 
            createResult(id2, score = 0.5f)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(id1, recommendations[0].mediaId)
    }

    @Test
    fun testStorageCalculation() {
        val recommendations = listOf(
            CleanupRecommendation("1", 0.1f, 0.9f, CleanupCategory.DELETE_RECOMMENDATIONS, emptyList(), 100L, explanation = ""),
            CleanupRecommendation("2", 0.1f, 0.9f, CleanupCategory.DELETE_RECOMMENDATIONS, emptyList(), 200L, explanation = "")
        )
        
        val total = CleanupRecommendationEngine.calculatePotentialRecovery(recommendations)
        assertEquals(300L, total)
    }
}
