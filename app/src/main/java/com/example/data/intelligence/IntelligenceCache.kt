package com.example.data.intelligence

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import android.util.Log

/**
 * Bounded evidence cache for intelligence results.
 * Implements strict invalidation triggers to ensure fresh personalization.
 */
object IntelligenceCache {
    
    private val evidenceCache = ConcurrentHashMap<String, List<EvidenceItem>>()
    private val responseCache = ConcurrentHashMap<Int, IntelligenceResponse>()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var invalidationJob: Job? = null

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
     * Performance Fix: Implements debounced invalidation to prevent re-computation storms.
     */
    @Synchronized
    fun invalidateAll() {
        invalidationJob?.cancel()
        invalidationJob = scope.launch {
            delay(400) // Debounce burst events (taps, batch updates)
            evidenceCache.clear()
            responseCache.clear()
            Log.d("IntelligenceCache", "Cache fully invalidated (debounced)")
        }
    }

    /**
     * Invalidate specific item (e.g. on metadata update).
     */
    @Synchronized
    fun invalidateMedia(mediaId: String) {
        evidenceCache.remove(mediaId)
        // Responses might still contain this item, clear responses to ensure fresh ranking
        responseCache.clear() 
        Log.d("IntelligenceCache", "Invalidated media: $mediaId")
    }
}
