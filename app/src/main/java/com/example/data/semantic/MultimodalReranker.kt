package com.example.data.semantic

/**
 * Interface for post-retrieval reranking of hybrid candidates.
 */
interface MultimodalReranker {
    /**
     * Reranks fused candidates to improve final order based on cross-channel evidence.
     * 
     * @param candidates Top-N fused candidates from RRF.
     * @param queryVector Optional query embedding (e.g. MobileCLIP text vector).
     * @param frameVectors Optional map of mediaId to individual frame embeddings.
     */
    fun rerank(
        candidates: List<HybridCandidate>,
        queryVector: FloatArray? = null,
        frameVectors: Map<String, List<VideoFrameRepresentation>> = emptyMap()
    ): List<HybridCandidate>
}

/**
 * Production reranker that prioritizes items with multi-channel alignment and 
 * performs deep frame-level max-similarity analysis for videos.
 */
class VideoIntelligenceReranker : MultimodalReranker {
    
    companion object {
        /**
         * Boost factor for items appearing in both text and visual semantic channels.
         */
        private const val ALIGNMENT_BOOST = 1.15 

        /**
         * Maximum boost factor for videos with a strongly matching individual frame.
         */
        private const val MAX_FRAME_SIMILARITY_BOOST = 1.25

        /**
         * Threshold for considering a frame similarity "stronger" than aggregate similarity.
         */
        private const val SIMILARITY_GAIN_THRESHOLD = 0.05f
    }

    override fun rerank(
        candidates: List<HybridCandidate>,
        queryVector: FloatArray?,
        frameVectors: Map<String, List<VideoFrameRepresentation>>
    ): List<HybridCandidate> {
        android.util.Log.i("RANKING", "RERANK_START: candidates=${candidates.size} hasQueryVector=${queryVector != null} frameBatchSize=${frameVectors.size}")
        if (candidates.isEmpty()) return emptyList()

        val results = candidates.map { candidate ->
            var boostedScore = candidate.rrfScore
            var explanation = candidate.matchExplanation

            // 1. Cross-Channel Alignment Boost (Stage 7 Logic preserved)
            val isAligned = candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_CONTENT) && 
                            candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_VISUAL)
            
            if (isAligned) {
                boostedScore *= ALIGNMENT_BOOST
                explanation += " [Aligned Multi-Modal Boost]"
            }

            // 2. Max Frame Similarity Reranking (Stage 8 Phase 8.4)
            if (queryVector != null && frameVectors.containsKey(candidate.mediaId)) {
                val frames = frameVectors[candidate.mediaId] ?: emptyList()
                if (frames.isNotEmpty()) {
                    val maxSim = calculateMaxSimilarity(queryVector, frames)
                    val aggregateSim = candidate.channelScores[SearchChannel.SEMANTIC_VISUAL] ?: 0.0f
                    
                    // AURA SEARCH DIAGNOSTICS: Expose indexed frame count and aggregate vs max similarity
                    explanation += " [Scene Frames: ${frames.size}]"
                    explanation += " [Aggregate Sim: ${"%.3f".format(aggregateSim)}]"

                    val gain = maxSim - aggregateSim
                    if (gain > SIMILARITY_GAIN_THRESHOLD) {
                        // Apply promotion boost based on visual evidence gain
                        val promotion = 1.0 + (gain * (MAX_FRAME_SIMILARITY_BOOST - 1.0) / 0.5)
                        boostedScore *= promotion.coerceAtMost(MAX_FRAME_SIMILARITY_BOOST.toDouble())
                        explanation += " [Max Frame Similarity: ${"%.3f".format(maxSim)}] [Max Frame Promotion]"
                    } else {
                        explanation += " [Max Frame Similarity: ${"%.3f".format(maxSim)}]"
                    }
                }
            }

            if (boostedScore != candidate.rrfScore) {
                candidate.copy(rrfScore = boostedScore, matchExplanation = explanation)
            } else {
                candidate
            }
        }.sortedByDescending { it.rrfScore }
        android.util.Log.i("RANKING", "RERANK_COMPLETE: topScore=${results.firstOrNull()?.rrfScore}")
        return results
    }

    private fun calculateMaxSimilarity(query: FloatArray, frames: List<VideoFrameRepresentation>): Float {
        var maxSim = -1.0f
        for (frame in frames) {
            try {
                // Frames and queries are expected to be L2-normalized
                val sim = VectorMath.dotProduct(query, frame.vector)
                if (sim > maxSim) maxSim = sim
            } catch (_: Exception) {}
        }
        return maxSim.coerceIn(-1.0f, 1.0f)
    }
}

/**
 * Legacy implementation preserved for alignment.
 */
object CrossChannelAlignmentReranker : MultimodalReranker {
    private const val ALIGNMENT_BOOST = 1.15 

    override fun rerank(
        candidates: List<HybridCandidate>,
        queryVector: FloatArray?,
        frameVectors: Map<String, List<VideoFrameRepresentation>>
    ): List<HybridCandidate> {
        return candidates.map { candidate ->
            val isAligned = candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_CONTENT) && 
                            candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_VISUAL)
            if (isAligned) {
                candidate.copy(
                    rrfScore = candidate.rrfScore * ALIGNMENT_BOOST,
                    matchExplanation = candidate.matchExplanation + " [Aligned Multi-Modal Boost]"
                )
            } else {
                candidate
            }
        }.sortedByDescending { it.rrfScore }
    }
}
