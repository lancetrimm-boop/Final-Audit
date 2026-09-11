package com.example.data.semantic

/**
 * Interface for post-retrieval reranking of hybrid candidates.
 */
interface MultimodalReranker {
    /**
     * Reranks fused candidates to improve final order based on cross-channel evidence.
     * 
     * @param candidates Top-N fused candidates from RRF.
     * @param queryVector Optional single query embedding (e.g. MobileCLIP text vector).
     * @param queryVectors Optional multiple query embeddings for intersection search.
     * @param frameVectors Optional map of mediaId to individual frame embeddings.
     */
    fun rerank(
        candidates: List<HybridCandidate>,
        queryVector: FloatArray? = null,
        queryVectors: List<FloatArray>? = null,
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
         * Boost factor to protect authoritative exact textual matches from being
         * displaced by merely aligned multimodal candidates (Step 1.2 Protection).
         * Increased in Plan 1 to ensure exact lexical matches win convincingly.
         */
        private const val LEXICAL_PROTECTION_BOOST = 5.0

        /**
         * Maximum boost factor for videos with a strongly matching individual frame.
         * Increased in Repair to allow strong deep matches to overtake aggregate matches.
         */
        private const val MAX_FRAME_SIMILARITY_BOOST = 3.0

        /**
         * Threshold for considering a frame similarity "stronger" than aggregate similarity.
         */
        private const val SIMILARITY_GAIN_THRESHOLD = 0.05f
    }

    override fun rerank(
        candidates: List<HybridCandidate>,
        queryVector: FloatArray?,
        queryVectors: List<FloatArray>?,
        frameVectors: Map<String, List<VideoFrameRepresentation>>
    ): List<HybridCandidate> {
        android.util.Log.i("RANKING", "RERANK_START: candidates=${candidates.size} hasQueryVector=${queryVector != null} hasMulti=${queryVectors?.size ?: 0} frameBatchSize=${frameVectors.size}")
        if (candidates.isEmpty()) return emptyList()

        val results = candidates.map { candidate ->
            var boostedScore = candidate.rrfScore
            var explanation = candidate.matchExplanation
            val reasons = candidate.matchReasons.toMutableList()

            // 1. Cross-Channel Alignment Boost (Single-reference focus)
            val isAligned = candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_CONTENT) && 
                            candidate.channelRanks.containsKey(SearchChannel.SEMANTIC_VISUAL)
            
            if (isAligned && queryVectors == null) { // Only apply to single-ref hybrid
                boostedScore *= ALIGNMENT_BOOST
                explanation += " [Aligned Multi-Modal Boost]"
                reasons.add(MatchReason(MatchReasonType.MULTI_CHANNEL_ALIGNMENT, 0.85f, "Matched by both text and visual appearance"))
            }

            // 1.1 Lexical Protection Boost (Plan 1 Step 1.2)
            if (candidate.isAuthoritativeLexical) {
                boostedScore *= LEXICAL_PROTECTION_BOOST
                explanation += " [Lexical Protection Boost]"
            }

            // 2. Multi-Reference Intersection Reranking
            if (frameVectors.containsKey(candidate.mediaId)) {
                val frames = frameVectors[candidate.mediaId] ?: emptyList()
                if (frames.isNotEmpty()) {
                    if (queryVectors != null && queryVectors.size >= 2) {
                        // PHASE 2: Multi-reference intersection scoring
                        // We score every visual evidence point (main vector and frames) against the references
                        val maxIntersectionResult = frames.map { frame ->
                            SoftIntersectionScorer.score(frame.vector, queryVectors)
                        }.maxByOrNull { it.score }

                        if (maxIntersectionResult != null && maxIntersectionResult.survives) {
                            val maxSim = maxIntersectionResult.score
                            val aggregateSim = candidate.channelScores[SearchChannel.SEMANTIC_VISUAL] ?: 0.0f
                            
                            explanation += " [Intersection Points: ${frames.size}]"
                            
                            val gain = maxSim - aggregateSim
                            if (gain > SIMILARITY_GAIN_THRESHOLD) {
                                val promotion = 1.0 + (gain * (MAX_FRAME_SIMILARITY_BOOST - 1.0) / 0.5)
                                boostedScore *= promotion.coerceAtMost(MAX_FRAME_SIMILARITY_BOOST.toDouble())
                                explanation += " [Max Intersection: ${"%.3f".format(maxSim)}] [Intersection Promotion]"
                                reasons.add(MatchReason(MatchReasonType.DEEP_SCENE_MATCH, maxSim, "Strong shared visual scene match"))
                            } else {
                                explanation += " [Max Intersection: ${"%.3f".format(maxSim)}]"
                            }
                        }
                    } else if (queryVector != null) {
                        // EXISTING: Single reference frame scoring
                        val maxSim = calculateMaxSimilarity(queryVector, frames)
                        val aggregateSim = candidate.channelScores[SearchChannel.SEMANTIC_VISUAL] ?: 0.0f
                        
                        explanation += " [Scene Points: ${frames.size}]"
                        explanation += " [Aggregate Sim: ${"%.3f".format(aggregateSim)}]"

                        val gain = maxSim - aggregateSim
                        if (gain > SIMILARITY_GAIN_THRESHOLD) {
                            val promotion = 1.0 + (gain * (MAX_FRAME_SIMILARITY_BOOST - 1.0) / 0.5)
                            boostedScore *= promotion.coerceAtMost(MAX_FRAME_SIMILARITY_BOOST.toDouble())
                            explanation += " [Max Frame Similarity: ${"%.3f".format(maxSim)}] [Max Frame Promotion]"
                            reasons.add(MatchReason(MatchReasonType.DEEP_SCENE_MATCH, maxSim, "Strong match to a specific scene in this video"))
                        } else {
                            explanation += " [Max Frame Similarity: ${"%.3f".format(maxSim)}]"
                        }
                    }
                }
            }

            if (boostedScore != candidate.rrfScore || reasons.size != candidate.matchReasons.size) {
                candidate.copy(rrfScore = boostedScore, matchExplanation = explanation, matchReasons = reasons)
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
        queryVectors: List<FloatArray>?,
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
