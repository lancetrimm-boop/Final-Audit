package com.example.data

import com.example.data.intelligence.IntelligenceCandidate
import org.junit.Assert.*
import org.junit.Test

class IntelligentSlideshowTest {

    @Test
    fun testSequencer_AvoidsDuplicates() {
        val item1 = MediaItem(id = "1", title = "A", mediaType = "PHOTO", dateAdded = 1000L)
        val item2 = MediaItem(id = "2", title = "A Copy", mediaType = "PHOTO", dateAdded = 2000L) 
        val item3 = MediaItem(id = "3", title = "B", mediaType = "PHOTO", dateAdded = 5000L)

        val cand1 = IntelligenceCandidate(item1, emptyList(), 10.0, 10f, 0f)
        val cand2 = IntelligenceCandidate(item2, emptyList(), 9.0, 9f, 0f)
        val cand3 = IntelligenceCandidate(item3, emptyList(), 8.0, 8f, 0f)

        val candidates = listOf(cand1, cand2, cand3)
        
        // Mock embeddings: 1 and 2 are very similar (0.99)
        val embeddings = mapOf(
            "1" to floatArrayOf(1f, 0f),
            "2" to floatArrayOf(0.995f, 0.005f),
            "3" to floatArrayOf(0f, 1f)
        )

        val result = SlideshowSequencer.sequence(candidates, embeddings)

        // Should skip "2" because it's a near-duplicate of "1"
        // Result size should be 2 because we only take 2 unique ones that pass the gate
        assertEquals(2, result.size)
        assertEquals("1", result[0].item.id)
        assertEquals("3", result[1].item.id)
    }

    @Test
    fun testSequencer_FavorsContinuity() {
        val item1 = MediaItem(id = "1", title = "A", mediaType = "PHOTO", dateAdded = 1000L)
        val item2 = MediaItem(id = "2", title = "C (Unrelated)", mediaType = "PHOTO", dateAdded = 2000L)
        val item3 = MediaItem(id = "3", title = "B (Related to A)", mediaType = "PHOTO", dateAdded = 3000L)

        val cand1 = IntelligenceCandidate(item1, emptyList(), 10.0, 10f, 0f)
        val cand2 = IntelligenceCandidate(item2, emptyList(), 9.5, 9.5f, 0f)
        val cand3 = IntelligenceCandidate(item3, emptyList(), 9.0, 9f, 0f)

        // Candidate 2 has higher relevance but 3 is more visually similar to 1
        val candidates = listOf(cand1, cand2, cand3)

        val embeddings = mapOf(
            "1" to floatArrayOf(1f, 0f),
            "2" to floatArrayOf(0f, 1f), // Dissimilar to 1
            "3" to floatArrayOf(0.85f, 0.15f) // Similar to 1 (in continuity zone)
        )

        val result = SlideshowSequencer.sequence(candidates, embeddings)

        // Should pick 3 after 1 despite 2 having higher Core relevance
        assertEquals("1", result[0].item.id)
        assertEquals("3", result[1].item.id)
        assertEquals("2", result[2].item.id)
    }

    @Test
    fun testSequencer_Deterministic() {
        val cand1 = IntelligenceCandidate(MediaItem(id = "1", title = "A", mediaType = "PHOTO"), emptyList(), 10.0, 10f, 0f)
        val cand2 = IntelligenceCandidate(MediaItem(id = "2", title = "B", mediaType = "PHOTO"), emptyList(), 9.0, 9f, 0f)
        val cand3 = IntelligenceCandidate(MediaItem(id = "3", title = "C", mediaType = "PHOTO"), emptyList(), 8.0, 8f, 0f)
        
        val candidates = listOf(cand1, cand2, cand3)
        val embeddings = emptyMap<String, FloatArray>()

        val result1 = SlideshowSequencer.sequence(candidates, embeddings)
        val result2 = SlideshowSequencer.sequence(candidates, embeddings)

        assertEquals(result1.map { it.item.id }, result2.map { it.item.id })
    }
}
