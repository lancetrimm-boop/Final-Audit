package com.example.data.semantic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Adapter interface providing lexical / keyword search candidate items for hybrid fusion.
 */
interface LexicalCandidateRetriever {
    /**
     * Retrieves ranked keyword candidates for the given search query.
     *
     * @param query Search query string.
     * @param topK Maximum number of keyword candidates to retrieve.
     * @return List of [RankedChannelItem]s with 1-based ranks.
     */
    suspend fun retrieveKeywordCandidates(query: String, topK: Int = 50): List<RankedChannelItem>
}

/**
 * Interface for providing personalization scores for media items during hybrid search.
 */
interface PersonalizationScorer {
    /**
     * Scores a media item based on user preferences.
     * @return A personalization score (higher is better).
     */
    fun score(mediaId: String): Float
}

/**
 * High-level search engine interface providing blended Hybrid Search via Reciprocal Rank Fusion.
 */
interface HybridSearchEngine {
    /**
     * Executes a hybrid search combining keyword and semantic retrieval channels.
     *
     * @param request The generalized search request (Text or Visual).
     * @param config Hybrid configuration (RRF constant K, channel weights, topK, semantic threshold).
     * @return [HybridSearchResult] containing fused candidates and channel metadata.
     */
    suspend fun search(
        request: SearchRequest,
        config: HybridSearchConfig = HybridSearchConfig()
    ): HybridSearchResult

    /**
     * Compatibility overload for legacy text queries.
     */
    suspend fun search(
        query: String,
        config: HybridSearchConfig = HybridSearchConfig()
    ): HybridSearchResult = search(SearchRequest.Text(query), config)

    /**
     * Checks if the semantic retrieval subsystem is initialized and ready for inference.
     */
    fun isSemanticReady(): Boolean
}

/**
 * Production implementation of [HybridSearchEngine].
 *
 * Coordinates concurrent candidate retrieval from the [LexicalCandidateRetriever] and [SemanticSearchService],
 * transforms channel results into ranked items, and executes deterministic Reciprocal Rank Fusion.
 *
 * Features:
 * - Robust fallback: If semantic retrieval is unready or fails, smoothly returns keyword ranking without failing the query.
 * - Concurrency: Fetches lexical and semantic candidates in parallel using structured coroutines.
 * - Modality & Descriptor Safety: Passes through typed constraints to the semantic service.
 * - Personalization: Integrates user preferences as a separate ranking channel in the fusion process.
 * - Deterministic Sorting: Primary sort by RRF score descending, tie-break by mediaId ascending.
 */
