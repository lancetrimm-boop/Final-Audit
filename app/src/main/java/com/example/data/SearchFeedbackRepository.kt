package com.example.data

import com.example.data.db.SearchFeedbackDao
import com.example.data.db.SearchFeedbackEntity
import com.example.data.semantic.HybridCandidate
import com.example.data.semantic.SearchRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Manages user feedback for search results to improve future relevance.
 */
class SearchFeedbackRepository(
    private val dao: SearchFeedbackDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    /**
     * Records a "Good" or "Bad" feedback for a specific search result.
     */
    fun recordFeedback(
        request: SearchRequest,
        candidate: HybridCandidate,
        position: Int,
        isGood: Boolean
    ) {
        scope.launch {
            val entity = SearchFeedbackEntity(
                mediaId = candidate.mediaId,
                query = request.query ?: "Visual Query",
                queryType = request.queryType.name,
                rankingPosition = position,
                rrfScore = candidate.rrfScore,
                feedback = if (isGood) "GOOD" else "BAD",
                matchExplanation = candidate.matchExplanation
            )
            dao.insert(entity)
        }
    }

    /**
     * Observes all recorded search feedback.
     */
    fun observeAllFeedback(): Flow<List<SearchFeedbackEntity>> = dao.getAllFeedback()

    /**
     * Clears all feedback history.
     */
    fun clearHistory() {
        scope.launch {
            dao.clearAll()
        }
    }
}
