package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Standardized behavioral signal for Aura's Preference Engine.
 * Represents a single user interaction that can be weighted and decayed.
 */
@Entity(
    tableName = "interaction_events",
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["type"]),
        Index(value = ["timestamp"])
    ]
)
data class InteractionEventEntity(
    @PrimaryKey val id: String,
    val mediaId: String?,
    val type: String, // e.g., FAVORITE, RATING, VIEW, SKIP
    val value: Double, // Magnitude of signal
    val timestamp: Long,
    val contextJson: String? // Serialized context (surface, query, etc.)
)
