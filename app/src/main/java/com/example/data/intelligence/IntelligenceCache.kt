package com.example.data.intelligence

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded evidence cache for intelligence results.
 * Implements strict invalidation triggers to ensure fresh personalization.
 */
object IntelligenceCache {
    
    private val evidenceCache = ConcurrentHashMap<String, List<EvidenceItem>>()
    private val responseCache = ConcurrentHashMap<Int, IntelligenceResponse>()
    
    /**
     * Cache results of a specific request.
     */
    fun putResponse(request: IntelligenceRequest, response: IntelligenceResponse) {
        val key = request.hashCode()
        responseCache[key] = response
    }
    
    fun getResponse(request: IntelligenceRequest): IntelligenceResponse? {
        return responseCache[request.hashCode()]
    }

    /**
     * Cache individual media evidence.
     */
    fun putEvidence(mediaId: String, evidence: List<EvidenceItem>) {
        evidenceCache[mediaId] = evidence
    }

    fun getEvidence(mediaId: String): List<EvidenceItem>? {
        return evidenceCache[mediaId]
    }

    /**
     * Invalidate entire cache (e.g. on Taste DNA update).
     */
    fun invalidateAll() {
        evidenceCache.clear()
        responseCache.clear()
    }

    /**
     * Invalidate specific item (e.g. on metadata update).
     */
    fun invalidateMedia(mediaId: String) {
        evidenceCache.remove(mediaId)
        // Responses might still contain this item, easier to clear responses
        responseCache.clear() 
    }
}
