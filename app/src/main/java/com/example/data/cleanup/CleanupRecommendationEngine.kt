package com.example.data.cleanup

import kotlin.math.min

/**
 * Transforms raw intelligence scores into actionable cleanup recommendations.
 */
object CleanupRecommendationEngine {

    /**
     * Generates a list of recommendations for a set of items.
     * Handles single-item categorization and cross-item duplicate detection.
     */
    fun generateRecommendations(
        results: List<KeepScoreResult>,
        itemMetadata: Map<String, CleanupItemMetadata>,
        unplayableMediaIds: Set<String> = emptySet()
    ): List<CleanupRecommendation> {
        val recommendations = mutableListOf<CleanupRecommendation>()
        val keepScoreMap = results.associateBy { it.mediaId }
        
        // 1. Group by content hash to find redundancy
        val hashGroups = itemMetadata.values.filter { it.contentHash != null }.groupBy { it.contentHash!! }
        
        val itemsToCleanupAsRedundant = mutableSetOf<String>()
        val masterInfoMap = mutableMapOf<String, Pair<String, String>>()
        
        hashGroups.filter { it.value.size > 1 }.forEach { (_, group) ->
            // Select exactly one 'Master' item to retain
            val sortedGroup = group.sortedWith(
                compareByDescending<CleanupItemMetadata> { keepScoreMap[it.mediaId]?.keepScore ?: 0f }
                    .thenByDescending { it.width * it.height }
                    .thenByDescending { it.durationMs }
                    .thenByDescending { it.viewCount }
                    .thenBy { it.mediaId }
            )
            
            val master = sortedGroup.first()
            val masterScore = keepScoreMap[master.mediaId]?.keepScore ?: 0f
            val masterLabel = master.title.ifBlank { master.mediaId }
            val rationale = "Master Retained: $masterLabel (Keep Score: ${(masterScore * 100).toInt()}% -> Resolution: ${master.width}x${master.height} -> Duration: ${master.durationMs}ms -> Views: ${master.viewCount})"

            val redundancyCandidates = sortedGroup.drop(1)
            
            redundancyCandidates.forEach { candidate ->
                itemsToCleanupAsRedundant.add(candidate.mediaId)
                masterInfoMap[candidate.mediaId] = Pair(master.mediaId, rationale)
            }
        }

        results.forEach { result ->
            val metadata = itemMetadata[result.mediaId] ?: return@forEach
            val reasons = result.reasons.toMutableList()
            
            // Require explicit negative user signals for Delete Recommendations:
            val hasExplicitNegativeUserSignal = reasons.contains(CleanupReason.REPEATED_SKIP) ||
                    reasons.contains(CleanupReason.LOW_USER_RATING)

            // Centralized Category Precedence:
            // UNPLAYABLE_FILES > REDUNDANT > SPACE_HOGS > DELETE_RECOMMENDATIONS
            val category = when {
                metadata.isFavorite -> CleanupCategory.NONE
                unplayableMediaIds.contains(result.mediaId) -> {
                    reasons.add(CleanupReason.UNPLAYABLE_MEDIA)
                    CleanupCategory.UNPLAYABLE_FILES
                }
                itemsToCleanupAsRedundant.contains(result.mediaId) -> {
                    reasons.add(CleanupReason.DUPLICATE_CONTENT)
                    CleanupCategory.REDUNDANT
                }
                metadata.sizeBytes > 100 * 1024 * 1024L -> {
                    reasons.add(CleanupReason.LARGE_FILE_SIZE)
                    CleanupCategory.SPACE_HOGS
                }
                hasExplicitNegativeUserSignal -> {
                    CleanupCategory.DELETE_RECOMMENDATIONS
                }
                else -> CleanupCategory.NONE
            }

            if (category != CleanupCategory.NONE) {
                val (masterId, masterRationale) = masterInfoMap[result.mediaId] ?: Pair(null, null)

                val rec = CleanupRecommendation(
                    mediaId = result.mediaId,
                    keepScore = result.keepScore,
                    confidenceScore = calculateConfidence(category, result, metadata),
                    category = category,
                    reasons = reasons.distinct(),
                    storageSize = metadata.sizeBytes,
                    exposureCount = metadata.exposureCount,
                    explanation = generateExplanation(category, result, metadata),
                    masterMediaId = masterId,
                    masterSelectionRationale = masterRationale
                )
                recommendations.add(rec)

                if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) {
                    val meta = mapOf(
                        "mediaId" to rec.mediaId,
                        "category" to rec.category.name,
                        "keepScore" to rec.keepScore.toString(),
                        "confidenceScore" to rec.confidenceScore.toString(),
                        "reasons" to rec.reasons.joinToString(","),
                        "masterMediaId" to (rec.masterMediaId ?: "N/A"),
                        "masterSelectionRationale" to (rec.masterSelectionRationale ?: "N/A")
                    )
                    com.example.data.intelligence.DecisionTraceCollector.logEvent(
                        requestId = "cleanup_${rec.mediaId}",
                        type = com.example.ui.models.TraceEventType.PROVENANCE_GENERATED,
                        detail = "Cleanup Decision: ${rec.category.name} | Keep Score: ${rec.keepScore}",
                        metadata = meta
                    )
                }
            }
        }
        
