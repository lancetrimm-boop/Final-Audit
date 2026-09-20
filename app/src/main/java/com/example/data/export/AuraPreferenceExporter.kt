package com.example.data.export

import com.example.BuildConfig
import com.example.data.*
import com.example.data.db.SearchFeedbackEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exporter for the provider-agnostic Aura Preference Contract v1.
 * Ensures privacy by sanitizing all internal IDs, URIs, and embeddings.
 */
object AuraPreferenceExporter {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private const val CONFIDENCE_UNKNOWN_THRESHOLD = 0.1
    private const val CONFIDENCE_VERIFIED_THRESHOLD = 0.5

    fun export(
        tasteDNA: TasteDNA,
        stats: IntelligenceStats,
        favorites: List<MediaItem>,
        negatives: List<SearchFeedbackEntity>,
        allMedia: List<MediaItem> // Needed to resolve titles for search feedback
    ): AuraPreferenceContract {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date())

        return AuraPreferenceContract(
            meta = PreferenceMeta(
                aura_version = BuildConfig.VERSION_NAME,
                generated_at = now
            ),
            aesthetic_preferences = mapAesthetics(tasteDNA),
            discovery_behavior = mapBehavior(tasteDNA),
            interests = UserInterests(
                genres = stats.topGenres,
                moods = stats.favoriteMoods
            ),
            examples = PreferenceExamples(
                positive = favorites.map { it.title }.distinct().take(10),
                negative = mapNegatives(negatives, allMedia)
            )
        )
    }

    private fun mapAesthetics(dna: TasteDNA): AestheticPreferences {
        val visualDna = mutableMapOf<String, AestheticValue>()
        val styles = mutableListOf<String>()
        val narrativeParts = mutableListOf<String>()

        TasteDNA.AestheticDimension.entries.forEach { dim ->
            val value = getEffectiveValue(dna, dim.key)
            val conf = getConfidenceValue(dna, dim.key)
            val status = when {
                conf >= CONFIDENCE_VERIFIED_THRESHOLD -> "verified"
                conf >= CONFIDENCE_UNKNOWN_THRESHOLD -> "inferred"
                else -> "unknown"
            }

            visualDna[dim.key] = AestheticValue(value, conf, status)

            if (status != "unknown") {
                if (value > 0.7) {
                    styles.add(dim.key.capitalize())
                    narrativeParts.add("Strong preference for ${dim.key}.")
                } else if (value < 0.3) {
                    narrativeParts.add("Preference for low ${dim.key}.")
                }
            }
        }

        return AestheticPreferences(
            visual_dna = visualDna,
            styles = styles.take(5),
            narrative = narrativeParts.joinToString(" ")
        )
    }

    private fun mapBehavior(dna: TasteDNA): DiscoveryBehavior {
        return DiscoveryBehavior(
            novelty_seeking = BehaviorValue(
                level = getLevel(dna.effectiveExploration, dna.confExploration),
                confidence = dna.confExploration
            ),
            skip_sensitivity = BehaviorValue(
                level = getLevel(dna.effectiveSkipSensitivity, dna.confSkipSensitivity),
                confidence = dna.confSkipSensitivity
            )
        )
    }

    private fun getLevel(value: Double, conf: Double): String {
        if (conf < CONFIDENCE_UNKNOWN_THRESHOLD) return "unknown"
        return when {
            value > 0.7 -> "HIGH"
            value > 0.3 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun mapNegatives(negatives: List<SearchFeedbackEntity>, allMedia: List<MediaItem>): List<String> {
        val mediaMap = allMedia.associateBy { it.id }
        return negatives
            .filter { it.feedback == "BAD" }
            .mapNotNull { mediaMap[it.mediaId]?.title }
            .distinct()
            .take(10)
    }

    private fun getEffectiveValue(dna: TasteDNA, dim: String): Double {
        return when (dim) {
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

    private fun getConfidenceValue(dna: TasteDNA, dim: String): Double {
        return when (dim) {
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

    fun toJson(contract: AuraPreferenceContract): String {
        return moshi.adapter(AuraPreferenceContract::class.java).indent("  ").toJson(contract)
    }
}

private fun String.capitalize(): String = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
