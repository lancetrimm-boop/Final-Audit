package com.example.data

import android.util.Log
import com.example.data.db.ClipInteractionEntity

object ClipTelemetryService {

    private const val TAG = "ClipTelemetryService"

    enum class InteractionType {
        PREVIEW,
        SELECT,
        EXPORT
    }

    fun logInteraction(
        repository: MediaRepository,
        mediaId: String,
        clip: ClipCandidate,
        type: InteractionType
    ) {
        Log.d(TAG, "Logging clip interaction [$type] for clip '${clip.title}' (MediaID: $mediaId, Range: ${clip.startTimeMs}ms - ${clip.endTimeMs}ms)")
        
        val entity = ClipInteractionEntity(
            mediaId = mediaId,
            clipTitle = clip.title,
            startTimeMs = clip.startTimeMs,
            endTimeMs = clip.endTimeMs,
            previewCount = if (type == InteractionType.PREVIEW) 1 else 0,
            selectCount = if (type == InteractionType.SELECT) 1 else 0,
            exportCount = if (type == InteractionType.EXPORT) 1 else 0
        )
        
        repository.logClipInteraction(entity)
    }
}
