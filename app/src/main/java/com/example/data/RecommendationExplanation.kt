package com.example.data

import com.example.data.intelligence.IntelligenceCandidate
import com.example.data.intelligence.EvidenceType

/**
 * Data backed explanation for a recommendation.
 */
data class RecommendationExplanation(
    val primaryReason: String,
    val detailPoints: List<String> = emptyList(),
    val confidenceLabel: String? = null,
    val isExploratory: Boolean = false
)

object RecommendationExplanationGenerator {

    /**
     * Generates an explanation for why a specific candidate was recommended.
     * Consumes evidence purely from the Intelligence Core.
     */
    fun generate(
        candidate: IntelligenceCandidate,
        tasteDNA: TasteDNA
    ): RecommendationExplanation? {
        val item = candidate.item
        val evidence = candidate.evidence
        
        val details = mutableListOf<String>()
        var primary = ""
        var isExploratory = false

        // 1. Explicit signals (Highest priority)
        if (item.isFavorite) {
            primary = "Similar to your favorites"
            details.add("You previously favorited this item")
        } else if (item.rating >= 4.0f) {
            primary = "Based on your high rating"
            details.add("You gave this item ${item.rating.toInt()} stars")
        }

        // 2. Pairwise Preference
        val pairwise = evidence.find { it.type == EvidenceType.PAIRWISE_PREFERENCE }
        if (primary.isEmpty() && pairwise != null && pairwise.score > 0.5f) {
            primary = "Matches your choices"
            details.add("You previously picked this in comparisons")
        }

        // 3. High Match logic (DNA alignment)
        val dnaAlignment = evidence.find { it.type == EvidenceType.TASTE_DNA_ALIGNMENT }
        if (primary.isEmpty() && dnaAlignment != null && dnaAlignment.score > 0.75f) {
            primary = "High predicted match"
            val highDNA = getHighPreferenceDimensions(item, tasteDNA)
            if (highDNA.isNotEmpty()) {
                details.add("Matches your preference for: ${highDNA.joinToString(", ")}")
            }
        }

        // 4. Novelty / Exploration
        val exploration = evidence.find { it.type == EvidenceType.EXPLORATION_VALUE }
        if (primary.isEmpty() && exploration != null && exploration.score > 0.7f) {
            primary = "Expand your taste"
            isExploratory = true
            details.add("A new discovery you haven't explored yet")
        }

        // 5. Relationship Evidence
        val relationship = evidence.find { it.type == EvidenceType.RELATIONSHIP_MATCH }
        if (relationship != null) {
            details.add("Contextual connection: ${relationship.provenance}")
        }

        // Fallback
        if (primary.isEmpty()) {
            if (candidate.rankScore > 0.5) {
                primary = "Aura matched this to your taste"
            } else {
                return null
            }
        }

        val matchPercent = (candidate.primaryRelevanceScore * 100).toInt().coerceIn(10, 99)

        return RecommendationExplanation(
            primaryReason = primary,
            detailPoints = details,
            confidenceLabel = "$matchPercent% Match",
            isExploratory = isExploratory
        )
    }

    private fun getHighPreferenceDimensions(item: MediaItem, tasteDNA: TasteDNA): List<String> {
        val traits = PersonalizationTraitMapper.getTraitAdjustments(item.moodTags)
        val matches = mutableListOf<String>()
        
        traits.forEach { (dim, presence) ->
            val userPref = when(dim) {
                "vibrancy" -> tasteDNA.effectiveVibrancy
                "contrast" -> tasteDNA.effectiveContrast
                "sharpness" -> tasteDNA.effectiveSharpness
                "symmetry" -> tasteDNA.effectiveSymmetry
                "complexity" -> tasteDNA.effectiveComplexity
                "naturalism" -> tasteDNA.effectiveNaturalism
                "novelty" -> tasteDNA.effectiveNovelty
                "lighting" -> tasteDNA.effectiveLighting
                "colorTemperature" -> tasteDNA.effectiveColorTemp
                "texture" -> tasteDNA.effectiveTexture
                "motion" -> tasteDNA.effectiveMotion
                "dynamicRange" -> tasteDNA.effectiveDynamicRange
                "framing" -> tasteDNA.effectiveFraming
                "depth" -> tasteDNA.effectiveDepth
                "warmth" -> tasteDNA.effectiveWarmth
                "saturation" -> tasteDNA.effectiveSaturation
                "elegance" -> tasteDNA.effectiveElegance
                "minimalism" -> tasteDNA.effectiveMinimalism
                "grain" -> tasteDNA.effectiveGrain
                "focus" -> tasteDNA.effectiveFocus
                "density" -> tasteDNA.effectiveDensity
                "rhythm" -> tasteDNA.effectiveRhythm
                "mood" -> tasteDNA.effectiveMood
                "harmony" -> tasteDNA.effectiveHarmony
                else -> 0.5
            }
            
            // If item has the trait and user likes the trait
            if (presence > 0.5 && userPref > 0.7) {
                matches.add(dim.replaceFirstChar { it.uppercase() })
            } else if (presence < -0.5 && userPref < 0.3) {
                matches.add("Muted/Subtle $dim".replaceFirstChar { it.uppercase() })
            }
        }
        return matches.take(3)
    }
}
