package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an encrypted, versioned on-device semantic vector representation.
 *
 * Designed to persist multiple representations per media item (e.g. TEXT, VISUAL, MOOD, AUDIO)
 * across multiple model architectures and versions without schema fragmentation or mixing.
 */
@Entity(
    tableName = "semantic_representations",
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["representationType"]),
        Index(value = ["modelId", "modelVersion"]),
        Index(value = ["representationType", "modelId", "modelVersion"]),
        Index(value = ["mediaId", "representationType", "modelId", "modelVersion"], unique = true)
    ]
)
data class SemanticRepresentationEntity(
    @PrimaryKey val id: String,
    val mediaId: String,
    val representationType: String,
    val modelId: String,
    val modelVersion: Int,
    val documentVersion: Int,
    val dimensionality: Int,
    val runtimeFormat: String = "ONNX",
    val quantization: String = "NONE_FP32",
    val artifactHash: String? = null,
    val vectorData: ByteArray,
    val isNormalized: Boolean,
    val sourceDataHash: String,
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SemanticRepresentationEntity

        if (id != other.id) return false
        if (mediaId != other.mediaId) return false
        if (representationType != other.representationType) return false
        if (modelId != other.modelId) return false
        if (modelVersion != other.modelVersion) return false
        if (dimensionality != other.dimensionality) return false
        if (!vectorData.contentEquals(other.vectorData)) return false
        if (isNormalized != other.isNormalized) return false
        if (sourceDataHash != other.sourceDataHash) return false
        if (confidence != other.confidence) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + representationType.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + dimensionality
        result = 31 * result + vectorData.contentHashCode()
        result = 31 * result + isNormalized.hashCode()
        result = 31 * result + sourceDataHash.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * Room entity representing an individual video frame embedding.
 */
@Entity(
    tableName = "video_frame_representations",
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["modelId", "modelVersion"]),
        Index(value = ["mediaId", "modelId", "modelVersion"])
    ]
)
data class VideoFrameRepresentationEntity(
    @PrimaryKey val id: String, // Stable ID: sem_frame_{mediaId}_{timestampUs}_{modelId}_v{version}
    val mediaId: String,
    val timestampUs: Long,
    val modelId: String,
    val modelVersion: Int,
    val documentVersion: Int,
    val dimensionality: Int,
    val runtimeFormat: String = "ONNX",
    val quantization: String = "NONE_FP32",
    val artifactHash: String? = null,
    val vectorData: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VideoFrameRepresentationEntity
        if (id != other.id) return false
        if (mediaId != other.mediaId) return false
        if (timestampUs != other.timestampUs) return false
        if (modelId != other.modelId) return false
        if (modelVersion != other.modelVersion) return false
        if (documentVersion != other.documentVersion) return false
        if (dimensionality != other.dimensionality) return false
        if (!vectorData.contentEquals(other.vectorData)) return false
        if (createdAt != other.createdAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + modelVersion
        result = 31 * result + documentVersion
        result = 31 * result + dimensionality
        result = 31 * result + vectorData.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
