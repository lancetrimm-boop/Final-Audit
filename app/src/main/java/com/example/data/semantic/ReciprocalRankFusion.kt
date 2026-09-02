package com.example.data.semantic

/**
 * Pure, deterministic implementation of Reciprocal Rank Fusion (RRF).
 *
 * RRF is a rank-aggregation algorithm that combines ranked candidate lists from multiple
 * independent retrieval mechanisms (e.g. keyword match, semantic vector retrieval) without
 * requiring calibration across incompatible raw score distributions.
 *
 * Mathematical formula for item $d$:
 * $$RRF(d) = \sum_{c \in C} \frac{w_c}{K + \text{rank}_c(d)}$$
 *
 * where:
 * - $C$ is the set of retrieval channels.
 * - $w_c$ is the normalized weight for channel $c$.
 * - $K$ is the smoothing constant (e.g. 60).
 * - $\text{rank}_c(d)$ is the 1-based rank position of item $d$ in channel $c$.
 */
object ReciprocalRankFusion {

    /**
     * Fuses multi-channel ranked candidate lists into a single ranked list of [HybridCandidate]s.
     *
     * @param channelResults Map of search channels to their respective ordered candidate lists.
     * @param config RRF parameters including smoothing constant $K$, channel weights, and topK limit.
     * @return Deterministically ordered list of fused candidates (sorted descending by RRF score, tie-break by mediaId ascending).
     */
    fun fuse(
        channelResults: Map<SearchChannel, List<RankedChannelItem>>,
        config: HybridSearchConfig = HybridSearchConfig()
    ): List<HybridCandidate> {
        if (channelResults.isEmpty()) {
            android.util.Log.i("RANKING", "FUSE_START: Empty channel results.")
            return emptyList()
        }

        android.util.Log.i("RANKING", "FUSE_START: channels=${channelResults.keys.joinToString { "${it.name}(${channelResults[it]?.size})" }}")

        val k = config.rrfConstantK
        val channelWeights = config.channelWeights

        // Accumulators keyed by mediaId
        class CandidateAccumulator(val mediaId: String) {
            var totalRrfScore: Double = 0.0
            val channelRanks = mutableMapOf<SearchChannel, Int>()
            val channelScores = mutableMapOf<SearchChannel, Float>()
            var isAuthoritativeLexical: Boolean = false
        }

        val accumulators = mutableMapOf<String, CandidateAccumulator>()

        for ((channel, items) in channelResults) {
            val weight = channelWeights[channel] ?: 0.0
            if (weight <= 0.0) continue // Skip unweighted or zero-weighted channels

            for (item in items) {
                val acc = accumulators.getOrPut(item.mediaId) { CandidateAccumulator(item.mediaId) }
                
                // RRF incremental component
                val contribution = weight / (k.toDouble() + item.rank.toDouble())
                acc.totalRrfScore += contribution
                acc.channelRanks[channel] = item.rank
                acc.channelScores[channel] = item.rawScore
                
                if (item.metadata["is_authoritative"] == "true") {
                    acc.isAuthoritativeLexical = true
                }
            }
        }

        if (accumulators.isEmpty()) return emptyList()

        // Deterministic sort: RRF score descending, then mediaId ascending (stable tie-breaking)
        val sortedCandidates = accumulators.values.sortedWith(
            compareByDescending<CandidateAccumulator> { it.totalRrfScore }
                .thenBy { it.mediaId }
        )

        // Trim to topK and build immutable HybridCandidate models
        val results = sortedCandidates
            .take(config.topK)
            .map { acc ->
                val explanation = buildExplanation(acc.channelRanks, acc.channelScores, acc.totalRrfScore)
                val reasons = buildStructuredReasons(acc.channelRanks, acc.channelScores, acc.isAuthoritativeLexical)
                
                HybridCandidate(
                    mediaId = acc.mediaId,
                    rrfScore = acc.totalRrfScore,
                    channelRanks = acc.channelRanks.toMap(),
                    channelScores = acc.channelScores.toMap(),
                    isAuthoritativeLexical = acc.isAuthoritativeLexical,
                    matchReasons = reasons,
                    matchExplanation = explanation
                )
            }
        android.util.Log.i("RANKING", "FUSE_COMPLETE: finalCandidates=${results.size} topId=${results.firstOrNull()?.mediaId}")
        return results
    }

    private fun buildStructuredReasons(
        ranks: Map<SearchChannel, Int>,
        scores: Map<SearchChannel, Float>,
        isAuthoritative: Boolean
    ): List<MatchReason> {
        val reasons = mutableListOf<MatchReason>()
        
        if (isAuthoritative) {
            reasons.add(MatchReason(MatchReasonType.EXACT_FILENAME, 1.0f, "Exact name match"))
        }

        ranks.forEach { (channel, rank) ->
            val score = scores[channel] ?: 0f
            when (channel) {
                SearchChannel.KEYWORD -> {
                    if (rank <= 3 && !isAuthoritative) {
                        reasons.add(MatchReason(MatchReasonType.STRONG_FILENAME_MATCH, 0.9f, "Strong name match"))
                    }
                }
                SearchChannel.SEMANTIC_CONTENT -> {
                    if (score > 0.7f) {
                        reasons.add(MatchReason(MatchReasonType.SEMANTIC_METADATA_MATCH, score, "Strong conceptual match"))
                    }
                }
                SearchChannel.SEMANTIC_VISUAL -> {
                    if (score > 0.6f) {
                        reasons.add(MatchReason(MatchReasonType.VISUAL_CONTENT_MATCH, score, "Visual similarity"))
                    }
                }
                SearchChannel.PERSONALIZED -> {
                    if (rank <= 5) {
                        reasons.add(MatchReason(MatchReasonType.PERSONALIZED_RELEVANCE, score, "Matches your preferences"))
                    }
                }
            }
        }

        return reasons
    }

    private fun buildExplanation(
        ranks: Map<SearchChannel, Int>,
        scores: Map<SearchChannel, Float>,
        rrfScore: Double
    ): String {
        val parts = ranks.entries.sortedBy { it.key.name }.map { (channel, rank) ->
            val score = scores[channel] ?: 0f
            "${channel.name}[Rank #$rank, Score ${"%.3f".format(score)}]"
        }
        return "RRF ${"%.5f".format(rrfScore)} via " + parts.joinToString(" + ")
    }
}
