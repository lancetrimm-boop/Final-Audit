package com.example.data.intelligence

import com.example.data.semantic.RankedChannelItem
import com.example.data.semantic.SearchChannel
import com.example.data.semantic.HybridSearchConfig

/**
 * Standalone implementation of Reciprocal Rank Fusion (RRF).
 * Merges heterogeneous candidate sets into a unified pool.
 */
object RetrievalFusion {

    private const val RRF_K = 60

    /**
     * Fuses multi-channel ranked candidate lists into a single ranked list of media IDs with fused scores.
     */
    fun fuse(
        channelResults: Map<SearchChannel, List<RankedChannelItem>>,
        config: HybridSearchConfig = HybridSearchConfig()
    ): List<FusedCandidate> {
        if (channelResults.isEmpty()) return emptyList()

        val k = config.rrfConstantK.toDouble()
        val weights = config.channelWeights

        val accumulators = mutableMapOf<String, FusedCandidateAccumulator>()

        for ((channel, items) in channelResults) {
            val weight = weights[channel] ?: 0.0
            if (weight <= 0.0) continue

            for (item in items) {
                val acc = accumulators.getOrPut(item.mediaId) { FusedCandidateAccumulator(item.mediaId) }
                
                val contribution = weight / (k + item.rank.toDouble())
                acc.totalRrfScore += contribution
                acc.channelRanks[channel] = item.rank
                acc.channelScores[channel] = item.rawScore
                
                if (item.metadata["is_authoritative"] == "true") {
                    acc.isAuthoritative = true
                }
            }
        }

        return accumulators.values.sortedWith(
            compareByDescending<FusedCandidateAccumulator> { it.totalRrfScore }
                .thenBy { it.mediaId }
        ).map { acc ->
            FusedCandidate(
                mediaId = acc.mediaId,
                rrfScore = acc.totalRrfScore,
                channelRanks = acc.channelRanks.toMap(),
                channelScores = acc.channelScores.toMap(),
                isAuthoritative = acc.isAuthoritative
            )
        }
    }

    private class FusedCandidateAccumulator(val mediaId: String) {
        var totalRrfScore: Double = 0.0
        val channelRanks = mutableMapOf<SearchChannel, Int>()
        val channelScores = mutableMapOf<SearchChannel, Float>()
        var isAuthoritative: Boolean = false
    }

    data class FusedCandidate(
        val mediaId: String,
        val rrfScore: Double,
        val channelRanks: Map<SearchChannel, Int>,
        val channelScores: Map<SearchChannel, Float>,
        val isAuthoritative: Boolean
    )
}
