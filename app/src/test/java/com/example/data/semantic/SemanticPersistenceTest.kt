package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Test

class SemanticPersistenceTest {

    @Test
    fun testVectorSerializationRoundTrip() {
        val original = FloatArray(384) { it.toFloat() * 0.1f }
        val serialized = VectorMath.serialize(original)
        
        // 384 * 4 bytes = 1536
        assertEquals(1536, serialized.size)
        
        val deserialized = VectorMath.deserialize(serialized, expectedDimension = 384)
        assertArrayEquals(original, deserialized)
    }

    @Test
    fun testEntityMapping() {
        val descriptor = EmbeddingModelDescriptor(
            modelId = "all-minilm-l6-v2",
            modelVersion = 2,
            dimensionality = 384,
            primaryType = SemanticRepresentationType.CONTENT
        )
        
        val domain = SemanticRepresentation(
            id = "r1",
            mediaId = "m1",
            type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor,
            documentVersion = 1,
            dimensionality = 384,
            vector = FloatArray(384) { 0.5f },
            sourceDataHash = "hash1",
            confidence = 0.9f
        )

        val entity = RoomSemanticRepresentationRepository.toEntity(domain)
        
        assertEquals("r1", entity.id)
        assertEquals("m1", entity.mediaId)
        assertEquals("CONTENT", entity.representationType)
        assertEquals("all-minilm-l6-v2", entity.modelId)
        assertEquals(2, entity.modelVersion)
        assertEquals(1, entity.documentVersion)
        assertEquals(384, entity.dimensionality)
        assertEquals("hash1", entity.sourceDataHash)
        assertEquals(0.9f, entity.confidence, 1e-6f)

        val backToDomain = RoomSemanticRepresentationRepository.toDomain(entity)
        assertEquals(domain, backToDomain)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDeserializationDimension() {
        val smallVector = FloatArray(10) { 0f }
        val bytes = VectorMath.serialize(smallVector)
        VectorMath.deserialize(bytes, expectedDimension = 384)
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 1e-6f)
        }
    }
}
