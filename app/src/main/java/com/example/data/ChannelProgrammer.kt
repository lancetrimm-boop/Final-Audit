package com.example.data

import com.example.data.metv.MeTVProgrammingEngine
import java.util.Calendar
import java.util.Random

/**
 * Main Programmer for Aura Channels.
 * Maps channel definitions to their respective Lane Strategies and enforces 100% media-type homogeneity.
 * Respects app-wide session and persistent exposure signals with strong repetition penalties.
 */
class ChannelProgrammer(
    private val meTvEngine: MeTVProgrammingEngine = MeTVProgrammingEngine()
) {

    private val strategies: Map<String, LaneStrategy> = mapOf(
        "ME_TV" to MeTvLaneStrategy(meTvEngine),
        "FAVORITES" to FavoritesLaneStrategy(),
        "REDISCOVER" to RediscoverLaneStrategy(),
        "CONTINUE" to ContinueLaneStrategy(),
        "MOOD" to MoodLaneStrategy(),
        "NOVELTY" to NoveltyLaneStrategy(),
        "ERA" to EraLaneStrategy(),
        "DAYPARTS" to DaypartsLaneStrategy(),
        "SEASONAL_POPUPS" to SeasonalPopupsLaneStrategy(),
        "SCOPED_RANDOM" to ScopedRandomLaneStrategy(),
        "SEARCH_SEEDED" to SearchSeededLaneStrategy()
    )

    /**
     * Programs a block of media for the given channel, respecting exposure signals.
     */
    fun programChannel(
        channel: Channel,
        context: ChannelProgrammingContext,
        limit: Int = 20
    ): List<MediaItem> {
        val strategy = strategies[channel.strategyId] ?: SearchSeededLaneStrategy()
        val rawItems = strategy.programBlock(channel, context, limit)

        // Enforce strict media-type homogeneity
        val isVideoFilter = context.filterType.equals("VIDEO", ignoreCase = true) || context.filterType.equals("VIDEOS", ignoreCase = true) || context.filterType.equals("MOVIE", ignoreCase = true) || context.filterType.equals("MOVIES", ignoreCase = true)
        return rawItems.filter { item ->
            val isVid = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("MOVIE", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true) || item.mediaType.startsWith("MOVIE", ignoreCase = true)
            if (isVideoFilter) isVid else !isVid
        }.take(limit)
    }

    /**
     * Helper to filter pool by media type.
     */
    internal companion object {
        fun filterPool(pool: List<MediaItem>, filterType: String): List<MediaItem> {
            val isVideoFilter = filterType.equals("VIDEO", ignoreCase = true) || filterType.equals("VIDEOS", ignoreCase = true) || filterType.equals("MOVIE", ignoreCase = true) || filterType.equals("MOVIES", ignoreCase = true)
            return pool.filter { item ->
                if (!item.isEligibleForLibraryAndChannels()) return@filter false
                val isVid = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("MOVIE", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true) || item.mediaType.startsWith("MOVIE", ignoreCase = true)
                if (isVideoFilter) isVid else !isVid
            }
        }
    }
}

// --- LANE STRATEGY IMPLEMENTATIONS ---

/**
 * 1. Me TV Strategy — Delegates directly to existing authoritative MeTVProgrammingEngine with session exposure integration.
 */
class MeTvLaneStrategy(
    private val meTvEngine: MeTVProgrammingEngine
) : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val result = meTvEngine.program(
            availableMedia = context.availableMedia,
            filterType = context.filterType,
            tasteDNA = context.tasteDNA,
            programmingFeedback = context.programmingFeedback,
            exposureMap = context.exposureMap,
            skipEvents = context.skipEvents,
            experienceRequest = context.experienceRequest,
            currentTimeMs = context.currentTimeMs,
            targetCount = limit,
            refreshEpoch = context.refreshEpoch,
            sessionExposures = context.sessionExposures
        )
        return result.items
    }
}

/**
 * 2. Unified Rediscover Strategy — Discovery + Rediscovery.
 * Combines low-exposure / unseen discovery candidates (Pool A) and forgotten previously-viewed candidates (>30 days unwatched, Pool B).
 * Respects both persistent exposure and short-term session exposure penalties.
 */
class RediscoverLaneStrategy : LaneStrategy {
    private val REDISCOVER_THRESHOLD_MS = 30L * 24 * 60 * 60 * 1000

    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        if (pool.isEmpty()) return emptyList()

