package com.example.data.semantic

import com.example.data.intelligence.AuraIntelligenceCore
import com.example.data.intelligence.IntelligenceRequest
import com.example.data.intelligence.IntelligenceMode

/**
 * Adapter interface providing lexical / keyword search candidate items for hybrid fusion.
 */
interface LexicalCandidateRetriever {
    suspend fun retrieveKeywordCandidates(query: String, topK: Int = 50): List<RankedChannelItem>
}

/**
 * Interface for providing personalization scores for media items during hybrid search.
 */
interface PersonalizationScorer {
    fun score(mediaId: String): Float
}

/**
 * High-level search engine interface providing blended Hybrid Search.
 */
interface HybridSearchEngine {
    suspend fun search(
        request: SearchRequest,
        config: HybridSearchConfig = HybridSearchConfig()
    ): HybridSearchResult

    suspend fun search(
        query: String,
        config: HybridSearchConfig = HybridSearchConfig()
    ): HybridSearchResult = search(SearchRequest.Text(query), config)

    fun isSemanticReady(): Boolean
}

/**
 * Production implementation of [HybridSearchEngine].
 * Refactored as a thin compatibility adapter over [AuraIntelligenceCore].
 */
class DefaultHybridSearchEngine(
    private val intelligenceCore: AuraIntelligenceCore
) : HybridSearchEngine {

    override fun isSemanticReady(): Boolean = true

    override suspend fun search(
        request: SearchRequest,
        config: HybridSearchConfig
    ): HybridSearchResult {
        val coreRequest = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = request.query,
            visualVector = request.visualVector,
            queryVectors = if (request is SearchRequest.MultiVisual) request.visualVectors else null,
            limit = config.topK,
            useLegacyRanking = true, // Force legacy equivalence during initial migration
            requestId = request.requestId
        )
        
        val response = intelligenceCore.processRequest(coreRequest)
        
        return HybridSearchResult(
            query = request.query ?: "Visual Query",
            queryType = request.queryType,
            requestId = response.requestId,
            candidates = response.candidates.map { candidate ->
                HybridCandidate(
                    mediaId = candidate.item.id,
                    rrfScore = candidate.rankScore,
                    channelRanks = emptyMap(),
                    channelScores = emptyMap(),
                    matchExplanation = candidate.provenance
                )
            },
            latencyMs = response.latencyMs,
            totalCandidatesConsidered = response.candidates.size,
            channelCandidateCounts = emptyMap(),
            isSuccess = response.isSuccess,
            errorMessage = response.errorMessage
        )
    }
}
