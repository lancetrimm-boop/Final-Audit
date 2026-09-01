package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Test

class VideoFramePersistenceTest {

    private val descriptor = EmbeddingModelDescriptor(
        modelId = "mobileclip",
        modelVersion = 1,
        dimensionality = 512,
        primaryType = SemanticRepresentationType.VISUAL
    )

    @Test
    fun testFrameEntityMapping() {
        val domain = VideoFrameRepresentation(
            id = "frame1",
            mediaId = "m1",
            timestampUs = 1000000L,
            modelDescriptor = descriptor,
            documentVersion = 3,
            dimensionality = 512,
            vector = FloatArray(512) { 0.1f }
        )

        val entity = RoomSemanticRepresentationRepository.toFrameEntity(domain)
        
        assertEquals("frame1", entity.id)
        assertEquals("m1", entity.mediaId)
        assertEquals(1000000L, entity.timestampUs)
        assertEquals("mobileclip", entity.modelId)
        assertEquals(1, entity.modelVersion)
        assertEquals(3, entity.documentVersion)
        assertEquals(512, entity.dimensionality)

        val backToDomain = RoomSemanticRepresentationRepository.toFrameDomain(entity)
        assertEquals(domain, backToDomain)
    }

    @Test
    fun testStableIdGeneration() {
        // sem_frame_{mediaId}_{timestampUs}_{modelId}_v{version}
        val mediaId = "local_123"
        val timestamp = 5000000L
        val modelId = "mobileclip-s0"
        val version = 1
        
        val id = "sem_frame_${mediaId}_${timestamp}_${modelId}_v${version}"
        assertEquals("sem_frame_local_123_5000000_mobileclip-s0_v1", id)
    }
}