        val skipCounts = context.skipEvents.groupingBy { it.mediaId }.eachCount()

        // Pool A: Unseen & Low-Exposure Candidates (Novelty Discovery) with Session + Persistent Exposure Penalty
        val poolA = pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val skips = skipCounts[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2) + skips
            val score = 1.0f / (1.0f + totalExp)
            item to score
        }.sortedWith(compareByDescending<Pair<MediaItem, Float>> { it.second }.thenBy { it.first.id })
            .map { it.first }

        // Pool B: Forgotten Candidates (Exposed items viewed > 30 days ago, penalizing active session exposure)
        val poolB = pool.filter { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val lastViewed = item.lastViewedTimestamp ?: 0L
            sessionExp == 0 && exp > 0 && (lastViewed == 0L || (context.currentTimeMs - lastViewed) > REDISCOVER_THRESHOLD_MS)
        }.sortedWith(compareBy<MediaItem> { it.lastViewedTimestamp ?: 0L }.thenBy { it.id })

        // Unified Composition: Interleave Pool B (Forgotten) and Pool A (Unseen/Low Exposure)
        val result = mutableListOf<MediaItem>()
        val seen = mutableSetOf<String>()

        var indexA = 0
        var indexB = 0

        while (result.size < limit && (indexA < poolA.size || indexB < poolB.size)) {
            if (indexB < poolB.size) {
                val candidateB = poolB[indexB++]
                if (seen.add(candidateB.id)) {
                    result.add(candidateB)
                }
            }
            if (result.size >= limit) break

            if (indexA < poolA.size) {
                val candidateA = poolA[indexA++]
                if (seen.add(candidateA.id)) {
                    result.add(candidateA)
                }
            }
        }

        return result.take(limit)
    }
}

/**
 * 3. Favorites Strategy — Favorited media with unwatched-first priority and strong exposure dampening.
 */
class FavoritesLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val favorites = pool.filter { it.isFavorite }
        if (favorites.isEmpty()) return emptyList()

        val skipCounts = context.skipEvents.groupingBy { it.mediaId }.eachCount()

        return favorites.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val skips = skipCounts[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2) + skips
            val score = if (totalExp == 0) 3.0f else 2.0f - (totalExp * 0.15f)
            item to score
        }.sortedWith(
            compareByDescending<Pair<MediaItem, Float>> { it.second }
                .thenBy { (it.first.id.hashCode() xor context.refreshEpoch).toString() }
        )
            .map { it.first }
            .take(limit)
    }
}

/**
 * 4. Mood Strategy — Evaluates TasteDNA visual dimensions + ExperienceRequest mood steering with exposure penalties.
 */
class MoodLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val nudge = context.experienceRequest?.moodNudge?.lowercase() ?: ""
        val skipCounts = context.skipEvents.groupingBy { it.mediaId }.eachCount()

        return pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val skips = skipCounts[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2) + skips
            var score = 0.5f + (item.rating / 10.0f) - (totalExp * 0.1f)
            if (nudge.contains("energetic") && (item.genre.contains("Action", ignoreCase = true) || item.title.contains("Dynamic", ignoreCase = true))) {
                score += 0.3f
            } else if (nudge.contains("serene") && (item.genre.contains("Atmospheric", ignoreCase = true) || item.title.contains("Serene", ignoreCase = true))) {
                score += 0.3f
            } else if (nudge.contains("cinematic") && (item.genre.contains("Cinematic", ignoreCase = true) || item.title.contains("Vibrant", ignoreCase = true))) {
                score += 0.3f
            }
            item to score
        }.sortedWith(
            compareByDescending<Pair<MediaItem, Float>> { it.second }
                .thenBy { (it.first.id.hashCode() xor context.refreshEpoch).toString() }
        )
            .map { it.first }
            .take(limit)
    }
}

/**
 * 5. Continue Strategy — Serialized series continue, selecting oldest unwatched episode with zero session/persistent exposure.
 */
class ContinueLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val serializedGroups = pool.filter { !it.parentContentId.isNullOrBlank() }.groupBy { it.parentContentId!! }

        val result = mutableListOf<MediaItem>()
        serializedGroups.forEach { (_, group) ->
            val oldestUnwatched = group.filter { 
                val exp = (context.exposureMap[it.id] ?: 0) + (context.sessionExposures[it.id] ?: 0)
                exp == 0 
            }.sortedWith(compareBy<MediaItem> { it.selectionReason ?: "" }.thenBy { it.id })
                .firstOrNull() ?: group.filter {
                    val sessionExp = context.sessionExposures[it.id] ?: 0
                    sessionExp == 0
                }.sortedWith(compareBy<MediaItem> { it.selectionReason ?: "" }.thenBy { it.id }).firstOrNull()
                ?: group.sortedWith(compareBy<MediaItem> { it.selectionReason ?: "" }.thenBy { it.id }).firstOrNull()

            if (oldestUnwatched != null) {
                result.add(oldestUnwatched)
            }
        }
        return result.take(limit)
    }
}

/**
 * Novelty Strategy — Strongest preference for zero exposure items.
 */
class NoveltyLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val skipCounts = context.skipEvents.groupingBy { it.mediaId }.eachCount()

        return pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val skips = skipCounts[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2) + skips
            val score = 1.0f / (1.0f + totalExp)
            item to score
        }.sortedWith(compareByDescending<Pair<MediaItem, Float>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .take(limit)
    }
}

class EraLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        return pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2)
            val score = item.year.toFloat() - (totalExp * 2.0f)
            item to score
        }.sortedWith(compareByDescending<Pair<MediaItem, Float>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .take(limit)
    }
}

class DaypartsLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val calendar = Calendar.getInstance().apply { timeInMillis = context.currentTimeMs }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val daypart = when (hour) {
            in 5..11 -> "MORNING"
            in 12..16 -> "AFTERNOON"
            in 17..22 -> "EVENING"
            else -> "NIGHT"
        }

        return pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2)
            val boost = when (daypart) {
                "EVENING", "NIGHT" -> if (item.genre.contains("Cinematic", ignoreCase = true) || item.genre.contains("Action", ignoreCase = true)) 0.2f else 0.0f
                "MORNING" -> if (item.genre.contains("Atmospheric", ignoreCase = true) || item.genre.contains("Serene", ignoreCase = true)) 0.2f else 0.0f
                else -> 0.1f
            }
            val score = (0.5f + boost) - (totalExp * 0.1f)
            item to score
        }.sortedWith(compareByDescending<Pair<MediaItem, Float>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .take(limit)
    }
}

class SeasonalPopupsLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        return pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2)
            val score = item.rating - (totalExp * 0.2f)
            item to score
        }.sortedWith(compareByDescending<Pair<MediaItem, Float>> { it.second }.thenBy { it.first.id })
            .map { it.first }
            .take(limit)
    }
}

class ScopedRandomLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        val seed = context.currentTimeMs + channel.id.hashCode() + context.refreshEpoch
        val random = Random(seed)
        return pool.sortedBy { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val totalExp = exp + (sessionExp * 3)
            (totalExp * 10f) + (random.nextFloat() * 5f)
        }.take(limit)
    }
}

/**
 * Search-Seeded Strategy — Dynamic block programming from saved search seeds respecting exposure penalties.
 */
class SearchSeededLaneStrategy : LaneStrategy {
    override fun programBlock(channel: Channel, context: ChannelProgrammingContext, limit: Int): List<MediaItem> {
        val pool = ChannelProgrammer.filterPool(context.availableMedia, context.filterType)
        if (pool.isEmpty()) return emptyList()

        val query = channel.query.lowercase().trim()
        val refIds = channel.referenceMediaIds.toSet()
        val skipCounts = context.skipEvents.groupingBy { it.mediaId }.eachCount()

        val scored = pool.map { item ->
            val exp = context.exposureMap[item.id] ?: 0
            val sessionExp = context.sessionExposures[item.id] ?: 0
            val skips = skipCounts[item.id] ?: 0
            val totalExp = exp + (sessionExp * 2) + skips
            var score = 0.5f - (totalExp * 0.1f)
            if (query.isNotEmpty()) {
                if (item.title.lowercase().contains(query)) score += 0.4f
                if (item.genre.lowercase().contains(query)) score += 0.3f
            }
            if (refIds.contains(item.id)) {
                score += 0.5f
            }
            item to score
        }

        return scored.sortedWith(
            compareByDescending<Pair<MediaItem, Float>> { it.second }
                .thenBy { (it.first.id.hashCode() xor context.refreshEpoch).toString() }
        )
            .map { it.first }
            .take(limit)
    }
}
