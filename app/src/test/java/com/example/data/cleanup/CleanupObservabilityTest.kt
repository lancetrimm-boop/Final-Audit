package com.example.data.cleanup

import org.junit.Assert.*
import org.junit.Test

class CleanupObservabilityTest {

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
        title: String = "Test Item",
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
        title = title,
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
    fun testKeepScoreProvenance_ReflectsProductionScoreAndFactors() {
        val input = KeepScoreInput(
            mediaId = "m1",
            fileSize = 50 * 1024 * 1024L,
            dateAdded = System.currentTimeMillis() - (200L * 24 * 60 * 60 * 1000L),
            exposureCount = 30,
            lastExposedTimestamp = System.currentTimeMillis() - (100L * 24 * 60 * 60 * 1000L),
            viewCount = 0,
            playCount = 0,
            averageWatchDuration = 0f,
            completionPercentage = 0f,
            skipCount = 5,
            rating = 0f,
            isFavorite = false,
            tasteAlignmentScore = 0.1f,
            rarityScore = 0.2f,
            contentHash = "hash1"
        )

        val prodResult = KeepScoreEngine.calculateScore(input)

        // Verify production output contains reasons and expected keep score
        assertTrue(prodResult.keepScore < 0.4f)
        assertTrue(prodResult.reasons.contains(CleanupReason.HIGH_EXPOSURE_NO_ENGAGEMENT))
        assertEquals(CleanupCategory.NONE, prodResult.category) // KeepScoreEngine delegates final category to CleanupRecommendationEngine
    }

    @Test
    fun testCategoryProvenance_ReflectsProductionRecommendationAndExplanation() {
        val id = "delete-1"
        val results = listOf(createResult(id, CleanupCategory.DELETE_RECOMMENDATIONS, listOf(CleanupReason.REPEATED_SKIP), score = 0.15f))
        val metadata = mapOf(id to createMetadata(id, title = "Forgotten Sunset", exposure = 45, views = 0))

        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)

        assertEquals(1, recommendations.size)
        val rec = recommendations[0]

        // Proves Developer Build receives exact production category, score, and explanation
        assertEquals(id, rec.mediaId)
        assertEquals(0.15f, rec.keepScore, 0.001f)
        assertEquals(CleanupCategory.DELETE_RECOMMENDATIONS, rec.category)
        assertTrue(rec.explanation.contains("Repeatedly skipped"))
        assertTrue(rec.reasons.contains(CleanupReason.REPEATED_SKIP))
    }

    @Test
    fun testDuplicateMasterProvenance_ExposesRetainedMasterAndRationale() {
        val masterId = "master-photo"
        val dupId = "duplicate-photo"
        val hash = "identical-content-hash"

        val results = listOf(
            createResult(masterId, score = 0.85f),
            createResult(dupId, score = 0.35f)
        )
        val metadata = mapOf(
            masterId to createMetadata(masterId, title = "Master High Res", hash = hash, width = 3840, height = 2160, views = 10),
            dupId to createMetadata(dupId, title = "Duplicate Low Res", hash = hash, width = 1280, height = 720, views = 1)
        )

        val recommendations = CleanupRecommendationEngine.generateRecommendations(results, metadata)

        // Only duplicate item is recommended for cleanup as REDUNDANT
        assertEquals(1, recommendations.size)
        val rec = recommendations[0]

        assertEquals(dupId, rec.mediaId)
        assertEquals(CleanupCategory.REDUNDANT, rec.category)

        // Provenance check: Master ID and selection rationale are exposed
        assertEquals(masterId, rec.masterMediaId)
        val rationale = rec.masterSelectionRationale
        assertNotNull(rationale)
        assertTrue(rationale!!.contains("Master Retained"))
        assertTrue(rationale.contains("Master High Res"))
        assertTrue(rationale.contains("3840x2160"))
    }
}
