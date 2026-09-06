package com.example.data

import com.example.compatibility.AuraMediaCompatibilityEngine

import com.example.data.intelligence.IntelligenceRequest
import com.example.data.intelligence.IntelligenceMode
import com.example.data.intelligence.ContextualIntent

object RecommendationEngine {

    data class DiscoverCategories(
        val nextObsession: MediaItem?,
        val freshForYou: List<MediaItem>,
        val underTheRadar: List<MediaItem>,
        val aLittleDifferent: List<MediaItem>,
        val fromYourFavorites: List<MediaItem>,
        val continueWatching: MediaItem?,
        val wildcard: List<MediaItem> = emptyList(),
        val deepDiscovery: List<MediaItem> = emptyList()
    )

    suspend fun computeDiscoverCategories(
        repository: MediaRepository,
        tasteDNA: TasteDNA = TasteDNA(),
        profile: TasteDNA.PreferenceProfile = TasteDNA.PreferenceProfile(),
        stats: IntelligenceStats = IntelligenceStats(),
        creatorProfiles: Map<String, CreatorProfile> = emptyMap()
    ): DiscoverCategories {
        val core = repository.intelligenceCore ?: return DiscoverCategories(null, emptyList(), emptyList(), emptyList(), emptyList(), null)
        
        // 1. Hero / Next Obsession
        val heroRequest = IntelligenceRequest(
            mode = IntelligenceMode.DISCOVER,
            contextualIntent = ContextualIntent.DISCOVER_CATEGORY,
            limit = 1,
            tasteDNA = tasteDNA,
            profile = profile,
            stats = stats,
            creatorProfiles = creatorProfiles
        )
        val heroResponse = core.processRequest(heroRequest)
        val nextObsession = heroResponse.candidates.firstOrNull()?.item

        // 2. Specialized Categories (Phased Core Requests)
        // For Update 9, we reuse the Core's DISCOVER mode with category-specific policies
        
        suspend fun getCategory(option: String, limit: Int): List<MediaItem> {
            val req = IntelligenceRequest(
                mode = IntelligenceMode.DISCOVER,
                contextualIntent = ContextualIntent.DISCOVER_CATEGORY,
                limit = limit,
                sortOption = option,
                tasteDNA = tasteDNA,
                profile = profile,
                stats = stats,
                creatorProfiles = creatorProfiles
            )
            return core.processRequest(req).candidates.map { it.item }
        }

        val freshForYou = getCategory("FRESH_FOR_YOU", 4)
        val fromYourFavorites = getCategory("FROM_YOUR_FAVORITES", 4)
        val wildcard = getCategory("WILDCARD", 3)
        val deepDiscovery = getCategory("DEEP_DISCOVERY", 3)
        val underTheRadar = getCategory("UNDER_THE_RADAR", 4)
        val aLittleDifferent = getCategory("A_LITTLE_DIFFERENT", 4)

        // 0. Continue Watching: Item in progress (Deterministic, preserved)
        val itemsOnly = repository.mediaItems.value.filter { 
            it.itemCount == null && 
            AuraMediaCompatibilityEngine.isEligibleForImport(it.compatibilityStatus) &&
            !it.isDeleted
        }
        val continueWatching = itemsOnly.filter { it.progress > 0f }.maxByOrNull { it.progress }

        return DiscoverCategories(
            nextObsession = nextObsession,
            freshForYou = freshForYou,
            underTheRadar = underTheRadar,
            aLittleDifferent = aLittleDifferent,
            fromYourFavorites = fromYourFavorites,
            continueWatching = continueWatching,
            wildcard = wildcard,
            deepDiscovery = deepDiscovery
        )
    }

    suspend fun computeObsessions(
        repository: MediaRepository,
        tasteDNA: TasteDNA = TasteDNA(),
        profile: TasteDNA.PreferenceProfile = TasteDNA.PreferenceProfile(),
        stats: IntelligenceStats = IntelligenceStats(),
        creatorProfiles: Map<String, CreatorProfile> = emptyMap()
    ): List<ObsessionRecommendation> {
        val categories = computeDiscoverCategories(repository, tasteDNA, profile, stats, creatorProfiles)
        val obsessions = mutableListOf<ObsessionRecommendation>()

        // 1. Hero / Next Obsession
        categories.nextObsession?.let { item ->
            obsessions.add(ObsessionRecommendation(
                id = "hero_${item.id}",
                title = "Your Next Obsession",
                subtitle = item.selectionReason ?: "Highest predicted match based on your vibe",
                strategy = ObsessionStrategy.Hero,
                previewItems = listOf(item),
                confidenceScore = 0.95f,
                emotionalRole = EmotionalRole.HIGH_CONFIDENCE
            ))
        }

        // 2. Fresh Arrivals
        if (categories.freshForYou.isNotEmpty()) {
            obsessions.add(ObsessionRecommendation(
                id = "fresh_arrivals",
                title = "Fresh Arrivals",
                subtitle = "Recently discovered content matching your evolving taste",
                strategy = ObsessionStrategy.FreshArrivals,
                previewItems = categories.freshForYou.take(3),
                confidenceScore = 0.85f,
                emotionalRole = EmotionalRole.EMERGING_INTEREST
            ))
        }

        // 3. The Remix
        if (categories.fromYourFavorites.isNotEmpty()) {
            obsessions.add(ObsessionRecommendation(
                id = "fav_remix",
                title = "The Remix",
                subtitle = "New discoveries that feel like your saved favorites",
                strategy = ObsessionStrategy.FavoriteRemix,
                previewItems = categories.fromYourFavorites.take(3),
                confidenceScore = 0.80f,
                emotionalRole = EmotionalRole.DEEPENING
            ))
        }

        return obsessions
    }

