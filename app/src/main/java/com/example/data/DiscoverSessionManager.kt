package com.example.data

import com.example.compatibility.AuraMediaCompatibilityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Domain-level orchestrator for the redesigned Discover session.
 * Manages Layer 1 (Cluster generation) and Layer 2 (Batch realization).
 */
class DiscoverSessionManager {
    private val sessionSeenIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sessionSeenContentIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var currentSessionId: String = java.util.UUID.randomUUID().toString()

    /**
     * LAYER 1: Generates the vertically scrolling obsession clusters and wraps them in a snapshot.
     */
    suspend fun generateSnapshot(
        repository: MediaRepository,
        tasteDNA: TasteDNA,
        profile: TasteDNA.PreferenceProfile,
        stats: IntelligenceStats,
        creatorProfiles: Map<String, CreatorProfile>,
        forceNewSession: Boolean = false
    ): DiscoverSnapshot = withContext(Dispatchers.Default) {
        if (forceNewSession) {
            currentSessionId = java.util.UUID.randomUUID().toString()
            sessionSeenIds.clear()
            sessionSeenContentIds.clear()
        }

        val obsessions = RecommendationEngine.computeObsessions(
            repository, tasteDNA, profile, stats, creatorProfiles
        )

        val systemState = ConfidenceEngine.calculateDiscoveryState(repository.mediaItems.value, stats)

        // Attach explanations and pre-mark previews as seen
        val explainedObsessions = obsessions.map { obsession ->
            obsession
        }

        explainedObsessions.forEach { obsession ->
            obsession.previewItems.forEach { markUsed(it) }
        }

        DiscoverSnapshot(
            sessionId = currentSessionId,
            obsessions = explainedObsessions,
            systemState = systemState,
            seenIds = sessionSeenIds.toSet()
        )
    }

    /**
     * LAYER 2: Realizes a finite batch of items for a specific obsession.
     */
    suspend fun realizeBatch(
        repository: MediaRepository,
        obsession: ObsessionRecommendation,
        allMedia: List<MediaItem>,
        tasteDNA: TasteDNA,
        profile: TasteDNA.PreferenceProfile,
        policy: DiscoveryPolicy,
        stats: IntelligenceStats,
        creatorProfiles: Map<String, CreatorProfile>,
        existingItems: List<MediaItem> = emptyList()
    ): ObsessionContentBatch = withContext(Dispatchers.Default) {
        val core = repository.intelligenceCore
        
        // Ensure preview items from the feed are included at the start of the first batch
        val isFirstBatch = existingItems.isEmpty()
        val baseItems = if (isFirstBatch) obsession.previewItems else existingItems

        val candidateBatch = if (core != null) {
            val request = com.example.data.intelligence.IntelligenceRequest(
                mode = com.example.data.intelligence.IntelligenceMode.DISCOVER,
                contextualIntent = com.example.data.intelligence.ContextualIntent.DISCOVER_CATEGORY,
                limit = 50, // Request larger pool for session filtering
                tasteDNA = tasteDNA,
                profile = profile,
                stats = stats,
                creatorProfiles = creatorProfiles,
                sortOption = obsession.strategy.javaClass.simpleName.uppercase() // Map strategy to sortOption
            )
            val response = core.processRequest(request)
            response.candidates
                .filter { it.item.id !in sessionSeenIds && !isContentSeen(it.item) && it.item.id !in baseItems.map { b -> b.id } }
                .take(12)
        } else {
            // Minimal Fallback
            emptyList()
        }

        val items = candidateBatch.map { it.item }
        items.forEach { markUsed(it) }

        val combinedItems = baseItems + items
        
        // Generate explanations for the new items in the batch
        val batchExplanations = mutableMapOf<String, RecommendationExplanation>()
        
        candidateBatch.forEach { candidate ->
            val exp = RecommendationExplanationGenerator.generate(candidate, tasteDNA)
            if (exp != null) batchExplanations[candidate.item.id] = exp
        }

        ObsessionContentBatch(
            obsessionId = obsession.id,
            items = combinedItems,
            canExpand = items.size >= 12,
            batchIndex = if (isFirstBatch) 0 else combinedItems.size / 12,
            explanations = batchExplanations
        )
    }

    private fun resolveStrategy(
        strategy: ObsessionStrategy,
        policy: DiscoveryPolicy,
        systemState: SystemDiscoveryState,
        tasteDNA: TasteDNA,
        profile: TasteDNA.PreferenceProfile
    ): RecommendationStrategy {
        return when (strategy) {
            is ObsessionStrategy.Hero -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(), RecommendationObjective.GENERAL_DISCOVERY, systemState, tasteDNA, profile)
            is ObsessionStrategy.FreshArrivals -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(), RecommendationObjective.NOVELTY_INJECTION, systemState, tasteDNA, profile)
            is ObsessionStrategy.FavoriteRemix -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(), RecommendationObjective.CHILL_EXPLOITATION, systemState, tasteDNA, profile)
            is ObsessionStrategy.HiddenGems -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(), RecommendationObjective.GENERAL_DISCOVERY, systemState, tasteDNA, profile)
            is ObsessionStrategy.NoveltyPulse -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(focus = IntentFocus.SURPRISE_ME), RecommendationObjective.GENERAL_DISCOVERY, systemState, tasteDNA, profile)
            else -> DiscoveryPolicyManager.resolveStrategy(policy, UserIntent(), RecommendationObjective.GENERAL_DISCOVERY, systemState, tasteDNA, profile)
        }
    }

    private fun markUsed(item: MediaItem) {
        sessionSeenIds.add(item.id)
        val contentId = item.parentContentId ?: item.contentHash ?: item.id
        sessionSeenContentIds.add(contentId)
    }

    private fun isContentSeen(item: MediaItem): Boolean {
        val contentId = item.parentContentId ?: item.contentHash ?: item.id
        return sessionSeenContentIds.contains(contentId)
    }
}

data class ObsessionContentBatch(
    val obsessionId: String,
    val items: List<MediaItem>,
    val canExpand: Boolean,
    val batchIndex: Int = 0,
    val explanations: Map<String, RecommendationExplanation> = emptyMap()
)

