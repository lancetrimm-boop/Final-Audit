package com.example.util

import android.util.Log
import com.example.data.semantic.HybridSearchResult
import com.example.data.semantic.SearchChannel

/**
 * Diagnostic utility for Aura Semantic Search.
 * 
 * Provides detailed tracing of search queries across lexical and semantic channels.
 * Intended for development and performance tuning.
 */
object AuraSearchDiagnostics {
    private const val TAG = "AuraSearchDebug"

    /**
     * Logs the detailed breakdown of a hybrid search result.
     */
    fun logSearchResult(result: HybridSearchResult) {
        val candidates = result.candidates
        val counts = result.channelCandidateCounts

        Log.d(TAG, "==========================================================")
        Log.d(TAG, "SEARCH QUERY: \"${result.query}\" [${result.queryType}]")
        Log.d(TAG, "Request ID: ${result.requestId}")
        Log.d(TAG, "Latency: ${result.latencyMs}ms | Total Candidates Evaluated: ${result.totalCandidatesConsidered}")
        Log.d(TAG, "Channel Counts: $counts")
        
        // Compound Metadata (Stage 11.6)
        if (result.queryType == com.example.data.semantic.SearchQueryType.COMPOUND) {
            Log.d(TAG, "Mode: CROSS-MODAL [Visual Anchor + Text Constraint]")
        }

        // Deep Reranking Stats
        if (result.deepRerankCount > 0) {
            Log.d(TAG, "Deep Reranking: ${result.deepRerankCount} candidates | Frame Vectors Loaded: ${result.frameVectorsLoaded}")
        }
        
        if (candidates.isEmpty()) {
            Log.d(TAG, "RESULT: NO MATCHES FOUND")
        } else {
            Log.d(TAG, "TOP FUSED RESULTS (Top ${candidates.size.coerceAtMost(5)}):")
            candidates.take(5).forEachIndexed { index, candidate ->
                Log.d(TAG, "  #${index + 1} ID: ${candidate.mediaId} | RRF Score: ${"%.5f".format(candidate.rrfScore)}")
                Log.d(TAG, "      Explanation: ${candidate.matchExplanation}")
            }
        }
        Log.d(TAG, "==========================================================")
    }

    /**
     * Logs information about where a candidate might have been lost in the pipeline.
     */
    fun logFilterCheck(query: String, mediaId: String, title: String, isSemantic: Boolean, survives: Boolean, reason: String) {
        if (!survives) {
            Log.v(TAG, "FILTER_REJECTION: Query=\"$query\" | Media=$mediaId (\"$title\") | Semantic=$isSemantic | Reason=$reason")
        }
    }
}
