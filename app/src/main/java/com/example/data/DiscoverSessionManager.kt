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
            val mainItem = obsession.previewItems.firstOrNull()
            val strategy = resolveStrategy(obsession.strategy, repository.discoveryPolicy.value, systemState, tasteDNA, profile)
            val explanation = if (mainItem != null) {
                RecommendationExplanationGenerator.generate(mainItem, tasteDNA, stats, creatorProfiles, strategy)
            } else null
            
            obsession.copy(explanation = explanation)
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
        val systemState = ConfidenceEngine.calculateDiscoveryState(allMedia, stats)
        val resolvedStrategy = resolveStrategy(obsession.strategy, policy, systemState, tasteDNA, profile)

        // Ensure preview items from the feed are included at the start of the first batch
        val isFirstBatch = existingItems.isEmpty()
        val baseItems = if (isFirstBatch) {
            obsession.previewItems.map { item ->
                val reason = item.selectionReason
                if (reason == null || !reason.contains("% Match")) {
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creatorProfiles)
                    val matchPercent = (evidence.exploitationScore * 100).toInt().coerceIn(10, 99)
                    item.copy(selectionReason = "$matchPercent% Match")
                } else item
            }
        } else existingItems

        val items = if (core != null) {
            val request = com.example.data.intelligence.IntelligenceRequest(
                mode = com.example.data.intelligence.IntelligenceMode.DISCOVER,
                contextualIntent = com.example.data.intelligence.ContextualIntent.DISCOVER_CATEGORY,
                limit = 50, // Request larger pool for session filtering
                tasteDNA = tasteDNA,
                profile = profile,
                stats = stats,
                creatorProfiles = creatorProfiles
            )
            val response = core.processRequest(request)
            response.candidates
                .filter { it.item.id !in sessionSeenIds && !isContentSeen(it.item) && it.item.id !in baseItems.map { b -> b.id } }
                .take(12)
                .map { it.item.copy(selectionReason = "${(it.primaryRelevanceScore * 100).toInt().coerceIn(10, 99)}% Match") }
        } else {
            // Minimal Fallback
            emptyList()
        }

        items.forEach { markUsed(it) }

        val combinedItems = baseItems + items
        
        // Generate explanations for the new items in the batch using the resolved strategy
        val batchExplanations = mutableMapOf<String, RecommendationExplanation>()
        
        // If first batch, add the hero explanation if available
        if (isFirstBatch && obsession.explanation != null && obsession.previewItems.isNotEmpty()) {
            batchExplanations[obsession.previewItems.first().id] = obsession.explanation
        }

        items.forEach { item ->
            val exp = RecommendationExplanationGenerator.generate(item, tasteDNA, stats, creatorProfiles, resolvedStrategy)
            if (exp != null) batchExplanations[item.id] = exp
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