class DefaultHybridSearchEngine(
    private val semanticService: SemanticSearchService,
    private val lexicalRetriever: LexicalCandidateRetriever,
    private val repository: SemanticRepresentationRepository? = null,
    private val visualTextProvider: EmbeddingProvider? = null,
    @Suppress("UNUSED_PARAMETER") private val visualImageProvider: EmbeddingProvider? = null,
    private val visualRetriever: MobileCLIPVisualRetriever? = null,
    private val personalizationScorer: PersonalizationScorer? = null,
    private val rrf: ReciprocalRankFusion = ReciprocalRankFusion,
    private val reranker: MultimodalReranker = VideoIntelligenceReranker()
) : HybridSearchEngine {

    companion object {
        /**
         * Number of top candidates to consider for deep frame-level reranking.
         */
        private const val RERANK_TOP_N = 50

        /**
         * Weights for vector composition in compound search.
         * Default: equal contribution from visual and textual modalities.
         */
        private const val COMPOUND_VISUAL_WEIGHT = 1.0f
        private const val COMPOUND_TEXT_WEIGHT = 1.0f
    }

    override fun isSemanticReady(): Boolean = semanticService.isReady() || visualRetriever?.isReady() == true

    override suspend fun search(
        request: SearchRequest,
        config: HybridSearchConfig
    ): HybridSearchResult {
        android.util.Log.i("SEARCH_REQUEST", "START_SEARCH: type=${request.javaClass.simpleName} query=\"${request.query}\" requestId=${request.requestId}")
        val result = when (request) {
            is SearchRequest.Text -> searchText(request, config)
            is SearchRequest.Visual -> searchVisual(request, config)
            is SearchRequest.Compound -> searchCompound(request, config)
        }
        android.util.Log.i("SEARCH_REQUEST", "FINISH_SEARCH: success=${result.isSuccess} candidates=${result.candidates.size} latency=${result.latencyMs}ms")
        return result
    }

    private suspend fun searchText(
        request: SearchRequest.Text,
        config: HybridSearchConfig
    ): HybridSearchResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val query = request.query
        val trimmedQuery = query.trim()

        if (trimmedQuery.isBlank()) {
            return@withContext HybridSearchResult(
                query = query,
                queryType = SearchQueryType.TEXT,
                requestId = request.requestId,
                candidates = emptyList(),
                latencyMs = 0L,
                totalCandidatesConsidered = 0,
                channelCandidateCounts = emptyMap(),
                isSuccess = false,
                errorMessage = "Search query cannot be blank."
            )
        }

        val channelResults = mutableMapOf<SearchChannel, List<RankedChannelItem>>()
        val channelCounts = mutableMapOf<SearchChannel, Int>()
        var semanticError: String? = null
        var queryVector: FloatArray? = null

        coroutineScope {
            // 1. Generate query embedding for visual/cross-modal search (Stage 8 Phase 8.4)
            val queryEmbeddingDeferred = async {
                if (visualTextProvider != null && visualTextProvider.isReady()) {
                    val result = visualTextProvider.generateEmbedding(
                        mediaId = "query_${System.currentTimeMillis()}",
                        input = SemanticInput.Text(trimmedQuery),
                        sourceDataHash = "query_hash"
                    )
                    (result as? EmbeddingResult.Success)?.representation?.vector
                } else null
            }
            // ... (rest of search text logic)

            val lexicalDeferred = async {
                try {
                    lexicalRetriever.retrieveKeywordCandidates(trimmedQuery, topK = config.topK * 2)
                } catch (e: Exception) {
                    android.util.Log.e("AuraSemanticTrace", "Lexical search failed for query \"$trimmedQuery\"", e)
                    emptyList()
                }
            }

            val semanticDeferred = async {
                try {
                    semanticService.search(
                        query = trimmedQuery,
                        topK = config.topK * 2,
                        minSimilarity = config.minSemanticSimilarity,
                        targetType = SemanticRepresentationType.CONTENT
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AuraSemanticTrace", "Semantic search failed for query \"$trimmedQuery\"", e)
                    null
                }
            }

            val visualDeferred = async {
                if (visualRetriever != null && visualRetriever.isReady()) {
                    try {
                        visualRetriever.retrieveVisualCandidates(
                            query = trimmedQuery,
                            topK = config.topK * 2,
                            minSimilarity = config.minSemanticSimilarity
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("AuraSemanticTrace", "Visual search failed for query \"$trimmedQuery\"", e)
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            // Await lexical candidates
            val keywordCandidates = lexicalDeferred.await()
            if (keywordCandidates.isNotEmpty()) {
                channelResults[SearchChannel.KEYWORD] = keywordCandidates
                channelCounts[SearchChannel.KEYWORD] = keywordCandidates.size
            }

            // Await semantic candidates
            val semanticResult = semanticDeferred.await()
            if (semanticResult != null && semanticResult.isSuccess) {
                val semanticRankedItems = semanticResult.candidates.mapIndexed { index, candidate ->
                    RankedChannelItem(
                        mediaId = candidate.mediaId,
                        rawScore = candidate.similarityScore,
                        rank = index + 1,
                        metadata = mapOf(
                            "representationType" to candidate.type.name,
                            "modelId" to candidate.modelDescriptor.modelId
                        )
                    )
                }
                if (semanticRankedItems.isNotEmpty()) {
                    channelResults[SearchChannel.SEMANTIC_CONTENT] = semanticRankedItems
                    channelCounts[SearchChannel.SEMANTIC_CONTENT] = semanticRankedItems.size
                }
            } else if (semanticResult != null && !semanticResult.isSuccess) {
                semanticError = semanticResult.errorMessage
            }

            // Await visual candidates
            val visualCandidates = visualDeferred.await()
            if (visualCandidates.isNotEmpty()) {
                channelResults[SearchChannel.SEMANTIC_VISUAL] = visualCandidates
                channelCounts[SearchChannel.SEMANTIC_VISUAL] = visualCandidates.size
            }

            queryVector = queryEmbeddingDeferred.await()
        }

        // --- INTEGRATE PERSONALIZATION (Phase 7 Step 10) ---
        // If a personalization scorer is available and the channel has weight, 
        // rank the unique candidates by user preference.
        if (personalizationScorer != null && config.channelWeights.getOrDefault(SearchChannel.PERSONALIZED, 0.0) > 0.0) {
            val candidateIds = channelResults.values.flatten().map { it.mediaId }.distinct()
            if (candidateIds.isNotEmpty()) {
                val personalizedItems = candidateIds.map { mediaId ->
                    mediaId to personalizationScorer.score(mediaId)
                }.sortedByDescending { it.second }
                 .mapIndexed { index, (mediaId, score) -> 
                    RankedChannelItem(
                        mediaId = mediaId,
                        rawScore = score,
                        rank = index + 1
                    )
                }

                channelResults[SearchChannel.PERSONALIZED] = personalizedItems
                channelCounts[SearchChannel.PERSONALIZED] = personalizedItems.size
            }
        }

        // Total unique candidates evaluated
        val totalUniqueCandidates = channelResults.values.flatten().map { it.mediaId }.distinct().size

        // Execute Reciprocal Rank Fusion
        val fusedCandidates = rrf.fuse(channelResults, config)

        // --- STAGE 8 Phase 8.4: Deep Frame-Level Reranking ---
        val topForRerank = fusedCandidates.take(RERANK_TOP_N)
        var framesLoadedCount = 0
        val frameVectors = if (repository != null && queryVector != null && topForRerank.isNotEmpty()) {
            val mediaIds = topForRerank.map { it.mediaId }
            val frames = repository.getFramesForBatch(mediaIds)
            framesLoadedCount = frames.size
            frames.groupBy { it.mediaId }
        } else {
            emptyMap()
        }

        // STAGE 7/8: Multimodal & Video Intelligence Reranking
        val rerankedCandidates = reranker.rerank(
            candidates = fusedCandidates,
            queryVector = queryVector,
            frameVectors = frameVectors
        )

        val elapsed = System.currentTimeMillis() - startTime

        val finalResult = HybridSearchResult(
            query = query,
            queryType = SearchQueryType.TEXT,
            requestId = request.requestId,
            candidates = rerankedCandidates,
            latencyMs = elapsed,
            totalCandidatesConsidered = totalUniqueCandidates,
            channelCandidateCounts = channelCounts.toMap(),
            deepRerankCount = topForRerank.size,
            frameVectorsLoaded = framesLoadedCount,
            isSuccess = true,
            errorMessage = if (channelResults.isEmpty() && semanticError != null) semanticError else null
        )

        // AURA SEARCH DIAGNOSTICS: Detailed trace for behavioral validation
        com.example.util.AuraSearchDiagnostics.logSearchResult(finalResult)

        finalResult
    }

    /**
     * Implementation for Visual-to-Visual search (Stage 10.3 / evolved in Stage 11.2).
     */
    private suspend fun searchVisual(
        request: SearchRequest,
        config: HybridSearchConfig
    ): HybridSearchResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val queryVector = request.visualVector

        if (queryVector == null) {
            return@withContext HybridSearchResult(
                query = "Visual Query",
                queryType = SearchQueryType.VISUAL,
                requestId = request.requestId,
                candidates = emptyList(),
                latencyMs = System.currentTimeMillis() - startTime,
                totalCandidatesConsidered = 0,
                channelCandidateCounts = emptyMap(),
                isSuccess = false,
                errorMessage = "Visual query vector missing."
            )
        }
        
        if (visualRetriever == null || !visualRetriever.isReady()) {
            return@withContext HybridSearchResult(
                query = "Visual Query",
                queryType = SearchQueryType.VISUAL,
                requestId = request.requestId,
                candidates = emptyList(),
                latencyMs = System.currentTimeMillis() - startTime,
                totalCandidatesConsidered = 0,
                channelCandidateCounts = emptyMap(),
                isSuccess = false,
                errorMessage = "Visual search engine not ready."
            )
        }

        val channelResults = mutableMapOf<SearchChannel, List<RankedChannelItem>>()
        val channelCounts = mutableMapOf<SearchChannel, Int>()

        coroutineScope {
            // 1. Retrieve visual candidates using the pre-encoded vector
            val visualCandidates = try {
                visualRetriever.retrieveVisualCandidates(
                    queryVector = queryVector,
                    topK = config.topK * 2,
                    minSimilarity = config.minSemanticSimilarity
                )
            } catch (e: Exception) {
                android.util.Log.e("AuraSemanticTrace", "Visual retrieval failed", e)
                emptyList()
            }

            if (visualCandidates.isNotEmpty()) {
                channelResults[SearchChannel.SEMANTIC_VISUAL] = visualCandidates
                channelCounts[SearchChannel.SEMANTIC_VISUAL] = visualCandidates.size
            }
        }

        // --- INTEGRATE PERSONALIZATION ---
        if (personalizationScorer != null && config.channelWeights.getOrDefault(SearchChannel.PERSONALIZED, 0.0) > 0.0) {
            val candidateIds = channelResults.values.flatten().map { it.mediaId }.distinct()
            if (candidateIds.isNotEmpty()) {
                val personalizedItems = candidateIds.map { mediaId ->
                    mediaId to personalizationScorer.score(mediaId)
                }.sortedByDescending { it.second }
                 .mapIndexed { index, (mediaId, score) -> 
                    RankedChannelItem(
                        mediaId = mediaId,
                        rawScore = score,
                        rank = index + 1
                    )
                }

                channelResults[SearchChannel.PERSONALIZED] = personalizedItems
                channelCounts[SearchChannel.PERSONALIZED] = personalizedItems.size
            }
        }

        val totalUniqueCandidates = channelResults.values.flatten().map { it.mediaId }.distinct().size

        // Execute Reciprocal Rank Fusion
        val fusedCandidates = rrf.fuse(channelResults, config)

        // --- DEEP VIDEO RERANKING ---
        val topForRerank = fusedCandidates.take(RERANK_TOP_N)
        var framesLoadedCount = 0
        val frameVectors = if (repository != null && topForRerank.isNotEmpty()) {
            val mediaIds = topForRerank.map { it.mediaId }
            val frames = repository.getFramesForBatch(mediaIds)
            framesLoadedCount = frames.size
            frames.groupBy { it.mediaId }
        } else {
            emptyMap()
        }

        val rerankedCandidates = reranker.rerank(
            candidates = fusedCandidates,
            queryVector = queryVector,
            frameVectors = frameVectors
        )

        val finalResult = HybridSearchResult(
            query = "Visual Reference [${request.requestId}]",
            queryType = SearchQueryType.VISUAL,
            requestId = request.requestId,
            candidates = rerankedCandidates,
            latencyMs = System.currentTimeMillis() - startTime,
            totalCandidatesConsidered = totalUniqueCandidates,
            channelCandidateCounts = channelCounts.toMap(),
            deepRerankCount = topForRerank.size,
            frameVectorsLoaded = framesLoadedCount,
            isSuccess = true
        )

        com.example.util.AuraSearchDiagnostics.logSearchResult(finalResult)
        finalResult
    }

    /**
     * Implementation for Compound Visual + Text search (Stage 11.3).
     * 
     * Uses vector composition in the shared MobileCLIP embedding space to satisfy
     * visual and textual constraints simultaneously.
     */
    private suspend fun searchCompound(
        request: SearchRequest.Compound,
        config: HybridSearchConfig
    ): HybridSearchResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val textQuery = request.query
        val visualVector = request.visualVector

        if (visualTextProvider == null || !visualTextProvider.isReady()) {
            // Fallback to visual-only if text encoder is not ready
            return@withContext searchVisual(request, config)
        }

        val channelResults = mutableMapOf<SearchChannel, List<RankedChannelItem>>()
        val channelCounts = mutableMapOf<SearchChannel, Int>()
        var semanticError: String? = null
        var compoundVector: FloatArray? = null

        coroutineScope {
            // 1. Generate text embedding for the constraint
            val textEmbeddingDeferred = async {
                val result = visualTextProvider.generateEmbedding(
                    mediaId = "query_text_${request.requestId}",
                    input = SemanticInput.Text(textQuery),
                    sourceDataHash = "query_text_hash"
                )
                (result as? EmbeddingResult.Success)?.representation?.vector
            }

            // 2. Fetch Lexical and MiniLM Content candidates in parallel
            val lexicalDeferred = async {
                try {
                    lexicalRetriever.retrieveKeywordCandidates(textQuery, topK = config.topK * 2)
                } catch (e: Exception) {
                    android.util.Log.e("AuraSemanticTrace", "Lexical search failed for compound query", e)
                    emptyList()
                }
            }

            val semanticDeferred = async {
                try {
                    semanticService.search(
                        query = textQuery,
                        topK = config.topK * 2,
                        minSimilarity = config.minSemanticSimilarity,
                        targetType = SemanticRepresentationType.CONTENT
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AuraSemanticTrace", "Semantic search failed for compound query", e)
                    null
                }
            }

            // 3. Compose vectors and perform Visual Retrieval
            val textVector = textEmbeddingDeferred.await()
            if (textVector != null && visualRetriever != null && visualRetriever.isReady()) {
                // COMPOUND SEMANTICS: Normalize the weighted sum of visual and textual evidence
                compoundVector = try {
                    val sum = FloatArray(visualVector.size) { i -> 
                        (visualVector[i] * COMPOUND_VISUAL_WEIGHT) + (textVector[i] * COMPOUND_TEXT_WEIGHT)
                    }
                    VectorMath.l2Normalize(sum)
                } catch (e: Exception) {
                    android.util.Log.w("AuraSemanticTrace", "Vector composition failed, falling back to visual vector", e)
                    visualVector
                }

                val visualCandidates = try {
                    visualRetriever.retrieveVisualCandidates(
                        queryVector = compoundVector!!,
                        topK = config.topK * 2,
                        minSimilarity = config.minSemanticSimilarity
                    )
                } catch (e: Exception) {
                    android.util.Log.e("AuraSemanticTrace", "Visual retrieval failed in compound search", e)
                    emptyList()
                }

                if (visualCandidates.isNotEmpty()) {
                    channelResults[SearchChannel.SEMANTIC_VISUAL] = visualCandidates
                    channelCounts[SearchChannel.SEMANTIC_VISUAL] = visualCandidates.size
                }
            }

            // Await Lexical
            val keywordCandidates = lexicalDeferred.await()
            if (keywordCandidates.isNotEmpty()) {
                channelResults[SearchChannel.KEYWORD] = keywordCandidates
                channelCounts[SearchChannel.KEYWORD] = keywordCandidates.size
            }

            // Await Semantic Content (MiniLM)
            val semanticResult = semanticDeferred.await()
            if (semanticResult != null && semanticResult.isSuccess) {
                val semanticRankedItems = semanticResult.candidates.mapIndexed { index, candidate ->
                    RankedChannelItem(
                        mediaId = candidate.mediaId,
                        rawScore = candidate.similarityScore,
                        rank = index + 1
                    )
                }
                if (semanticRankedItems.isNotEmpty()) {
                    channelResults[SearchChannel.SEMANTIC_CONTENT] = semanticRankedItems
                    channelCounts[SearchChannel.SEMANTIC_CONTENT] = semanticRankedItems.size
                }
            } else if (semanticResult != null && !semanticResult.isSuccess) {
                semanticError = semanticResult.errorMessage
            }
        }

        // --- INTEGRATE PERSONALIZATION ---
        if (personalizationScorer != null && config.channelWeights.getOrDefault(SearchChannel.PERSONALIZED, 0.0) > 0.0) {
            val candidateIds = channelResults.values.flatten().map { it.mediaId }.distinct()
            if (candidateIds.isNotEmpty()) {
                val personalizedItems = candidateIds.map { mediaId ->
                    mediaId to personalizationScorer.score(mediaId)
                }.sortedByDescending { it.second }
                 .mapIndexed { index, (mediaId, score) -> 
                    RankedChannelItem(
                        mediaId = mediaId,
                        rawScore = score,
                        rank = index + 1
                    )
                }

                channelResults[SearchChannel.PERSONALIZED] = personalizedItems
                channelCounts[SearchChannel.PERSONALIZED] = personalizedItems.size
            }
        }

        val totalUniqueCandidates = channelResults.values.flatten().map { it.mediaId }.distinct().size

        // Execute Reciprocal Rank Fusion
        val fusedCandidates = rrf.fuse(channelResults, config)

        // --- DEEP VIDEO RERANKING ---
        // Pass the compound vector to the reranker to promote videos matching both constraints in a single frame
        val topForRerank = fusedCandidates.take(RERANK_TOP_N)
        var framesLoadedCount = 0
        val frameVectors = if (repository != null && topForRerank.isNotEmpty() && compoundVector != null) {
            val mediaIds = topForRerank.map { it.mediaId }
            val frames = repository.getFramesForBatch(mediaIds)
            framesLoadedCount = frames.size
            frames.groupBy { it.mediaId }
        } else {
            emptyMap()
        }

        val rerankedCandidates = reranker.rerank(
            candidates = fusedCandidates,
            queryVector = compoundVector ?: visualVector,
            frameVectors = frameVectors
        )

        val finalResult = HybridSearchResult(
            query = textQuery,
            queryType = SearchQueryType.COMPOUND,
            requestId = request.requestId,
            candidates = rerankedCandidates,
            latencyMs = System.currentTimeMillis() - startTime,
            totalCandidatesConsidered = totalUniqueCandidates,
            channelCandidateCounts = channelCounts.toMap(),
            deepRerankCount = topForRerank.size,
            frameVectorsLoaded = framesLoadedCount,
            isSuccess = true,
            errorMessage = if (channelResults.isEmpty() && semanticError != null) semanticError else null
        )

        com.example.util.AuraSearchDiagnostics.logSearchResult(finalResult)
        finalResult
    }
}
