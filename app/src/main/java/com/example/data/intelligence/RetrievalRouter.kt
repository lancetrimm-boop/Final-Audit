package com.example.data.intelligence

import com.example.data.semantic.RankedChannelItem
import com.example.data.semantic.SearchChannel
import com.example.data.semantic.LexicalCandidateRetriever
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Coordinates multi-channel candidate retrieval based on intelligence requests.
 * Decouples query modality from specific retrieval implementations.
 */
class RetrievalRouter(
    private val lexicalRetriever: LexicalCandidateRetriever,
    private val semanticProvider: SemanticRetrievalProvider?,
    private val visualProvider: VisualRetrievalProvider?
) {
    /**
     * Executes parallel retrieval across all available and appropriate channels.
     */
    suspend fun retrieve(request: IntelligenceRequest): Map<SearchChannel, List<RankedChannelItem>> = coroutineScope {
        val channelResults = mutableMapOf<SearchChannel, List<RankedChannelItem>>()
        
        // 1. Lexical Channel (Keyword/Metadata)
        val lexicalDeferred = async {
            val query = request.query ?: ""
            if (query.isNotBlank()) {
                try {
                    lexicalRetriever.retrieveKeywordCandidates(query, request.limit * 2)
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Lexical retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 2. Semantic Channel (MiniLM Content)
        val semanticDeferred = async {
            val query = request.query
            if (query != null && query.isNotBlank() && semanticProvider != null && semanticProvider.isReady()) {
                try {
                    semanticProvider.retrieveSemanticCandidates(query, request.limit * 2, 0.15f)
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Semantic retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 3. Visual Channel (CLIP Visual)
        val visualDeferred = async {
            if (visualProvider != null && visualProvider.isReady()) {
                try {
                    val vector = request.visualVector
                    val queryVectors = request.queryVectors
                    val query = request.query
                    when {
                        // Multi-Vector Union Strategy (A ∪ B ∪ C)
                        queryVectors != null && queryVectors.isNotEmpty() -> {
                            val allResults = queryVectors.flatMap { v ->
                                visualProvider.retrieveVisualCandidates(v, request.limit, 0.15f)
                            }
                            
                            // Deduplicate and re-rank
                            allResults.groupBy { it.mediaId }
                                .map { (id, items) ->
                                    val best = items.minBy { it.rank }
                                    RankedChannelItem(
                                        mediaId = id,
                                        rawScore = items.maxOf { it.rawScore },
                                        rank = best.rank,
                                        metadata = best.metadata
                                    )
                                }
                                .sortedWith(compareBy({ it.rank }, { -it.rawScore }))
                                .take(request.limit * 2)
                                .mapIndexed { index, item -> item.copy(rank = index + 1) }
                        }
                        vector != null -> visualProvider.retrieveVisualCandidates(vector, request.limit * 2, 0.15f)
                        query != null && query.isNotBlank() -> visualProvider.retrieveVisualCandidates(query, request.limit * 2, 0.15f)
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Visual retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // Collect results
        val lexical = lexicalDeferred.await()
        if (lexical.isNotEmpty()) channelResults[SearchChannel.KEYWORD] = lexical

        val semantic = semanticDeferred.await()
        if (semantic.isNotEmpty()) channelResults[SearchChannel.SEMANTIC_CONTENT] = semantic

        val visual = visualDeferred.await()
        if (visual.isNotEmpty()) channelResults[SearchChannel.SEMANTIC_VISUAL] = visual

        channelResults
    }
}