    /**
     * Specialized Pairwise Selection logic preserved in RecommendationEngine.
     * While candidate pools are now Core-driven, the specific information-value
     * and diversity logic for Pairwise selection remains here for the Update 9 MVP.
     */
    suspend fun getTop100PairwiseCandidates(
        repository: MediaRepository,
        winsMap: Map<String, Int> = emptyMap(),
        lossesMap: Map<String, Int> = emptyMap(),
        mediaTypeFilter: String = "ALL",
        tasteDNA: TasteDNA = TasteDNA(),
        profile: TasteDNA.PreferenceProfile = TasteDNA.PreferenceProfile(),
        strategy: RecommendationStrategy? = null,
        stats: IntelligenceStats = IntelligenceStats(),
        creatorProfiles: Map<String, CreatorProfile> = emptyMap(),
        compareStrategy: CompareStrategy = CompareStrategy.PERSONALIZED,
        compareSort: CompareSortOption = CompareSortOption.RECOMMENDED
    ): List<Pair<MediaItem, Float>> {
        val core = repository.intelligenceCore
        if (core != null) {
            val req = IntelligenceRequest(
                mode = IntelligenceMode.SORT,
                sortOption = "RANKING_REFINEMENT",
                filterType = mediaTypeFilter,
                limit = 100,
                tasteDNA = tasteDNA,
                profile = profile,
                stats = stats,
                creatorProfiles = creatorProfiles
            )
            val response = core.processRequest(req)
            if (response.isSuccess) {
                return response.candidates.map { it.item to it.rankScore.toFloat() }
            }
        }
        
        // Final fallback (Safe degraded state)
        return repository.mediaItems.value.shuffled().take(100).map { it to 1.0f }
    }

    fun selectNextPairFromPool(
        top100Pool: List<Pair<MediaItem, Float>>,
        comparisonCounts: Map<String, Int> = emptyMap(),
        recentPairs: List<Pair<String, String>> = emptyList(),
        recentItemIds: List<String> = emptyList(),
        mediaTypeFilter: String = "ALL",
        randomSeed: Long = System.currentTimeMillis(),
        strategy: RecommendationStrategy? = null,
        tasteDNA: TasteDNA = TasteDNA(),
        creatorProfiles: Map<String, CreatorProfile> = emptyMap(),
        compareStrategy: CompareStrategy = CompareStrategy.PERSONALIZED
    ): Pair<MediaItem, MediaItem>? {
        val filteredPool = top100Pool.filter { (item, _) ->
            when (mediaTypeFilter.uppercase()) {
                "PHOTO", "PHOTOS" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE")
                "VIDEO", "VIDEOS" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE")
                else -> true
            }
        }

        if (filteredPool.size < 2) return null

        val poolItems = filteredPool.map { it.first }
        var bestPair: Pair<MediaItem, MediaItem>? = null
        var maxScore = -1e9f

        val random = kotlin.random.Random(randomSeed)
        val maxIndex = filteredPool.size.coerceAtMost(30)
        
        for (i in 0 until maxIndex) {
            val itemA = poolItems[i]
            for (j in i + 1 until filteredPool.size) {
                val itemB = poolItems[j]
                
                val scoreA = filteredPool.find { it.first.id == itemA.id }?.second ?: 0f
                val scoreB = filteredPool.find { it.first.id == itemB.id }?.second ?: 0f
                val avgRelevance = (scoreA + scoreB) / 2.0f

                val expectedA = PairwiseEloEngine.calculateExpectedScore(itemA.eloRating, itemB.eloRating)
                val infoValue = PairwiseEloEngine.calculateInformationValue(expectedA).toFloat() * 100.0f
                val diversity = if (itemA.genre != itemB.genre || itemA.mediaType != itemB.mediaType) 1.5f else 0.0f

                val exactPairRepeat = recentPairs.any {
                    (it.first == itemA.id && it.second == itemB.id) || (it.first == itemB.id && it.second == itemA.id)
                }
                val repetitionPenalty = if (exactPairRepeat) 100.0f else 0.0f

                val recentCountA = recentItemIds.count { it == itemA.id }
                val recentCountB = recentItemIds.count { it == itemB.id }
                val itemRepetitionPenalty = (recentCountA + recentCountB) * 15.0f

                val totalValue = avgRelevance + infoValue + diversity - repetitionPenalty - itemRepetitionPenalty + (random.nextFloat() * 0.5f)

                if (totalValue > maxScore) {
                    maxScore = totalValue
                    bestPair = itemA to itemB
                }
            }
        }

        return bestPair ?: (poolItems[0] to poolItems[1])
    }
}
