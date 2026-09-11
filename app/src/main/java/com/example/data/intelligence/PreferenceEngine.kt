package com.example.data.intelligence

import android.util.Log
import com.example.data.AuraInteractionType
import com.example.data.MediaRepository
import com.example.data.PersonalizationTraitMapper
import com.example.data.TasteDNA
import com.example.data.db.InteractionEventEntity
import kotlin.math.exp
import kotlin.math.ln

/**
 * Central mechanism for converting Interaction Memory into learned preferences.
 * Implements weighted, time-decayed aggregation with damping.
 */
object PreferenceEngine {

    private const val TAG = "PreferenceEngine"
    private const val LEARNING_RATE = 0.1 // Damping to prevent overfitting
    private const val DECAY_LAMBDA = 0.00000001 // Slow decay over days (approx 10% per day)
    
    // Confidence thresholds
    private const val CONFIDENCE_SATURATION_COUNT = 20.0 // Events needed for 1.0 confidence

    /**
     * Aggregates a batch of interaction events into an updated TasteDNA.
     */
    fun calculateUpdatedDNA(
        currentDNA: TasteDNA,
        events: List<InteractionEventEntity>,
        repository: MediaRepository
    ): TasteDNA {
        if (events.isEmpty()) return currentDNA

        val now = System.currentTimeMillis()
        
        // 1. Group events by dimension
        // We iterate through events, get traits of media, and apply weights.
        val dimensionSignals = mutableMapOf<String, MutableList<WeightedSignal>>()

        events.forEach { event ->
            val weight = getBaseWeight(event.type)
            val decay = exp(-DECAY_LAMBDA * (now - event.timestamp))
            val effectiveWeight = weight * decay

            // For media-linked events, use traits
            event.mediaId?.let { mediaId ->
                repository.getMediaItemById(mediaId)?.let { item ->
                    val adjustments = PersonalizationTraitMapper.getEffectiveTraitAdjustments(item)
                    adjustments.forEach { (dim, multiplier) ->
                        // Signal value is centered around 0.5. 
                        // If multiplier is high (1.0), it pulls toward 1.0 (if weight > 0)
                        // Signal = 0.5 + (0.5 * multiplier * direction)
                        val signalValue = 0.5 + (0.5 * multiplier * (if (weight >= 0) 1.0 else -1.0))
                        
                        dimensionSignals.getOrPut(dim) { mutableListOf() }
                            .add(WeightedSignal(signalValue, kotlin.math.abs(effectiveWeight)))
                    }
                }
            }
            
            // For behavioral-only events (like Skip Sensitivity)
            if (event.type == AuraInteractionType.MEDIA_ABANDONED.name && event.mediaId == null) {
                 dimensionSignals.getOrPut("skipSensitivity") { mutableListOf() }
                     .add(WeightedSignal(1.0, kotlin.math.abs(effectiveWeight)))
            }

            // Update 9.1: Visual Context Signals
            if (event.type == AuraInteractionType.VISUAL_CONTEXT.name) {
                // Parse metrics from contextJson if available
                event.contextJson?.let { json ->
                    try {
                        val adapter = repository.getMoshi().adapter<Map<String, String>>(
                            com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                        )
                        val metrics = adapter.fromJson(json)

                        metrics?.forEach { (key: String, valueStr: String) ->
                            val value = valueStr.toDoubleOrNull() ?: 0.5
                            val dim = when(key) {
                                "brightness" -> "lighting"
                                "contrast" -> "contrast"
                                "warmth" -> "warmth"
                                "saturation" -> "saturation"
                                else -> null
                            }
                            if (dim != null) {
                                dimensionSignals.getOrPut(dim) { mutableListOf() }
                                    .add(WeightedSignal(value, effectiveWeight))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse visual context metrics", e)
                    }
                }
            }
        }

        // 2. Aggregate signals into Target Learned State and Confidence
        var updatedDNA = currentDNA
        
        dimensionSignals.forEach { (dim, signals) ->
            val totalWeight = signals.sumOf { it.weight }
            if (totalWeight <= 0) return@forEach

            val targetValue = signals.sumOf { it.value * it.weight } / totalWeight
            val confidence = (totalWeight / CONFIDENCE_SATURATION_COUNT).coerceIn(0.0, 1.0)
            
            updatedDNA = applyLearnedUpdate(updatedDNA, dim, targetValue, confidence)
        }

        return updatedDNA
    }

    private fun getBaseWeight(type: String): Double {
        return when (type) {
            AuraInteractionType.FAVORITE.name -> 1.0
            AuraInteractionType.SAVE.name -> 1.0
            AuraInteractionType.PAIRWISE_WIN.name -> 1.0
            AuraInteractionType.RATING.name -> 0.75
            AuraInteractionType.MEDIA_COMPLETED.name -> 0.3
            AuraInteractionType.MEDIA_ENGAGEMENT.name -> 0.1
            AuraInteractionType.MEDIA_EXPOSURE.name -> 0.05
            AuraInteractionType.MEDIA_ABANDONED.name -> -0.5
            AuraInteractionType.PAIRWISE_LOSS.name -> -0.5
            AuraInteractionType.UNFAVORITE.name -> -1.0
            else -> 0.0
        }
    }

    private fun applyLearnedUpdate(dna: TasteDNA, dimension: String, target: Double, confidence: Double): TasteDNA {
        // Damping: newLearned = oldLearned + learningRate * (target - oldLearned)
        fun damped(old: Double) = old + LEARNING_RATE * (target - old)

        return when (dimension) {
            "vibrancy" -> dna.copy(learnedVibrancy = damped(dna.learnedVibrancy), confVibrancy = confidence)
            "contrast" -> dna.copy(learnedContrast = damped(dna.learnedContrast), confContrast = confidence)
            "sharpness" -> dna.copy(learnedSharpness = damped(dna.learnedSharpness), confSharpness = confidence)
            "symmetry" -> dna.copy(learnedSymmetry = damped(dna.learnedSymmetry), confSymmetry = confidence)
            "complexity" -> dna.copy(learnedComplexity = damped(dna.learnedComplexity), confComplexity = confidence)
            "naturalism" -> dna.copy(learnedNaturalism = damped(dna.learnedNaturalism), confNaturalism = confidence)
            "novelty" -> dna.copy(learnedNovelty = damped(dna.learnedNovelty), confNovelty = confidence)
            "lighting" -> dna.copy(learnedLighting = damped(dna.learnedLighting), confLighting = confidence)
            "colorTemperature" -> dna.copy(learnedColorTemp = damped(dna.learnedColorTemp), confColorTemp = confidence)
            "texture" -> dna.copy(learnedTexture = damped(dna.learnedTexture), confTexture = confidence)
            "motion" -> dna.copy(learnedMotion = damped(dna.learnedMotion), confMotion = confidence)
            "dynamicRange" -> dna.copy(learnedDynamicRange = damped(dna.learnedDynamicRange), confDynamicRange = confidence)
            "framing" -> dna.copy(learnedFraming = damped(dna.learnedFraming), confFraming = confidence)
            "depth" -> dna.copy(learnedDepth = damped(dna.learnedDepth), confDepth = confidence)
            "warmth" -> dna.copy(learnedWarmth = damped(dna.learnedWarmth), confWarmth = confidence)
            "saturation" -> dna.copy(learnedSaturation = damped(dna.learnedSaturation), confSaturation = confidence)
            "elegance" -> dna.copy(learnedElegance = damped(dna.learnedElegance), confElegance = confidence)
            "minimalism" -> dna.copy(learnedMinimalism = damped(dna.learnedMinimalism), confMinimalism = confidence)
            "grain" -> dna.copy(learnedGrain = damped(dna.learnedGrain), confGrain = confidence)
            "focus" -> dna.copy(learnedFocus = damped(dna.learnedFocus), confFocus = confidence)
            "density" -> dna.copy(learnedDensity = damped(dna.learnedDensity), confDensity = confidence)
            "rhythm" -> dna.copy(learnedRhythm = damped(dna.learnedRhythm), confRhythm = confidence)
            "mood" -> dna.copy(learnedMood = damped(dna.learnedMood), confMood = confidence)
            "harmony" -> dna.copy(learnedHarmony = damped(dna.learnedHarmony), confHarmony = confidence)
            "skipSensitivity" -> dna.copy(learnedSkipSensitivity = damped(dna.learnedSkipSensitivity), confSkipSensitivity = confidence)
            "explorationPropensity" -> dna.copy(learnedExploration = damped(dna.learnedExploration), confExploration = confidence)
            "retentionFocus" -> dna.copy(learnedRetention = damped(dna.learnedRetention), confRetention = confidence)
            "favoriteSignificance" -> dna.copy(learnedFavSignificance = damped(dna.learnedFavSignificance), confFavSignificance = confidence)
            else -> dna
        }
    }

    private data class WeightedSignal(val value: Double, val weight: Double)
}
