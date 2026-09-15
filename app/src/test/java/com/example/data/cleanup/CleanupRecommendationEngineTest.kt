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
    fun testForgottenItem_CategorizedCorrectly() {
        val id = "forgotten-1"
        val results = listOf(createResult(id, CleanupCategory.FORGOTTEN))
        val metadata = mapOf(id to createMetadata(id, exposure = 50, views = 0))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.FORGOTTEN, recommendations[0].category)
        assertTrue(recommendations[0].explanation.contains("50 times"))
    }

    @Test
    fun testNeverConnectedItem_CategorizedCorrectly() {
        val id = "never-1"
        val results = listOf(createResult(id, CleanupCategory.NEVER_CONNECTED, listOf(CleanupReason.LOW_TASTE_ALIGNMENT)))
        val metadata = mapOf(id to createMetadata(id))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.NEVER_CONNECTED, recommendations[0].category)
    }

    @Test
    fun testSpaceHog_CategorizedCorrectly() {
        val id = "huge-1"
        val size = 2L * 1024 * 1024 * 1024 // 2GB
        val results = listOf(createResult(id, CleanupCategory.SPACE_HOGS, score = 0.2f))
        val metadata = mapOf(id to createMetadata(id, size = size, type = "VIDEO"))
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(CleanupCategory.SPACE_HOGS, recommendations[0].category)
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
    fun testRedundantItem_FavoriteProtection() {
        val id1 = "normal"
        val id2 = "fav" // Master (Higher score usually, but engine ensures master is never REDUNDANT)
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.4f), 
            createResult(id2, score = 0.9f, category = CleanupCategory.NONE)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash).copy(isFavorite = true)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(id1, recommendations[0].mediaId)
        assertEquals(CleanupCategory.REDUNDANT, recommendations[0].category)
    }

    @Test
    fun testRedundantItem_MultipleGroups() {
        val hash1 = "hash-1"
        val hash2 = "hash-2"
        
        val results = listOf(
            createResult("1a", score = 0.9f),
            createResult("1b", score = 0.1f),
            createResult("2a", score = 0.9f),
            createResult("2b", score = 0.1f)
        )
        val metadata = mapOf(
            "1a" to createMetadata("1a", hash = hash1),
            "1b" to createMetadata("1b", hash = hash1),
            "2a" to createMetadata("2a", hash = hash2),
            "2b" to createMetadata("2b", hash = hash2)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(2, recommendations.size)
        assertTrue(recommendations.any { it.mediaId == "1b" })
        assertTrue(recommendations.any { it.mediaId == "2b" })
    }

    @Test
    fun testRedundantItem_CategoryPrecedence_NonMaster() {
        val id1 = "master"
        val id2 = "forgotten-and-dup"
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.9f),
            createResult(id2, score = 0.1f, category = CleanupCategory.FORGOTTEN)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(1, recommendations.size)
        assertEquals(id2, recommendations[0].mediaId)
        assertEquals(CleanupCategory.REDUNDANT, recommendations[0].category)
    }

    @Test
    fun testRedundantItem_MasterStaysForgotten() {
        val id1 = "forgotten-master"
        val id2 = "even-worse-dup"
        val hash = "fixed-hash"
        
        val results = listOf(
            createResult(id1, score = 0.2f, category = CleanupCategory.FORGOTTEN),
            createResult(id2, score = 0.1f, category = CleanupCategory.FORGOTTEN)
        )
        val metadata = mapOf(
            id1 to createMetadata(id1, hash = hash),
            id2 to createMetadata(id2, hash = hash)
        )
        
        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)
        
        assertEquals(2, recommendations.size)
        val rec1 = recommendations.find { it.mediaId == id1 }!!
        val rec2 = recommendations.find { it.mediaId == id2 }!!
        
        assertEquals(CleanupCategory.FORGOTTEN, rec1.category)
        assertEquals(CleanupCategory.REDUNDANT, rec2.category)
    }

    @Test
    fun testStorageCalculation() {
        val recommendations = listOf(
            CleanupRecommendation("1", 0.1f, 0.9f, CleanupCategory.FORGOTTEN, emptyList(), 100L, explanation = ""),
            CleanupRecommendation("2", 0.1f, 0.9f, CleanupCategory.FORGOTTEN, emptyList(), 200L, explanation = "")
        )
        
        val total = CleanupRecommendationEngine.calculatePotentialRecovery(recommendations)
        assertEquals(300L, total)
    }
}
