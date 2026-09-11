package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Test

class SoftIntersectionScorerTest {

    /**
     * Creates an L2-normalized vector with specific dot products against orthogonal bases.
     */
    private fun createCandidate(sims: List<Float>, dimensionality: Int = 512): FloatArray {
        val vector = FloatArray(dimensionality)
        var sumSquares = 0f
        sims.forEachIndexed { i, s -> 
            vector[i] = s
            sumSquares += s * s
        }
        
        // Fill residual to normalize if needed, but for logic testing we often don't care
        // if the candidate is slightly under-magnitude as long as dot products are correct.
        // However, production code expects normalized inputs.
        if (sumSquares < 1.0f) {
            vector[sims.size] = kotlin.math.sqrt(1.0f - sumSquares)
        }
        return vector
    }

    /**
     * Creates a set of orthogonal reference vectors.
     */
    private fun createReferences(count: Int, dimensionality: Int = 512): List<FloatArray> {
        return (0 until count).map { i ->
            val v = FloatArray(dimensionality)
            v[i] = 1.0f
            v
        }
    }

    @Test
    fun testAuthoritativeRankingRequirement() {
        // Spec: [0.70, 0.70] > [0.90, 0.30]
        val refs = createReferences(2)
        
        val candidateBalanced = createCandidate(listOf(0.70f, 0.70f))
        val candidateAsymmetric = createCandidate(listOf(0.90f, 0.30f))
        
        val resultBalanced = SoftIntersectionScorer.score(candidateBalanced, refs)
        val resultAsymmetric = SoftIntersectionScorer.score(candidateAsymmetric, refs)
        
        assertTrue("Balanced candidate ($resultBalanced) must outrank asymmetric candidate ($resultAsymmetric)",
            resultBalanced.score > resultAsymmetric.score)
            
        // Expected scores calculated in thought trace: ~0.7 vs ~0.457
        assertEquals(0.70f, resultBalanced.score, 0.001f)
        assertTrue(resultAsymmetric.score < 0.50f)
    }

    @Test
    fun testWeakestLinkRejection() {
        // Spec: recall floor = 0.25
        val refs = createReferences(2)
        
        // One reference is 0.20 (below 0.25 floor)
        val candidateWeak = createCandidate(listOf(0.80f, 0.20f))
        val result = SoftIntersectionScorer.score(candidateWeak, refs)
        
        assertFalse("Candidate with any similarity below floor must not survive", result.survives)
        assertEquals(0f, result.score, 0f)
    }

    @Test
    fun testRelevanceGate() {
        // Spec: relevance gate = 0.35
        val refs = createReferences(2)
        
        // [0.4, 0.4] -> GM=0.4, D=0 -> Score=0.4 (Above 0.35)
        val candidatePass = createCandidate(listOf(0.40f, 0.40f))
        // [0.3, 0.3] -> GM=0.3, D=0 -> Score=0.3 (Below 0.35)
        val candidateFail = createCandidate(listOf(0.30f, 0.30f))
        
        assertTrue("Score 0.4 should pass relevance gate 0.35", SoftIntersectionScorer.score(candidatePass, refs).survives)
        assertFalse("Score 0.3 should fail relevance gate 0.35", SoftIntersectionScorer.score(candidateFail, refs).survives)
    }

    @Test
    fun testIdenticalReferences() {
        // Multiple identical references should yield same score as single reference (effectively)
        val r1 = createReferences(1)[0]
        val refs = listOf(r1, r1)
        
        val candidate = createCandidate(listOf(0.80f)) 
        val result = SoftIntersectionScorer.score(candidate, refs)
        
        // GM = (0.8 * 0.8)^0.5 = 0.8
        // D = 0.8 - 0.8 = 0
        // Score = 0.8
        assertEquals(0.80f, result.score, 0.001f)
    }

    @Test
    fun testConflictingReferences() {
        val refs = createReferences(2) // Orthogonal bases (512 dimensions)
        
        // Candidate is [1, 0] (perfect match to R1, zero to R2)
        val candidate = createCandidate(listOf(1f, 0f))
        val result = SoftIntersectionScorer.score(candidate, refs)
        
        // R2 similarity is 0.0 < floor 0.25
        assertFalse("Opposing references should result in rejection", result.survives)
        assertEquals(0f, result.score, 0f)
    }

    @Test
    fun testThreePlusReferences() {
        val refs = createReferences(3)
        
        // [0.6, 0.6, 0.6]
        val candidate = createCandidate(listOf(0.6f, 0.6f, 0.6f))
        val result = SoftIntersectionScorer.score(candidate, refs)
        
        // GM = (0.6^3)^(1/3) = 0.6
        // D = 0
        // Score = 0.6
        assertEquals(0.60f, result.score, 0.001f)
        assertTrue(result.survives)
    }
}
