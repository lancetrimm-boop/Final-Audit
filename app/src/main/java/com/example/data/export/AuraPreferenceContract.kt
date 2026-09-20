package com.example.data.export

import com.squareup.moshi.JsonClass

/**
 * Portable Aura Preference Contract v1.
 * Standardized, provider-agnostic representation of user taste.
 */
@JsonClass(generateAdapter = true)
data class AuraPreferenceContract(
    val schema: String = "aura-preferences-v1",
    val meta: PreferenceMeta,
    val aesthetic_preferences: AestheticPreferences,
    val discovery_behavior: DiscoveryBehavior,
    val interests: UserInterests,
    val examples: PreferenceExamples
)

@JsonClass(generateAdapter = true)
data class PreferenceMeta(
    val aura_version: String,
    val profile_version: Int = 1,
    val source: String = "Aura",
    val generated_at: String
)

@JsonClass(generateAdapter = true)
data class AestheticPreferences(
    val visual_dna: Map<String, AestheticValue>,
    val styles: List<String>,
    val narrative: String
)

@JsonClass(generateAdapter = true)
data class AestheticValue(
    val value: Double,
    val confidence: Double,
    val status: String // "verified", "inferred", "unknown"
)

@JsonClass(generateAdapter = true)
data class DiscoveryBehavior(
    val novelty_seeking: BehaviorValue,
    val skip_sensitivity: BehaviorValue
)

@JsonClass(generateAdapter = true)
data class BehaviorValue(
    val level: String, // "LOW", "MEDIUM", "HIGH", "unknown"
    val confidence: Double
)

@JsonClass(generateAdapter = true)
data class UserInterests(
    val genres: List<String>,
    val moods: List<String>
)

@JsonClass(generateAdapter = true)
data class PreferenceExamples(
    val positive: List<String>, // Media titles
    val negative: List<String>  // Media titles
)
