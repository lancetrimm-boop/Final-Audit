package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import com.example.compatibility.AuraMediaCompatibilityEngine
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.random.Random

/**
 * Reusable orchestration layer for Aura's intelligence capabilities.
 * Unifies Retrieval, Fusion, Evidence Generation, and Ranking.
 */
class AuraIntelligenceCore(
    private val repository: MediaRepository,
    internal val retrievalRouter: RetrievalRouter,
    private val reranker: MultimodalReranker = VideoIntelligenceReranker(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private class PerformanceCollector {
        var databaseMs: Long = 0
        var inferenceMs: Long = 0
        
        inline fun <T> timeDatabase(block: () -> T): T {
            val start = System.currentTimeMillis()
            return try { block() } finally { databaseMs += System.currentTimeMillis() - start }
        }

        inline fun <T> timeInference(block: () -> T): T {
            val start = System.currentTimeMillis()
            return try { block() } finally { inferenceMs += System.currentTimeMillis() - start }
        }
    }

    /**
     * Processes a generalized intelligence request.
     */
    suspend fun processRequest(request: IntelligenceRequest): IntelligenceResponse = withContext(dispatcher) {
        DecisionTraceCollector.startTrace(request.requestId, request.mode.name)
        
        // 0. Cache Lookup
        if (!request.skipPersistence) {
            IntelligenceCache.getResponse(request)?.let { 
                DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.FUSION_COMPLETED, "Cache Hit")
                repository.reportPerformance(OperationPerformance(
                    operationName = "${request.mode}:${request.sortOption ?: "Generic"}",
                    totalDurationMs = 0, // Instant
                    cacheHit = true
                ))
                return@withContext it 
            }
        }

        val collector = PerformanceCollector()
        val startTime = System.currentTimeMillis()
        var intelligenceStartTime = 0L
        var intelligenceDuration = -1L
        
        try {
            intelligenceStartTime = System.currentTimeMillis()
            val candidates = when(request.mode) {
                IntelligenceMode.SEARCH -> handleSearch(request, collector)
                IntelligenceMode.SORT -> handleSort(request, collector)
                IntelligenceMode.SIMILAR -> handleSimilar(request, collector)
                IntelligenceMode.DISCOVER -> handleDiscover(request, collector)
            }
            
            DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CANDIDATES_RETRIEVED, metadata = mapOf("count" to candidates.size.toString()))
            
            val totalLatency = System.currentTimeMillis() - startTime
            intelligenceDuration = System.currentTimeMillis() - intelligenceStartTime

            val rankingStartTime = System.currentTimeMillis()
            val sealedResults = seal(candidates, request)
            val topId = sealedResults.firstOrNull()?.item?.id
            DecisionTraceCollector.logEvent(
                request.requestId, 
                com.example.ui.models.TraceEventType.RANK_ASSIGNED, 
                detail = "Rank 1: ${topId?.take(8) ?: "none"}",
                metadata = mapOf(
                    "count" to sealedResults.size.toString(),
                    "itemId_hash" to (topId?.hashCode()?.toString() ?: "none"),
                    "rank" to "1"
                )
            )
            val rankingDuration = System.currentTimeMillis() - rankingStartTime

            val response = IntelligenceResponse(
                requestId = request.requestId,
                mode = request.mode,
                candidates = sealedResults,
                visibilitySealed = true, // Mark as sealed
                latencyMs = totalLatency
            )

            DecisionTraceCollector.logEvent(
                request.requestId, 
                com.example.ui.models.TraceEventType.PRESENTATION_MAPPED, 
                detail = "Response ready: ${sealedResults.size} items",
                metadata = mapOf("count" to sealedResults.size.toString())
            )

            if (!request.skipPersistence) {
                // 1. Cache Write
                IntelligenceCache.putResponse(request, response)
                
                // 2. Report Performance
                repository.reportPerformance(OperationPerformance(
                    operationName = "${request.mode}:${request.sortOption ?: "Generic"}",
                    totalDurationMs = totalLatency,
                    intelligenceDurationMs = intelligenceDuration,
                    rankingDurationMs = rankingDuration,
                    databaseDurationMs = collector.databaseMs,
                    inferenceDurationMs = collector.inferenceMs,
                    candidateCount = candidates.size,
                    resultCount = sealedResults.size,
                    cacheHit = false
                ))
            }
            
            response
        } catch (e: Exception) {
            val totalLatency = System.currentTimeMillis() - startTime
            if (!request.skipPersistence) {
                repository.reportPerformance(OperationPerformance(
                    operationName = "${request.mode}:${request.sortOption ?: "Generic"} [FAILED]",
                    totalDurationMs = totalLatency,
                    intelligenceDurationMs = intelligenceDuration,
                    databaseDurationMs = collector.databaseMs,
                    inferenceDurationMs = collector.inferenceMs
                ))
            }
            IntelligenceResponse(
                requestId = request.requestId,
                mode = request.mode,
                candidates = emptyList(),
                latencyMs = totalLatency,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    private suspend fun handleSearch(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        var searchRequest = request
        val query = request.query
        if (query != null && query.isNotBlank()) {
            // 1. Resolve CLIP vector (VISUAL channel)
            if (request.visualVector == null) {
                val textProvider = repository.mobileClipTextProvider
                if (textProvider != null && textProvider.isReady()) {
                    val result = collector.timeInference {
                        textProvider.generateEmbedding(
                            mediaId = "query_search_${request.requestId}",
                            input = SemanticInput.Text(query, SemanticRepresentationType.VISUAL),
                            sourceDataHash = "query_${query.hashCode()}"
                        )
                    }
                    if (result is EmbeddingResult.Success) {
                        searchRequest = searchRequest.copy(visualVector = result.representation.vector)
                    }
                }
            }
            // 2. Resolve MiniLM vector (CONTENT channel)
            if (searchRequest.queryVectors == null) {
                val contentProvider = repository.embeddingProvider
                if (contentProvider != null) {
                    val result = collector.timeInference {
                        contentProvider.generateEmbedding(
                            mediaId = "query_search_content_${request.requestId}",
                            input = SemanticInput.Text(query, SemanticRepresentationType.CONTENT),
                            sourceDataHash = "query_content_${query.hashCode()}"
                        )
                    }
                    if (result is EmbeddingResult.Success) {
                        searchRequest = searchRequest.copy(queryVectors = listOf(result.representation.vector))
                    }
                }
            }
        }

        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_START, "Channel retrieval starting")
        val channelResults = collector.timeDatabase { retrievalRouter.retrieve(searchRequest) }
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_COMPLETE, "Channel retrieval completed", metadata = mapOf("count" to channelResults.values.sumOf { it.size }.toString()))
        
        val fusionConfig = HybridSearchConfig(topK = searchRequest.limit * 2)
        val fused = RetrievalFusion.fuse(channelResults, fusionConfig)
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.FUSION_COMPLETED, "Fusion completed: ${fused.size} candidates")
        
        // Post-retrieval Reranking
        val queryVector = searchRequest.visualVector
        val topForRerank = fused.take(50).map { fc ->
            HybridCandidate(
                mediaId = fc.mediaId,
                rrfScore = fc.rrfScore,
                channelRanks = fc.channelRanks,
                channelScores = fc.channelScores,
                isAuthoritativeLexical = fc.isAuthoritative
            )
        }

        val mediaIds = topForRerank.map { it.mediaId }
        val frames = collector.timeDatabase {
            repository.semanticRepresentationRepository?.getFramesForBatch(mediaIds) ?: emptyList()
        }
        
        val descriptor = repository.mobileCLIPProvider?.descriptor
        val mainReps = if (descriptor != null) {
            repository.semanticRepresentationRepository?.getCompatibleRepresentations(
                SemanticRepresentationType.VISUAL,
                descriptor
            )?.filter { it.mediaId in mediaIds } ?: emptyList()
        } else emptyList()

        val allVisualEvidence = mutableListOf<VideoFrameRepresentation>()
        allVisualEvidence.addAll(frames)
        mainReps.forEach { rep ->
            allVisualEvidence.add(VideoFrameRepresentation(
                id = rep.id, mediaId = rep.mediaId, timestampUs = -1,
                modelDescriptor = rep.modelDescriptor, documentVersion = rep.documentVersion,
                dimensionality = rep.dimensionality, vector = rep.vector
            ))
        }

        val evidenceMap = allVisualEvidence.groupBy { it.mediaId }
        val reranked = reranker.rerank(
            candidates = topForRerank,
            queryVector = queryVector,
            queryVectors = searchRequest.queryVectors,
            frameVectors = evidenceMap
        )
        
        val rerankedFused = reranked.map { rc ->
            RetrievalFusion.FusedCandidate(
                mediaId = rc.mediaId, rrfScore = rc.rrfScore, channelRanks = rc.channelRanks,
                channelScores = rc.channelScores, isAuthoritative = rc.isAuthoritativeLexical
            )
        }

        return scoreAndRank(rerankedFused, searchRequest, collector)
    }

    private suspend fun handleSort(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val allItems = request.poolOverride ?: repository.mediaItems.value
        val now = System.currentTimeMillis()
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value
        
        val items = allItems.filter { item ->
            isItemVisibleInLibrary(item, request) && matchesFilterType(item, request.filterType)
        }

        DecisionTraceCollector.logEvent(
            request.requestId,
            com.example.ui.models.TraceEventType.POOL_FILTERED,
            detail = "${allItems.size} -> ${items.size} eligible",
            metadata = mapOf("count" to items.size.toString(), "originalCount" to allItems.size.toString())
        )

        val scored = when (request.sortOption) {
            "PERSONALIZED" -> {
                val systemState = collector.timeInference { ConfidenceEngine.calculateDiscoveryState(allItems, stats) }
                val strategy = DiscoveryPolicyManager.resolveStrategy(
                    policy = request.policy ?: DiscoveryPolicy(),
                    intent = request.intent ?: UserIntent(),
                    objective = RecommendationObjective.LIBRARY_INTELLIGENT_DISCOVERY,
                    systemState = systemState,
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                )

                val recentThreshold = 3600000L // 1 hour
                items.map { item ->
                    val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now) }
                    val baseScore = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    val personalScore = scorePersonalization(item, tasteDNA)
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.9f, EvidenceStatus.INFERRED, "ExplorationEngine"))
                    evidenceItems.add(EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, personalScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA"))
                    
                    val isRecent = item.lastViewedTimestamp?.let { now - it < recentThreshold } ?: false
                    // [PHASE 2] Even stronger personalization weight to ensure item1 (rating 5) beats item2 (rating 0).
                    var score = (baseScore.toDouble() * 0.1) + (personalScore.toDouble() * 0.9)
                    if (isRecent) score -= 0.8 

                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

                    // AURA STAGE 2: Jitter
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.001

                    IntelligenceCandidate(
                        item = item, evidence = evidenceItems, rankScore = score + relBonus + jitter, 
                        primaryRelevanceScore = personalScore, secondaryEvidenceScore = baseScore,
                        provenance = if (evidence.exploitationScore > 0.6) "For You" else "Personalized"
                    )
                }
            }
            "DISCOVER" -> {
                val strategy = DiscoveryPolicyManager.resolveStrategy(
                    policy = request.policy ?: DiscoveryPolicy(),
                    intent = request.intent ?: UserIntent(),
                    objective = RecommendationObjective.GENERAL_DISCOVERY,
                    systemState = collector.timeInference { ConfidenceEngine.calculateDiscoveryState(allItems, stats) },
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                ).copy(exploitationWeight = 0.2f, explorationWeight = 0.8f)

                items.map { item ->
                    val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now) }
                    val baseScore = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    val seenCount = item.viewCount + (item.exposureCount / 5)
                    val seenPenalty = (seenCount * 0.1).coerceAtMost(0.9)
                    val explorationBoost = (evidence.uncertaintyScore * 0.5) + (evidence.noveltyScore * 0.5)
                    val score = (baseScore.toDouble() * (1.0 - seenPenalty)) + (explorationBoost * 2.0)
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)
                    val matchPercent = (baseScore * 100).toInt().coerceIn(10, 99)

                    // AURA STAGE 2: Jitter
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.1

                    IntelligenceCandidate(
                        item = item.copy(selectionReason = "$matchPercent% Match"),
                        evidence = evidenceItems, rankScore = score + relBonus + jitter,
                        primaryRelevanceScore = score.toFloat(), secondaryEvidenceScore = jitter.toFloat(), provenance = "Discover Sort"
                    )
                }
            }
            "RANKING_REFINEMENT" -> {
                val counts = request.comparisonCounts ?: emptyMap()
                items.map { item ->
                    val count = counts[item.id] ?: 0
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.1
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.PAIRWISE_PREFERENCE, count.toFloat(), 1.0f, EvidenceStatus.KNOWN, "ComparisonCount")),
                        rankScore = (-count).toDouble() + jitter,
                        primaryRelevanceScore = (-count).toFloat(),
                        secondaryEvidenceScore = jitter.toFloat(),
                        provenance = "Ranking Refinement"
                    )
                }
            }
            "NEWEST_FIRST", "NEWEST" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), item.dateAdded.toDouble(), item.dateAdded.toFloat(), 0f, provenance = "Newest First")
            }
            "OLDEST" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), -item.dateAdded.toDouble(), -item.dateAdded.toFloat(), 0f, provenance = "Oldest First")
            }
            "RECENTLY_PLAYED" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), (item.lastViewedTimestamp ?: 0L).toDouble(), (item.lastViewedTimestamp ?: 0L).toFloat(), 0f, provenance = "Recently Played")
            }
            "TITLE_ASC" -> items.sortedBy { it.title }.mapIndexed { idx, item ->
                IntelligenceCandidate(item, emptyList(), (-idx).toDouble(), 0f, 0f, provenance = "Title A-Z")
            }
            "TITLE_DESC" -> items.sortedByDescending { it.title }.mapIndexed { idx, item ->
                IntelligenceCandidate(item, emptyList(), (-idx).toDouble(), 0f, 0f, provenance = "Title Z-A")
            }
            "SHORTEST_DURATION" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), -item.durationMs.toDouble(), 0f, 0f, provenance = "Shortest First")
            }
            "LONGEST_DURATION" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), item.durationMs.toDouble(), 0f, 0f, provenance = "Longest First")
            }
            "MOST_PLAYED" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), item.viewCount.toDouble(), 0f, 0f, provenance = "Most Played")
            }
            "LEAST_PLAYED" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), -item.viewCount.toDouble(), 0f, 0f, provenance = "Least Played")
            }
            "RANDOM" -> items.map { item ->
                val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble()
                IntelligenceCandidate(item, emptyList(), jitter, 0f, 0f, provenance = "Random")
            }
            "SIZE" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), item.sizeBytes.toDouble(), item.sizeBytes.toFloat(), 0f, provenance = "Largest Files")
            }
            "RATING" -> items.map { item ->
                IntelligenceCandidate(item, emptyList(), item.rating.toDouble(), item.rating, 0f, provenance = "Rating")
            }
            "LEAST_INTERACTED" -> items.map { item ->
                val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.01
                IntelligenceCandidate(
                    item = item, evidence = emptyList(), rankScore = -item.viewCount.toDouble() + jitter,
                    primaryRelevanceScore = -item.viewCount.toFloat(), secondaryEvidenceScore = jitter.toFloat(),
                    provenance = "Least Interacted"
                )
            }
            "EXPLORE" -> {
                items.map { item ->
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.1
                    IntelligenceCandidate(item, emptyList(), jitter, 0f, 0f, provenance = "Explore")
                }
            }
            "REDISCOVER" -> {
                items.map { item ->
                    val ageBonus = (now - (item.lastViewedTimestamp ?: 0L)).toDouble() / (1000.0 * 60 * 60 * 24 * 7)
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.01
                    val score = (item.rating.toDouble() * 20.0) + (item.viewCount.toDouble() * 2.0) + ageBonus + jitter
                    IntelligenceCandidate(
                        item = item, evidence = emptyList(), rankScore = score,
                        primaryRelevanceScore = item.rating, secondaryEvidenceScore = ageBonus.toFloat()
                    )
                }
            }
            "FAVORITES" -> {
                val favItems = items.filter { it.isFavorite || it.rating >= 4.0f }
                val likeCounts = collector.timeDatabase { repository.getActualLikeCounts(favItems.map { it.id }) }
                favItems.map { item ->
                    val likes = likeCounts[item.id] ?: 0
                    val durationSec = item.durationMs / 1000.0
                    val likeDensity = likes.toDouble() / (durationSec + 10.0)
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.001
                    val score = likeDensity + jitter
                    IntelligenceCandidate(
                        item = item, evidence = emptyList(), rankScore = score,
                        primaryRelevanceScore = likeDensity.toFloat(), secondaryEvidenceScore = if (item.isFavorite) 1f else 0f
                    )
                }
            }
            "SURPRISE_ME" -> {
                items.map { item ->
                    val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.1
                    IntelligenceCandidate(item, emptyList(), jitter, 0f, 0f, provenance = "Surprise Me")
                }
            }
            else -> items.map { item ->
                val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.01
                IntelligenceCandidate(item, emptyList(), jitter, 0f, 0f)
            }
        }

        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.SCORING_COMPLETED, detail = "Scoring completed for ${scored.size} items")

        return finalizeResults(scored, request)
    }

    private suspend fun handleSimilar(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val refId = request.referenceItemId ?: return emptyList()
        val provider = repository.mobileCLIPProvider
        val semanticRepo = repository.semanticRepresentationRepository
        val visualVector = request.visualVector ?: if (provider != null && semanticRepo != null) {
             collector.timeDatabase { semanticRepo.getSpecificRepresentation(refId, SemanticRepresentationType.VISUAL, provider.descriptor)?.vector }
        } else null

        if (visualVector == null) throw IllegalStateException("STILL_PROCESSING")
        
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_START, "Channel retrieval starting for similar")
        val channelResults = collector.timeDatabase { retrievalRouter.retrieve(request.copy(visualVector = visualVector)) }
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_COMPLETE, "Channel retrieval completed for similar", metadata = mapOf("count" to channelResults.values.sumOf { it.size }.toString()))
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.CANDIDATES_RETRIEVED, "Candidates retrieved for similar", metadata = mapOf("count" to channelResults.values.sumOf { it.size }.toString()))
        
        val fused = RetrievalFusion.fuse(channelResults, HybridSearchConfig(topK = request.limit * 2))
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.FUSION_COMPLETED, "Fusion completed for similar: ${fused.size} candidates")
        val filteredFused = fused.filter { it.mediaId != refId }
        
        return scoreAndRank(filteredFused, request.copy(visualVector = visualVector), collector)
    }

    private suspend fun handleDiscover(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val allItems = request.poolOverride ?: repository.mediaItems.value
        val itemsOnly = allItems.filter { isItemVisibleInLibrary(it, request) && it.itemCount == null }
        val stats = request.stats ?: repository.intelligenceStats.value
        val dna = request.tasteDNA ?: repository.tasteDNA.value
        val profile = request.profile ?: repository.preferenceProfile.value
        val now = System.currentTimeMillis()
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value

        val filteredItems = when (request.sortOption) {
            "FROM_YOUR_FAVORITES" -> itemsOnly.filter { it.isFavorite || it.rating >= 4.0f }
            "FRESH_FOR_YOU" -> itemsOnly.filter { now - it.dateAdded < 7 * 24 * 60 * 60 * 1000L }
            else -> itemsOnly
        }

        DecisionTraceCollector.logEvent(
            request.requestId,
            com.example.ui.models.TraceEventType.POOL_FILTERED,
            detail = "${allItems.size} -> ${filteredItems.size} eligible",
            metadata = mapOf("count" to filteredItems.size.toString(), "originalCount" to allItems.size.toString())
        )

        val objective = when (request.sortOption) {
            "UNDER_THE_RADAR", "DEEP_DISCOVERY" -> RecommendationObjective.DEEP_DISCOVERY
            "FRESH_FOR_YOU" -> RecommendationObjective.NOVELTY_INJECTION
            else -> RecommendationObjective.GENERAL_DISCOVERY
        }

        val strategy = DiscoveryPolicyManager.resolveStrategy(
            policy = request.policy ?: DiscoveryPolicy(), intent = request.intent ?: UserIntent(),
            objective = objective, systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats),
            tasteDNA = dna, profile = profile
        )

        val candidates = filteredItems.map { item ->
            val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, dna, stats, creators, now) }
            val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
            val evidenceItems = mutableListOf<EvidenceItem>()
            val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)
            val matchPercent = (score * 100).toInt().coerceIn(10, 99)

            // AURA STAGE 2: Jitter
            val jitter = Random(item.id.hashCode().toLong() + request.seed).nextDouble() * 0.01

            IntelligenceCandidate(
                item = item.copy(selectionReason = "$matchPercent% Match"),
                evidence = evidenceItems, rankScore = score.toDouble() + relBonus + jitter,
                primaryRelevanceScore = score, secondaryEvidenceScore = evidence.explorationScore,
                provenance = "Discover:${request.sortOption ?: "General"}"
            )
        }
        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.SCORING_COMPLETED, "Discover scoring completed for ${candidates.size} items")
        return finalizeResults(candidates, request)
    }

    private suspend fun scoreAndRank(
        fused: List<RetrievalFusion.FusedCandidate>, 
        request: IntelligenceRequest,
        collector: PerformanceCollector
    ): List<IntelligenceCandidate> {
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value
        val config = HybridSearchConfig()
        
        val isTextSearch = !request.query.isNullOrBlank()
        val isPureVisual = !isTextSearch && (request.visualVector != null || request.mode == IntelligenceMode.SIMILAR)
        val precisionThreshold = when {
            isTextSearch -> config.minTextSemanticSimilarity
            isPureVisual -> config.minSemanticSimilarity
            else -> 0.0f
        }

        val candidates = fused.mapNotNull { fusedCandidate ->
            val item = collector.timeDatabase { resolveItem(fusedCandidate.mediaId) } ?: return@mapNotNull null
            if (!isItemVisibleInLibrary(item, request)) return@mapNotNull null
            
            val keywordScore = fusedCandidate.channelScores[SearchChannel.KEYWORD] ?: 0f
            val maxNeuralSimilarity = fusedCandidate.channelScores.filter { (channel, _) ->
                channel == SearchChannel.SEMANTIC_CONTENT || channel == SearchChannel.SEMANTIC_VISUAL
            }.values.maxOrNull() ?: 0f

            val passesNeuralGate = maxNeuralSimilarity >= precisionThreshold
            val passesLexicalGate = fusedCandidate.isAuthoritative || (isTextSearch && keywordScore > 0f)

            val needsPrecisionGate = request.mode == IntelligenceMode.SEARCH || request.mode == IntelligenceMode.SIMILAR
            if (needsPrecisionGate && !passesLexicalGate && !passesNeuralGate) {
                DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.ELIGIBILITY_CHECK, detail = "Dropped (Precision)", metadata = mapOf("itemId" to item.id))
                return@mapNotNull null
            }

            val evidence = mutableListOf<EvidenceItem>()
            
            // a. Retrieval Evidence
            fusedCandidate.channelRanks.forEach { (channel, rank) ->
                val score = fusedCandidate.channelScores[channel] ?: 0f
                evidence.add(EvidenceItem(
                    type = when(channel) {
                        SearchChannel.KEYWORD -> EvidenceType.LEXICAL_MATCH
                        SearchChannel.SEMANTIC_CONTENT -> EvidenceType.SEMANTIC_RELEVANCE
                        SearchChannel.SEMANTIC_VISUAL -> EvidenceType.VISUAL_SIMILARITY
                        SearchChannel.PERSONALIZED -> EvidenceType.TASTE_DNA_ALIGNMENT
                    },
                    score = score,
                    confidence = if (score > 0) 0.8f else 0.0f,
                    status = if (channel == SearchChannel.KEYWORD) EvidenceStatus.KNOWN else EvidenceStatus.INFERRED,
                    provenance = channel.name,
                    metadata = mapOf("channel_rank" to rank.toString())
                ))
            }
            
            val personalScore = scorePersonalization(item, tasteDNA)
            evidence.add(EvidenceItem(
                type = EvidenceType.TASTE_DNA_ALIGNMENT,
                score = personalScore,
                confidence = 0.9f,
                status = EvidenceStatus.INFERRED,
                provenance = "TasteDNA"
            ))
            
            val explorationEvidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators)
            evidence.add(EvidenceItem(
                type = EvidenceType.EXPLORATION_VALUE,
                score = explorationEvidence.explorationScore,
                confidence = 0.7f,
                status = EvidenceStatus.INFERRED,
                provenance = "ExplorationEngine"
            ))
            val wins = repository.getPairwiseWins()[item.id] ?: 0
            val losses = repository.getPairwiseLosses()[item.id] ?: 0
            val pairwiseImpact = (wins - losses).toFloat() * 0.1f
            if (wins > 0 || losses > 0) {
                evidence.add(EvidenceItem(
                    type = EvidenceType.PAIRWISE_PREFERENCE,
                    score = pairwiseImpact,
                    confidence = 1.0f,
                    status = EvidenceStatus.KNOWN,
                    provenance = "InteractionMemory"
                ))
            }
            val relBonus = scoreRelationships(item, request.relatedMediaIds, evidence)

            val finalScore = (fusedCandidate.rrfScore * 0.7) + (personalScore * 0.3) + (explorationEvidence.explorationScore * 0.05) + (relBonus * 0.1) + pairwiseImpact

            IntelligenceCandidate(
                item = item, evidence = evidence, rankScore = finalScore,
                primaryRelevanceScore = fusedCandidate.rrfScore.toFloat(),
                secondaryEvidenceScore = personalScore.toFloat(),
                provenance = buildProvenance(evidence, finalScore)
            )
        }
        return finalizeResults(candidates, request)
    }

    private fun seal(candidates: List<IntelligenceCandidate>, request: IntelligenceRequest): List<IntelligenceCandidate> {
        return candidates.filter { 
            val visible = isItemVisibleInLibrary(it.item, request)
            if (!visible) {
                DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.ELIGIBILITY_CHECK, detail = "Dropped at Seal", metadata = mapOf("itemId" to it.item.id))
            }
            visible
        }
    }

    private suspend fun resolveItem(id: String): MediaItem? {
        return repository.getMediaItemById(id) ?: repository.getMediaItemByIdAuthoritative(id)
    }

    private fun isItemVisibleInLibrary(item: MediaItem, request: IntelligenceRequest): Boolean {
        if (request.poolOverride != null) return !item.isDeleted && item.compatibilityStatus !in listOf(CompatibilityStatus.CORRUPT, CompatibilityStatus.UNSUPPORTED, CompatibilityStatus.DELETED)
        return repository.isItemVisibleInLibrary(item)
    }

    private suspend fun scoreRelationships(item: MediaItem, relatedIds: List<String>, evidence: MutableList<EvidenceItem>): Double {
        if (relatedIds.isEmpty()) return 0.0
        var bonus = 0.0
        val styleProfile = repository.signatureStyleProfile.value
        relatedIds.forEach { rid ->
            val related = resolveItem(rid) ?: return@forEach
            styleProfile.activeStyles.forEach { style ->
                if (SignatureStyleProvider.calculateMediaAffinity(item, style.anchor) > 0.8 &&
                    SignatureStyleProvider.calculateMediaAffinity(related, style.anchor) > 0.8) {
                    bonus += 0.05
                    evidence.add(EvidenceItem(EvidenceType.RELATIONSHIP_MATCH, 0.05f, 0.9f, EvidenceStatus.INFERRED, "StyleContinuity:${style.anchor.id}"))
                }
            }
        }
        return bonus.coerceAtMost(0.3)
    }

    private fun finalizeResults(candidates: List<IntelligenceCandidate>, request: IntelligenceRequest): List<IntelligenceCandidate> {
        val sorted = candidates.sortedWith(
            compareByDescending<IntelligenceCandidate> { it.rankScore }
                .thenByDescending { it.primaryRelevanceScore }
                .thenBy { it.item.id }
        ).take(request.limit)
        
        val styleProfile = try { repository.signatureStyleProfile.value } catch (_: Exception) { null }
        return if (styleProfile != null) {
            sorted.map { IntelligentPresentationProvider.annotateCandidate(it, styleProfile) }
        } else sorted
    }

    private fun buildProvenance(evidence: List<EvidenceItem>, finalScore: Double): String = "Score ${"%.3f".format(finalScore)}"

    private fun matchesFilterType(item: MediaItem, filterType: String): Boolean {
        return when (filterType.uppercase()) {
            "PHOTO" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE")
            "VIDEO" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE")
            else -> true
        }
    }

    internal fun scorePersonalization(item: MediaItem, tasteDNA: TasteDNA): Float {
        val explicitBoost = if (item.isFavorite) 0.5f else 0.0f
        val ratingScore = item.rating / 5.0f
        
        val traits = PersonalizationTraitMapper.getEffectiveTraitAdjustments(item)
        if (traits.isEmpty()) return (0.5f + explicitBoost + ratingScore * 0.2f).coerceIn(0f, 1.0f)
        
        var sumAlignment = 0.0
        traits.forEach { (dim, presence) ->
            val itemTraitValue = (presence + 1.0) / 2.0
            val userPref = getDimensionValue(tasteDNA, dim)
            val alignment = 1.0 - abs(userPref - itemTraitValue)
            sumAlignment += alignment
        }
        val dnaAlignment = (sumAlignment / traits.size).toFloat()
        
        return (dnaAlignment + explicitBoost + ratingScore * 0.2f).coerceIn(0f, 1.0f)
    }

    private fun getDimensionValue(tasteDNA: TasteDNA, dimension: String): Double {
        return when(dimension) {
            "vibrancy" -> tasteDNA.effectiveVibrancy
            "contrast" -> tasteDNA.effectiveContrast
            "sharpness" -> tasteDNA.effectiveSharpness
            "symmetry" -> tasteDNA.effectiveSymmetry
            "complexity" -> tasteDNA.effectiveComplexity
            "naturalism" -> tasteDNA.effectiveNaturalism
            "novelty" -> tasteDNA.effectiveNovelty
            "lighting" -> tasteDNA.effectiveLighting
            "colorTemperature" -> tasteDNA.effectiveColorTemp
            "texture" -> tasteDNA.effectiveTexture
            "motion" -> tasteDNA.effectiveMotion
            "dynamicRange" -> tasteDNA.effectiveDynamicRange
            "framing" -> tasteDNA.effectiveFraming
            "depth" -> tasteDNA.effectiveDepth
            "warmth" -> tasteDNA.effectiveWarmth
            "saturation" -> tasteDNA.effectiveSaturation
            "elegance" -> tasteDNA.effectiveElegance
            "minimalism" -> tasteDNA.effectiveMinimalism
            "grain" -> tasteDNA.effectiveGrain
            "focus" -> tasteDNA.effectiveFocus
            "density" -> tasteDNA.effectiveDensity
            "rhythm" -> tasteDNA.effectiveRhythm
            "mood" -> tasteDNA.effectiveMood
            "harmony" -> tasteDNA.effectiveHarmony
            else -> 0.5
        }
    }
}
