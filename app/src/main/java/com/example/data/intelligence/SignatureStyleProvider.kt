package com.example.data.intelligence

import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.TasteDNA
import com.example.data.PersonalizationTraitMapper
import kotlin.math.abs

/**
 * Derives human-readable Signature Styles from Taste DNA and Interaction Memory.
 */
object SignatureStyleProvider {

    private const val STYLE_CONFIRMED_THRESHOLD = 0.65
    private const val STYLE_EMERGING_THRESHOLD = 0.50

    /**
     * Computes the complete Signature Style profile for the current user.
     */
    fun calculateStyleProfile(
        tasteDNA: TasteDNA,
        repository: MediaRepository
    ): SignatureStyleProfile {
        val allStyles = StyleAnchors.ALL_ANCHORS.map { anchor ->
            deriveStyle(anchor, tasteDNA, repository)
        }

        val active = allStyles.filter { it.affinityScore >= STYLE_CONFIRMED_THRESHOLD && it.confidence > 0.4 }
            .sortedByDescending { it.affinityScore }
        
        val emerging = allStyles.filter { 
            (it.affinityScore in STYLE_EMERGING_THRESHOLD..STYLE_CONFIRMED_THRESHOLD) || 
            (it.affinityScore >= STYLE_CONFIRMED_THRESHOLD && it.confidence <= 0.4)
        }.sortedByDescending { it.affinityScore }

        return SignatureStyleProfile(
            activeStyles = active,
            emergingStyles = emerging
        )
    }

    /**
     * Scores a single media item's affinity with a specific style anchor.
     */
    fun calculateMediaAffinity(item: MediaItem, anchor: StyleAnchor): Double {
        val traits = PersonalizationTraitMapper.getEffectiveTraitAdjustments(item)
        if (traits.isEmpty()) return 0.5

        var weightedSum = 0.0
        var totalWeight = 0.0

        anchor.dimensionWeights.forEach { (dim, weight) ->
            val target = anchor.dimensionTargets[dim] ?: 0.5
            val presence = traits[dim] ?: 0.0
            val traitValue = (presence + 1.0) / 2.0 // Map -1..1 to 0..1
            
            val alignment = 1.0 - abs(target - traitValue)
            weightedSum += alignment * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedSum / totalWeight else 0.5
    }

    private fun deriveStyle(
        anchor: StyleAnchor,
        tasteDNA: TasteDNA,
        repository: MediaRepository
    ): SignatureStyle {
        // 1. Calculate Aesthetic Affinity from DNA
        var weightedSum = 0.0
        var totalWeight = 0.0
        var totalConfidence = 0.0

        anchor.dimensionWeights.forEach { (dim, weight) ->
            val target = anchor.dimensionTargets[dim] ?: 0.5
            val current = getEffectiveDimensionValue(tasteDNA, dim)
            val conf = getDimensionConfidence(tasteDNA, dim)
            
            val alignment = 1.0 - abs(target - current)
            weightedSum += alignment * weight
            totalWeight += weight
            totalConfidence += conf * weight
        }

        val dnaAffinity = if (totalWeight > 0) weightedSum / totalWeight else 0.5
        val dnaConfidence = if (totalWeight > 0) totalConfidence / totalWeight else 0.0

        // 2. Incorporate Behavioral Evidence (Wins/Losses)
        // This is a placeholder for more complex behavioral aggregation
        // We look for media that matches this anchor and see user performance
        val behavioralBoost = 0.0 // To be implemented with Interaction Memory analysis

        val finalAffinity = (dnaAffinity + behavioralBoost).coerceIn(0.0, 1.0)
        
        // 3. Find Supporting Media
        val items = repository.mediaItems.value
        val scoredItems = items.map { it to calculateMediaAffinity(it, anchor) }
            .filter { it.second > 0.7 }
            .sortedByDescending { it.second }
        
        val supportingIds = scoredItems.map { it.first.id }
        val representative = scoredItems.take(3).map { it.first }

        val status = when {
            dnaConfidence > 0.7 && finalAffinity > STYLE_CONFIRMED_THRESHOLD -> EvidenceStatus.KNOWN
            dnaConfidence > 0.3 -> EvidenceStatus.INFERRED
            else -> EvidenceStatus.UNCERTAIN
        }

        return SignatureStyle(
            anchor = anchor,
            affinityScore = finalAffinity,
            confidence = dnaConfidence,
            status = status,
            supportingMediaIds = supportingIds,
            representativeMedia = representative
        )
    }

    private fun getEffectiveDimensionValue(dna: TasteDNA, dim: String): Double {
        return when(dim) {
            "vibrancy" -> dna.effectiveVibrancy
            "contrast" -> dna.effectiveContrast
            "sharpness" -> dna.effectiveSharpness
            "symmetry" -> dna.effectiveSymmetry
            "complexity" -> dna.effectiveComplexity
            "naturalism" -> dna.effectiveNaturalism
            "novelty" -> dna.effectiveNovelty
            "lighting" -> dna.effectiveLighting
            "colorTemperature" -> dna.effectiveColorTemp
            "texture" -> dna.effectiveTexture
            "motion" -> dna.effectiveMotion
            "dynamicRange" -> dna.effectiveDynamicRange
            "framing" -> dna.effectiveFraming
            "depth" -> dna.effectiveDepth
            "warmth" -> dna.effectiveWarmth
            "saturation" -> dna.effectiveSaturation
            "elegance" -> dna.effectiveElegance
            "minimalism" -> dna.effectiveMinimalism
            "grain" -> dna.effectiveGrain
            "focus" -> dna.effectiveFocus
            "density" -> dna.effectiveDensity
            "rhythm" -> dna.effectiveRhythm
            "mood" -> dna.effectiveMood
            "harmony" -> dna.effectiveHarmony
            else -> 0.5
        }
    }

    private fun getDimensionConfidence(dna: TasteDNA, dim: String): Double {
        return when(dim) {
            "vibrancy" -> dna.confVibrancy
            "contrast" -> dna.confContrast
            "sharpness" -> dna.confSharpness
            "symmetry" -> dna.confSymmetry
            "complexity" -> dna.confComplexity
            "naturalism" -> dna.confNaturalism
            "novelty" -> dna.confNovelty
            "lighting" -> dna.confLighting
            "colorTemperature" -> dna.confColorTemp
            "texture" -> dna.confTexture
            "motion" -> dna.confMotion
            "dynamicRange" -> dna.confDynamicRange
            "framing" -> dna.confFraming
            "depth" -> dna.confDepth
            "warmth" -> dna.confWarmth
            "saturation" -> dna.confSaturation
            "elegance" -> dna.confElegance
            "minimalism" -> dna.confMinimalism
            "grain" -> dna.confGrain
            "focus" -> dna.confFocus
            "density" -> dna.confDensity
            "rhythm" -> dna.confRhythm
            "mood" -> dna.confMood
            "harmony" -> dna.confHarmony
            else -> 0.0
        }
    }
}
