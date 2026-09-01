package com.example.data.semantic

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VectorIndexTest {

    private lateinit var descriptor: EmbeddingModelDescriptor
    private lateinit var index: InMemoryVectorIndex

    @Before
    fun setUp() {
        descriptor = EmbeddingModelDescriptor(
            modelId = "test-model",
            modelVersion = 1,
            dimensionality = 3,
            primaryType = SemanticRepresentationType.CONTENT
        )
        index = InMemoryVectorIndex(descriptor)
    }

    @Test
    fun testAddAndSearch_SimpleRanking() {
        // A = [1, 0, 0] -> perfect match for query [1, 0, 0]
        // B = [0.8, 0.6, 0] -> high similarity
        // C = [0, 0, 1] -> orthogonal
        
        val repA = createRep("m1", floatArrayOf(1.0f, 0.0f, 0.0f))
        val repB = createRep("m2", floatArrayOf(0.8f, 0.6f, 0.0f))
        val repC = createRep("m3", floatArrayOf(0.0f, 0.0f, 1.0f))

        index.addAll(listOf(repA, repB, repC))
        assertEquals(3, index.size)

        val query = floatArrayOf(1.0f, 0.0f, 0.0f)
        val results = index.query(query, topK = 3)

        assertEquals(3, results.size)
        assertEquals("m1", results[0].mediaId)
        assertEquals(1.0f, results[0].similarityScore, 1e-5f)
        
        assertEquals("m2", results[1].mediaId)
        assertEquals(0.8f, results[1].similarityScore, 1e-5f)
        
        assertEquals("m3", results[2].mediaId)
        assertEquals(0.0f, results[2].similarityScore, 1e-5f)
    }

    @Test
    fun testRemove_WorksCorrectly() {
        val rep = createRep("m1", floatArrayOf(1f, 0f, 0f))
        index.add(rep)
        assertEquals(1, index.size)
        
        index.remove(rep.id)
        assertEquals(0, index.size)
        assertTrue(index.query(floatArrayOf(1f, 0f, 0f)).isEmpty())
    }

    @Test
    fun testClear_WorksCorrectly() {
        index.add(createRep("m1", floatArrayOf(1f, 0f, 0f)))
        index.add(createRep("m2", floatArrayOf(0f, 1f, 0f)))
        assertEquals(2, index.size)
        
        index.clear()
        assertEquals(0, index.size)
    }

    @Test
    fun testTopK_IsRespected() {
        for (i in 1..10) {
            index.add(createRep("m$i", floatArrayOf(1f, 0f, 0f)))
        }
        
        val results = index.query(floatArrayOf(1f, 0f, 0f), topK = 5)
        assertEquals(5, results.size)
    }

    @Test
    fun testDuplicateMediaId_KeepsBestScore() {
        // Same mediaId, two different representations (e.g. different segments or metadata versions)
        // InMemoryVectorIndex uses bestByMedia map to deduplicate by mediaId
        val rep1 = createRep("m1", floatArrayOf(1f, 0f, 0f), id = "r1")
        val rep2 = createRep("m1", floatArrayOf(0.5f, 0.5f, 0f), id = "r2")
        
        index.add(rep1)
        index.add(rep2)
        
        // Query [1, 0, 0] should pick r1 because 1.0 > 0.5
        val results = index.query(floatArrayOf(1f, 0f, 0f))
        assertEquals(1, results.size)
        assertEquals("m1", results[0].mediaId)
        assertEquals("r1", results[0].representationId)
        assertEquals(1.0f, results[0].similarityScore, 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDimension_Rejected() {
        val badRep = createRep("m1", floatArrayOf(1f, 0f)) // Expected 3
        index.add(badRep)
    }

    @Test
    fun testConcurrentAccess_IsThreadSafe() {
        val executor = Executors.newFixedThreadPool(4)
        val iterations = 1000
        
        for (i in 1..iterations) {
            executor.submit {
                index.add(createRep("m$i", floatArrayOf(1f, 0f, 0f)))
            }
            executor.submit {
                index.query(floatArrayOf(1f, 0f, 0f), topK = 10)
            }
            if (i % 10 == 0) {
                executor.submit {
                    index.removeForMedia("m${i-5}")
                }
            }
        }
        
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        // No crash is the success condition here
    }

    private fun createRep(
        mediaId: String, 
        vector: FloatArray, 
        id: String = "rep_$mediaId"
    ): SemanticRepresentation {
        return SemanticRepresentation(
            id = id,
            mediaId = mediaId,
            type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor,
            dimensionality = descriptor.dimensionality,
            vector = vector,
            sourceDataHash = "hash"
        )
    }
}
