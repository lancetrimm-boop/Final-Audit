package com.example.data

import com.example.data.intelligence.IntelligenceCandidate
import com.example.data.semantic.VectorMath

/**
 * Intelligent pathfinding for media slideshows.
 * Optimizes for visual coherence and temporal continuity while preserving candidate relevance.
 */
object SlideshowSequencer {
    private const val INITIAL_DUPLICATE_THRESHOLD = 0.98
    private const val IDEAL_TRANSITION_MIN = 0.6
    private const val IDEAL_TRANSITION_MAX = 0.8

    data class SequencingConfig(
        val targetLength: Int = 20,
        val nearDuplicateThreshold: Double = INITIAL_DUPLICATE_THRESHOLD,
        val idealSimilarityMin: Double = IDEAL_TRANSITION_MIN,
        val idealSimilarityMax: Double = IDEAL_TRANSITION_MAX,
        val temporalWeight: Double = 0.3,
        val diversityWeight: Double = 0.4
    )

    /**
     * Constructs a deterministic intelligent sequence from a candidate pool.
     */
    fun sequence(
        candidates: List<IntelligenceCandidate>,
        embeddings: Map<String, FloatArray>,
        config: SequencingConfig = SequencingConfig()
    ): List<IntelligenceCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val result = mutableListOf<IntelligenceCandidate>()
        val remaining = candidates.toMutableList()

        // 1. Opening: Top candidate from Core (Hero)
        val opening = remaining.removeAt(0)
        result.add(opening)

        var current = opening
        val limit = config.targetLength.coerceAtMost(candidates.size)

        while (result.size < limit && remaining.isNotEmpty()) {
            val next = findNextBest(current, remaining, embeddings, config, result)
            if (next != null) {
                result.add(next)
                remaining.remove(next)
                current = next
            } else {
                // RESILIENT FALLBACK: If individual candidates lack data or pathfinding fails,
                // continue using the next available core candidate.
                val fallback = remaining.removeAt(0)
                result.add(fallback)
                current = fallback
            }
        }

        return result
    }

    private fun findNextBest(
        current: IntelligenceCandidate,
        pool: List<IntelligenceCandidate>,
        embeddings: Map<String, FloatArray>,
        config: SequencingConfig,
        history: List<IntelligenceCandidate>
    ): IntelligenceCandidate? {
        var bestCandidate: IntelligenceCandidate? = null
        var maxScore = -Double.MAX_VALUE

        val currentVector = embeddings[current.item.id]

        pool.forEach { candidate ->
            val candidateVector = embeddings[candidate.item.id]
            
            // 1. Transition Similarity (Transition Coherence)
            val similarity = if (currentVector != null && candidateVector != null) {
                try {
                    VectorMath.cosineSimilarity(currentVector, candidateVector).toDouble()
                } catch (e: Exception) {
                    0.5 // Neutral if math fails
                }
            } else 0.5 // Default neutral if missing

            // DUPLICATE GUARD: Hard penalty for high similarity (Empirical Tuning Target)
            if (similarity > config.nearDuplicateThreshold) {
                return@forEach 
            }

            // Transition Quality: Favor "Continuity Zone" for smooth visual flow
            val transitionQuality = when {
                similarity >= config.idealSimilarityMin && similarity <= config.idealSimilarityMax -> 1.0
                similarity > config.idealSimilarityMax -> 0.5 // Too similar (risks feeling repetitive)
                else -> similarity // Scale with similarity for lower values
            }

            // 2. Temporal Continuity
            val timeDiffHours = Math.abs(candidate.item.dateAdded - current.item.dateAdded) / (1000.0 * 60 * 60)
            // Favor items close in time (same day) but with soft decay
            val temporalContinuity = if (timeDiffHours < 24) 1.0 else (24.0 / timeDiffHours).coerceIn(0.0, 1.0)

            // 3. Diversity Penalty (Soft diminishing returns)
            val diversityPenalty = calculateDiversityPenalty(candidate, history)

            // 4. Bounded Relevance Tie-breaker (Does not override Core authority)
            val relevanceSignal = (candidate.rankScore / 20.0).coerceIn(0.0, 1.0) * 0.1

            // Composite Transition Score
            val totalScore = (transitionQuality * 0.6) + 
                             (temporalContinuity * config.temporalWeight) + 
                             relevanceSignal - 
                             (diversityPenalty * config.diversityWeight)

            if (totalScore > maxScore) {
                maxScore = totalScore
                bestCandidate = candidate
            }
        }

        return bestCandidate
    }

    private fun calculateDiversityPenalty(candidate: IntelligenceCandidate, history: List<IntelligenceCandidate>): Double {
        val recent = history.takeLast(5)
        var penalty = 0.0
        recent.forEach { prev ->
            // Soft penalty for same exact minute (likely same burst/sequence)
            if (Math.abs(candidate.item.dateAdded - prev.item.dateAdded) < 60000) {
                penalty += 0.4
            }
            // Same genre/category
            if (candidate.item.genre == prev.item.genre && candidate.item.genre.isNotBlank() && candidate.item.genre != "Media") {
                penalty += 0.2
            }
        }
        return penalty.coerceIn(0.0, 1.0)
    }
}
