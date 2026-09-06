package com.example.data

import android.util.Log

enum class AuraInteractionType {
    // LAYER 1: Discovery Feed
    OBSESSION_EXPOSURE,
    OBSESSION_OPENED,
    OBSESSION_ABANDONED,
    
    // LAYER 2: Obsession Detail
    MEDIA_EXPOSURE,
    MEDIA_ENGAGEMENT,      // Meaningful view (e.g., > 5s or play click)
    MEDIA_ABANDONED,       // Rapid skip
    MEDIA_REPLAY,
    MEDIA_COMPLETED,
    
    // LAYER 3: Expansion & Navigation
    BATCH_EXPANDED,
    TRY_SOMETHING_NEW,
    END_OF_BATCH_REACHED,
    NAVIGATED_BACK,

    // EXPLICIT SIGNALS
    FAVORITE,
    UNFAVORITE,
    RATING,
    PAIRWISE_WIN,
    PAIRWISE_LOSS,
    SAVE,
    
    // LAYER 4: Intelligence Context
    VISUAL_CONTEXT
}

/**
 * Authoritative service for Aura local behavioral interaction coordination.
 * Distinguishes between Exposure, Engagement, and Intent.
 * Centralizes mapping of UI events to Interaction Memory.
 */
object AuraInteractionService {

    private const val TAG = "AuraInteraction"

    /**
     * Logs an interaction event and persists it to local Interaction Memory.
     */
    fun logInteraction(
        mediaRepository: MediaRepository,
        interactionRepository: InteractionRepository,
        type: AuraInteractionType,
        mediaId: String? = null,
        value: Double = 1.0,
        metadata: Map<String, String> = emptyMap()
    ) {
        val message = "Interaction: $type | Media: ${mediaId ?: "N/A"} | Value: $value | Context: $metadata"
        Log.i(TAG, message)

        // 1. Persist to local Interaction Memory
        interactionRepository.recordSignal(
            mediaId = mediaId,
            type = type.name,
            value = value,
            context = metadata
        )

        // 2. Legacy/Immediate side-effects (Counters, etc.)
        when (type) {
            AuraInteractionType.MEDIA_EXPOSURE -> {
                mediaId?.let { mediaRepository.recordExposure(it) }
            }
            AuraInteractionType.MEDIA_ENGAGEMENT, 
            AuraInteractionType.MEDIA_REPLAY -> {
                mediaId?.let { mediaRepository.recordView(it) }
            }
            AuraInteractionType.MEDIA_COMPLETED -> {
                mediaId?.let { mediaRepository.recordMediaCompletion(it) }
            }
            else -> {
                // No immediate side effects required
            }
        }
    }
}