        return recommendations
    }

    private fun calculateConfidence(
        category: CleanupCategory,
        result: KeepScoreResult,
        metadata: CleanupItemMetadata
    ): Float {
        return when (category) {
            CleanupCategory.UNPLAYABLE_FILES -> 0.95f
            CleanupCategory.REDUNDANT -> 1.0f
            CleanupCategory.SPACE_HOGS -> {
                val sizeFactor = min(1.0f, metadata.sizeBytes / (500 * 1024 * 1024f))
                val valueFactor = 1.0f - result.keepScore
                ((sizeFactor + valueFactor) / 2.0f).coerceIn(0.6f, 1.0f)
            }
            CleanupCategory.DELETE_RECOMMENDATIONS -> (1.0f - result.keepScore).coerceIn(0.5f, 0.95f)
            CleanupCategory.NONE -> 0f
        }
    }

    private fun generateExplanation(
        category: CleanupCategory,
        result: KeepScoreResult,
        metadata: CleanupItemMetadata
    ): String {
        return when (category) {
            CleanupCategory.UNPLAYABLE_FILES -> "Confirmed playback failure — file may not play normally"
            CleanupCategory.REDUNDANT -> "Duplicate of another item"
            CleanupCategory.SPACE_HOGS -> "Large file — ${formatSize(metadata.sizeBytes)}"
            CleanupCategory.DELETE_RECOMMENDATIONS -> {
                when {
                    result.reasons.contains(CleanupReason.REPEATED_SKIP) -> "Repeatedly skipped"
                    result.reasons.contains(CleanupReason.LOW_USER_RATING) -> "Low user rating"
                    else -> "Explicit negative feedback"
                }
            }
            CleanupCategory.NONE -> ""
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        val gb = bytes / (1024 * 1024 * 1024f)
        return if (gb >= 1.0f) {
            String.format(java.util.Locale.US, "%.1f GB", gb)
        } else {
            "$mb MB"
        }
    }

    /**
     * Calculates the total potential storage recovery for a list of recommendations.
     */
    fun calculatePotentialRecovery(recommendations: List<CleanupRecommendation>): Long {
        return recommendations.sumOf { it.storageSize }
    }
}

/**
 * Minimal metadata required for recommendation logic.
 */
data class CleanupItemMetadata(
    val mediaId: String,
    val title: String = "",
    val sizeBytes: Long,
    val exposureCount: Int,
    val viewCount: Int,
    val mediaType: String,
    val contentHash: String?,
    val isFavorite: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateAdded: Long
)
