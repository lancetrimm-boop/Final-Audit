package com.example.data.intelligence

import com.example.data.MediaItem

/**
 * A semantic anchor representing a specific aesthetic style.
 * Defined by a weighted vector of Taste DNA dimensions.
 */
data class StyleAnchor(
    val id: String,
    val displayName: String,
    val description: String,
    /**
     * Map of Taste DNA dimension keys to their target values (0.0 to 1.0).
     */
    val dimensionTargets: Map<String, Double>,
    /**
     * Relative importance of each dimension for this style (0.0 to 1.0).
     */
    val dimensionWeights: Map<String, Double>
)

/**
 * Represents a user's relationship with a specific style.
 */
data class SignatureStyle(
    val anchor: StyleAnchor,
    val affinityScore: Double, // 0.0 to 1.0
    val confidence: Double,    // 0.0 to 1.0
    val status: EvidenceStatus,
    val supportingMediaIds: List<String>,
    val representativeMedia: List<MediaItem> = emptyList()
)

/**
 * The rollup of all confirmed Signature Styles for the user.
 */
data class SignatureStyleProfile(
    val activeStyles: List<SignatureStyle>,
    val emergingStyles: List<SignatureStyle>,
    val lastUpdated: Long = System.currentTimeMillis()
)

object StyleAnchors {
    val CINEMATIC = StyleAnchor(
        id = "cinematic",
        displayName = "Cinematic",
        description = "Atmospheric depth, dramatic lighting, and rich tonal range.",
        dimensionTargets = mapOf(
            "depth" to 0.8,
            "lighting" to 0.7,
            "dynamicRange" to 0.7,
            "contrast" to 0.7,
            "focus" to 0.8
        ),
        dimensionWeights = mapOf(
            "depth" to 1.0,
            "lighting" to 0.8,
            "dynamicRange" to 0.6,
            "contrast" to 0.6,
            "focus" to 0.5
        )
    )

    val VIBRANT = StyleAnchor(
        id = "vibrant",
        displayName = "Vibrant & Energetic",
        description = "High saturation, vivid colors, and dynamic movement.",
        dimensionTargets = mapOf(
            "vibrancy" to 0.9,
            "saturation" to 0.8,
            "motion" to 0.7,
            "mood" to 0.8
        ),
        dimensionWeights = mapOf(
            "vibrancy" to 1.0,
            "saturation" to 0.9,
            "motion" to 0.7,
            "mood" to 0.5
        )
    )

    val MINIMAL = StyleAnchor(
        id = "minimal",
        displayName = "Minimal & Clean",
        description = "Uncluttered compositions, balanced structures, and focus on simplicity.",
        dimensionTargets = mapOf(
            "minimalism" to 0.9,
            "symmetry" to 0.7,
            "harmony" to 0.8,
            "density" to 0.2,
            "complexity" to 0.2
        ),
        dimensionWeights = mapOf(
            "minimalism" to 1.0,
            "symmetry" to 0.6,
            "harmony" to 0.7,
            "density" to 0.8,
            "complexity" to 0.8
        )
    )

    val NATURAL = StyleAnchor(
        id = "natural",
        displayName = "Natural & Organic",
        description = "Authentic textures, natural lighting, and unposed subjects.",
        dimensionTargets = mapOf(
            "naturalism" to 0.9,
            "texture" to 0.7,
            "warmth" to 0.6,
            "grain" to 0.5
        ),
        dimensionWeights = mapOf(
            "naturalism" to 1.0,
            "texture" to 0.6,
            "warmth" to 0.5,
            "grain" to 0.4
        )
    )

    val ALL_ANCHORS = listOf(CINEMATIC, VIBRANT, MINIMAL, NATURAL)
}
