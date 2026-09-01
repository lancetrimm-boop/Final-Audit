package com.example.data.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorMathTest {

    private val epsilon = 1e-5f

    @Test
    fun testDotProduct_Succeeds() {
        val u = floatArrayOf(1.0f, 2.0f, 3.0f)
        val v = floatArrayOf(4.0f, 5.0f, 6.0f)
        val result = VectorMath.dotProduct(u, v)
        // 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0f, result, epsilon)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDotProduct_DimensionMismatch_Throws() {
        VectorMath.dotProduct(floatArrayOf(1f), floatArrayOf(1f, 2f))
    }

    @Test
    fun testMagnitude_Succeeds() {
        val v = floatArrayOf(3.0f, 4.0f)
        val result = VectorMath.magnitude(v)
        // sqrt(3^2 + 4^2) = 5
        assertEquals(5.0f, result, epsilon)
    }

    @Test
    fun testL2Normalize_Succeeds() {
        val v = floatArrayOf(3.0f, 4.0f)
        val normalized = VectorMath.l2Normalize(v)
        assertEquals(0.6f, normalized[0], epsilon)
        assertEquals(0.8f, normalized[1], epsilon)
        assertEquals(1.0f, VectorMath.magnitude(normalized), epsilon)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testL2Normalize_ZeroVector_Throws() {
        VectorMath.l2Normalize(floatArrayOf(0f, 0f))
    }

    @Test
    fun testCosineSimilarity_Parallel_ReturnsOne() {
        val u = floatArrayOf(1.0f, 0.0f)
        val v = floatArrayOf(2.0f, 0.0f)
        val sim = VectorMath.cosineSimilarity(u, v)
        assertEquals(1.0f, sim, epsilon)
    }

    @Test
    fun testCosineSimilarity_Orthogonal_ReturnsZero() {
        val u = floatArrayOf(1.0f, 0.0f)
        val v = floatArrayOf(0.0f, 1.0f)
        val sim = VectorMath.cosineSimilarity(u, v)
        assertEquals(0.0f, sim, epsilon)
    }

    @Test
    fun testCosineSimilarity_Opposite_ReturnsMinusOne() {
        val u = floatArrayOf(1.0f, 0.0f)
        val v = floatArrayOf(-1.0f, 0.0f)
        val sim = VectorMath.cosineSimilarity(u, v)
        assertEquals(-1.0f, sim, epsilon)
    }

    @Test
    fun testSafeCosineSimilarity_ZeroVector_ReturnsNull() {
        val u = floatArrayOf(0.0f, 0.0f)
        val v = floatArrayOf(1.0f, 1.0f)
        val result = VectorMath.safeCosineSimilarity(u, v)
        assertNull(result)
    }

    @Test
    fun testMean_Succeeds() {
        val v1 = floatArrayOf(1.0f, 2.0f)
        val v2 = floatArrayOf(3.0f, 4.0f)
        val result = VectorMath.mean(listOf(v1, v2))
        assertEquals(2.0f, result[0], epsilon)
        assertEquals(3.0f, result[1], epsilon)
    }

    @Test
    fun testSerializationRoundTrip() {
        val v = floatArrayOf(1.23f, -4.56f, 0.001f)
        val bytes = VectorMath.serialize(v)
        val deserialized = VectorMath.deserialize(bytes, expectedDimension = 3)
        assertArrayEquals(v, deserialized)
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], 0.0f)
        }
    }
}
