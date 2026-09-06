package com.example.data.intelligence

import com.example.data.semantic.RankedChannelItem

/**
 * Capability for semantic natural-language retrieval.
 */
interface SemanticRetrievalProvider {
    fun isReady(): Boolean
    suspend fun retrieveSemanticCandidates(
        query: String, 
        topK: Int, 
        minSimilarity: Float
    ): List<RankedChannelItem>
}

/**
 * Capability for visual reference retrieval.
 */
interface VisualRetrievalProvider {
    fun isReady(): Boolean
    suspend fun retrieveVisualCandidates(
        query: String, 
        topK: Int, 
        minSimilarity: Float
    ): List<RankedChannelItem>

    suspend fun retrieveVisualCandidates(
        queryVector: FloatArray, 
        topK: Int, 
        minSimilarity: Float
    ): List<RankedChannelItem>
}

/**
 * Capability for scoring items based on user personalization.
 */
interface PersonalizationProvider {
    fun score(mediaId: String): Float
}
