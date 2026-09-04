package com.example.data.semantic

import kotlin.math.pow

/**
 * Result of a soft visual intersection scoring operation.
 */
data class SoftIntersectionResult(
    val score: Float,
    val survives: Boolean,
    val individualSimilarities: List<Float>
)

/**
 * Mathematical engine for computing the "Soft Intersection" of multiple visual references.
 * 
 * Implements a geometric-mean based similarity aggregation with a dispersion penalty
 * to prefer balanced candidates over those that only strongly match a subset of references.
 */
object SoftIntersectionScorer {

    /**
     * Minimum similarity to any single reference for a candidate to be considered a participant.
     * Provisional: 0.25 recall floor.
     */
    const val RECALL_FLOOR = 0.25f

    /**
     * Minimum aggregate intersection score for a candidate to be considered relevant.
     * Provisional: 0.35 relevance gate.
     */
    const val RELEVANCE_GATE = 0.35f

    /**
     * Smoothing constant for geometric mean calculation to avoid zero-product extinction.
     */
    private const val EPSILON = 0.01f

    /**
     * Weight for the dispersion penalty (max similarity - min similarity).
     */
    private const val DISPERSION_PENALTY_FACTOR = 0.20f

    /**
     * Computes the soft intersection score for a candidate vector against multiple references.
     * 
     * @param candidate L2-normalized candidate vector.
     * @param references List of L2-normalized reference vectors.
     * @return [SoftIntersectionResult] containing the final score and survival status.
     */
    fun score(candidate: FloatArray, references: List<FloatArray>): SoftIntersectionResult {
        if (references.isEmpty()) {
            return SoftIntersectionResult(0f, false, emptyList())
        }

        // 1. Calculate individual cosine similarities (dot product for L2-normalized vectors)
        val similarities = references.map { ref ->
            VectorMath.dotProduct(candidate, ref).coerceAtLeast(0f)
        }

        // 2. Weakest-link rejection: Every similarity must meet the recall floor
        val anyBelowFloor = similarities.any { it < RECALL_FLOOR }
        if (anyBelowFloor) {
            return SoftIntersectionResult(0f, false, similarities)
        }

        // 3. Geometric Mean calculation: (Π max(ε, s_i))^(1/k)
        var product = 1.0
        for (s in similarities) {
            product *= s.coerceAtLeast(EPSILON).toDouble()
        }
        val gm = product.pow(1.0 / similarities.size).toFloat()

        // 4. Dispersion Penalty: D = max(s) - min(s)
        val maxSim = similarities.maxOrNull() ?: 0f
        val minSim = similarities.minOrNull() ?: 0f
        val dispersion = maxSim - minSim
        val penalty = dispersion * DISPERSION_PENALTY_FACTOR

        // 5. Final Score: GM * (1.0 - Penalty)
        val finalScore = (gm * (1.0f - penalty)).coerceAtLeast(0f)
        
        // 6. Final relevance gate
        val survives = finalScore >= RELEVANCE_GATE

        return SoftIntersectionResult(finalScore, survives, similarities)
    }
}
