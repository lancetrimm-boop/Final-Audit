package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import com.example.compatibility.AuraMediaCompatibilityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Reusable orchestration layer for Aura's intelligence capabilities.
 * Unifies Retrieval, Fusion, Evidence Generation, and Ranking.
 */
class AuraIntelligenceCore(
    private val repository: MediaRepository,
    internal val retrievalRouter: RetrievalRouter,
    private val reranker: MultimodalReranker = VideoIntelligenceReranker()
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
    suspend fun processRequest(request: IntelligenceRequest): IntelligenceResponse = withContext(Dispatchers.Default) {
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
            
            val totalLatency = System.currentTimeMillis() - startTime
            intelligenceDuration = System.currentTimeMillis() - intelligenceStartTime

            val rankingStartTime = System.currentTimeMillis()
            val sealedResults = seal(candidates, request.requestId)
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
        // AURA SEARCH REPAIR 3.3: Resolve CLIP text vector for both retrieval AND post-retrieval reranking.
        // This ensures text-based conceptual searches (e.g. "car") can undergo deep frame analysis
        // without redundant inference.
        var searchRequest = request
        if (request.visualVector == null && request.query != null && request.query.isNotBlank()) {
            val textProvider = repository.mobileClipTextProvider
            if (textProvider != null && textProvider.isReady()) {
                val result = collector.timeInference {
                    textProvider.generateEmbedding(
                        mediaId = "query_search_${request.requestId}",
                        input = SemanticInput.Text(request.query, SemanticRepresentationType.VISUAL),
                        sourceDataHash = "query_${request.query.hashCode()}"
                    )
                }
                if (result is EmbeddingResult.Success) {
                    searchRequest = request.copy(visualVector = result.representation.vector)
                    DecisionTraceCollector.logEvent(
                        request.requestId, 
                        com.example.ui.models.TraceEventType.SCORING_COMPLETED, 
                        detail = "Query embedding generated",
                        metadata = mapOf("channel" to "QUERY_INFERENCE")
                    )
                }
            }
        }

        val channelResults = collector.timeDatabase { retrievalRouter.retrieve(searchRequest) }
        val channelNames = channelResults.keys.sortedBy { it.name }.joinToString()
        DecisionTraceCollector.logEvent(
            request.requestId, 
            com.example.ui.models.TraceEventType.CANDIDATES_RETRIEVED, 
            detail = "Channels: $channelNames",
            metadata = mapOf("count" to channelResults.values.sumOf { it.size }.toString())
        )
        val fusionConfig = HybridSearchConfig(topK = searchRequest.limit * 2)
        val fused = RetrievalFusion.fuse(channelResults, fusionConfig)
        DecisionTraceCollector.logEvent(
            request.requestId, 
            com.example.ui.models.TraceEventType.FUSION_COMPLETED, 
            detail = "Fused to ${fused.size} candidates",
            metadata = mapOf("count" to fused.size.toString())
        )
        
        // Post-retrieval Reranking (Stage 8 Video Intelligence Integration)
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
        
        // AURA REPAIR: Also include main visual representations as "frames" for reranking
        // This ensures the reranker can perform Soft Intersection on photos and video aggregate vectors too.
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
                id = rep.id,
                mediaId = rep.mediaId,
                timestampUs = -1, // Use -1 to denote "Aggregate/Main" representation
                modelDescriptor = rep.modelDescriptor,
                documentVersion = rep.documentVersion,
                dimensionality = rep.dimensionality,
                vector = rep.vector
            ))
        }

        val evidenceMap = allVisualEvidence.groupBy { it.mediaId }

        DecisionTraceCollector.logEvent(
            request.requestId, 
            com.example.ui.models.TraceEventType.RERANKING_STARTED, 
            detail = "Reranking ${topForRerank.size} candidates",
            metadata = mapOf("count" to topForRerank.size.toString())
        )
        val reranked = reranker.rerank(
            candidates = topForRerank,
            queryVector = queryVector,
            queryVectors = searchRequest.queryVectors, // Support multi-reference if present
            frameVectors = evidenceMap
        )
        DecisionTraceCollector.logEvent(
            request.requestId, 
            com.example.ui.models.TraceEventType.RERANKING_COMPLETED,
            metadata = mapOf("count" to reranked.size.toString())
        )
        
        val rerankedFused = reranked.map { rc ->
            RetrievalFusion.FusedCandidate(
                mediaId = rc.mediaId,
                rrfScore = rc.rrfScore,
                channelRanks = rc.channelRanks,
                channelScores = rc.channelScores,
                isAuthoritative = rc.isAuthoritativeLexical
            )
        }

        val results = scoreAndRank(rerankedFused, searchRequest, collector)
        
        // Update 7: Style Annotation
        val styleProfile = repository.signatureStyleProfile.value
        return results.map { IntelligentPresentationProvider.annotateCandidate(it, styleProfile) }
    }

    private suspend fun handleSort(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val allItems = request.poolOverride ?: repository.mediaItems.value
        val now = System.currentTimeMillis()
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value
        
        val items = allItems.filter { 
            val eligible = matchesFilterType(it, request.filterType) && isItemVisibleInLibrary(it) 
            if (!eligible) {
                // Limit logging to avoid memory pressure on traces
                if (allItems.indexOf(it) < 50) {
                    DecisionTraceCollector.logEvent(
                        request.requestId, 
                        com.example.ui.models.TraceEventType.ELIGIBILITY_CHECK, 
                        detail = "Dropped: ${it.id.take(8)}",
                        metadata = mapOf("itemId_hash" to it.id.hashCode().toString(), "eligible" to "false")
                    )
                }
            }
            eligible
        }
        
        // Log aggregate eligibility
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
                items.mapIndexed { index, item ->
                    val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now) }
                    val baseScore = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    val personalScore = scorePersonalization(item, tasteDNA)
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.exploitationScore, 0.9f, EvidenceStatus.INFERRED, "ExplorationEngine"))
                    evidenceItems.add(EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, personalScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA"))
                    
                    // Limit individual scoring logs to top items or first 20 to avoid trace bloat
                    if (index < 20) {
                        DecisionTraceCollector.logEvent(
                            request.requestId, 
                            com.example.ui.models.TraceEventType.SCORING_COMPLETED, 
                            detail = "Item: ${item.id.take(8)}, Base: $baseScore",
                            metadata = mapOf(
                                "itemId_hash" to item.id.hashCode().toString(),
                                "score" to baseScore.toString(),
                                "personalScore" to personalScore.toString()
                            )
                        )
                    }
                    
                    // AURA REPAIR: Soft penalties instead of hard filters
                    val isLiked = item.isFavorite || item.rating >= 4.0f
                    val isRecent = item.lastViewedTimestamp?.let { now - it < recentThreshold } ?: false
                    
                    var score = baseScore.toDouble()
                    if (isLiked) score -= 0.5 // Penalty for already liked items to surface new content
                    if (isRecent) score -= 0.8 // Heavy penalty for recently viewed

                    // AURA STAGE 2: Seeded Jitter to break deterministic tie-breaking (item.id)
                    // and introduce session-level variety among similarly ranked items.
                    val jitter = kotlin.random.Random(item.id.hashCode().toLong() xor request.seed).nextDouble() * 0.01

                    // Update 9: Relationship Evidence
                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score + relBonus + jitter,
                        primaryRelevanceScore = score.toFloat(),
                        secondaryEvidenceScore = personalScore,
                        provenance = when {
                            evidence.exploitationScore > 0.6 && evidence.familiarityScore > 0.4 -> "For You"
                            evidence.exploitationScore > 0.5 && evidence.familiarityScore < 0.3 -> "Hidden Gem"
                            else -> "Personalized"
                        }
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
                    
                    // AURA REPAIR: Soft penalties for seen content
                    val seenCount = item.viewCount + (item.exposureCount / 5)
                    val seenPenalty = (seenCount * 0.1).coerceAtMost(0.9)
                    
                    // Incorporate Uncertainty/Novelty boost (Consolidated from EXPLORE)
                    val explorationBoost = (evidence.uncertaintyScore * 0.5) + (evidence.noveltyScore * 0.5)
                    
                    val score = (baseScore.toDouble() * (1.0 - seenPenalty)) + (explorationBoost * 2.0)
                    
                    // AURA STAGE 2: High variety jitter for Discover
                    val jitter = kotlin.random.Random(item.id.hashCode().toLong() xor request.seed).nextDouble() * 0.1

                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine"))
                    
                    // Update 9: Relationship Evidence
                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score + relBonus + jitter,
                        primaryRelevanceScore = score.toFloat(),
                        secondaryEvidenceScore = 0f,
                        provenance = "Discover Sort"
                    )
                }
            }
            "REDISCOVER" -> {
                val recentThreshold = 3600000L // 1 hour
                items.filter { item ->
                    val isLiked = item.isFavorite || item.rating >= 4.0f
                    val isRecent = item.lastViewedTimestamp?.let { now - it < recentThreshold } ?: false
                    isLiked && !isRecent
                }.map { item ->
                    val ageBonus = if (item.lastViewedTimestamp != null) {
                        (now - item.lastViewedTimestamp).toDouble() / (1000.0 * 60 * 60 * 24 * 7) // weeks
                    } else 100.0
                    
                    val score = (item.rating.toDouble() * 20.0) + (item.viewCount.toDouble() * 2.0) + ageBonus
                    
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.RECENCY_BONUS, ageBonus.toFloat(), 1.0f, EvidenceStatus.KNOWN, "RediscoverAge")),
                        rankScore = score,
                        primaryRelevanceScore = item.rating,
                        secondaryEvidenceScore = ageBonus.toFloat()
                    )
                }
            }
            "FAVORITES" -> {
                val favItems = items.filter { item -> item.isFavorite || item.rating >= 4.0f }
                val likeCounts = collector.timeDatabase { repository.getActualLikeCounts(favItems.map { it.id }) }

                favItems.map { item ->
                    val likes = likeCounts[item.id] ?: 0
                    val durationSec = item.durationMs / 1000.0
                    // Like Density: actualLikes / (durationSec + 10s smoothing)
                    val likeDensity = likes.toDouble() / (durationSec + 10.0)
                    
                    val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now) }
                    
                    // Ranking: Primarily Like Density + Favorite Boost + DNA Alignment
                    val score = (likeDensity * 100.0) + (if (item.isFavorite) 50.0 else 0.0) + (evidence.exploitationScore * 10.0)
                    
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.PAIRWISE_PREFERENCE, likes.toFloat(), 1.0f, EvidenceStatus.KNOWN, "ActualLikes"))
                    evidenceItems.add(EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, evidence.exploitationScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA"))

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score,
                        primaryRelevanceScore = likeDensity.toFloat(),
                        secondaryEvidenceScore = evidence.exploitationScore,
                        provenance = "Your Favorite"
                    )
                }
            }
            "SURPRISE_ME" -> {
                val random = kotlin.random.Random(request.seed)
                items.shuffled(random).map { item ->
                    IntelligenceCandidate(
                        item = item,
                        evidence = emptyList(),
                        rankScore = random.nextDouble(),
                        primaryRelevanceScore = 0f,
                        secondaryEvidenceScore = 0f,
                        provenance = "Surprise Me"
                    )
                }
            }
            else -> items.map { 
                IntelligenceCandidate(it, emptyList(), 0.0, 0f, 0f) 
            }
        }

        return scored.sortedWith(
            compareByDescending<IntelligenceCandidate> { it.rankScore }
                .thenByDescending { it.primaryRelevanceScore }
                .thenBy { it.item.id }
        ).take(request.limit)
    }

    private suspend fun handleSimilar(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val refId = request.referenceItemId ?: return emptyList()
        val refItem = repository.getMediaItemById(refId)
        
        // 1. Resolve context and embedding for reference item (Update 9.1 Consolidation)
        val provider = repository.mobileCLIPProvider
        val semanticRepo = repository.semanticRepresentationRepository
        
        val visualVector = request.visualVector ?: if (provider != null && semanticRepo != null) {
             collector.timeDatabase { semanticRepo.getSpecificRepresentation(refId, SemanticRepresentationType.VISUAL, provider.descriptor)?.vector }
        } else null

        // AURA REPAIR: Identify processing delay for specific reference search
        if (visualVector == null) {
            throw IllegalStateException("STILL_PROCESSING")
        }
        
        val query = request.query ?: refItem?.title
        
        // 2. Execute retrieval via Router (Unified Path)
        val similarRequest = request.copy(visualVector = visualVector, query = query)
        val channelResults = collector.timeDatabase { retrievalRouter.retrieve(similarRequest) }
        
        // 3. Fusion and Ranking
        val fused = RetrievalFusion.fuse(channelResults, HybridSearchConfig(topK = request.limit * 2))
        
        // Exclude the reference item itself
        val filteredFused = fused.filter { it.mediaId != refId }
        
        val results = scoreAndRank(filteredFused, request, collector)
        
        // Update 7: Style Annotation
        val styleProfile = repository.signatureStyleProfile.value
        return results.map { IntelligentPresentationProvider.annotateCandidate(it, styleProfile) }
    }

    private fun handleDiscover(request: IntelligenceRequest, collector: PerformanceCollector): List<IntelligenceCandidate> {
        val allItems = request.poolOverride ?: repository.mediaItems.value
        val itemsOnly = allItems.filter { 
            it.itemCount == null && 
            AuraMediaCompatibilityEngine.isEligibleForImport(it.compatibilityStatus) &&
            !it.isDeleted &&
            isItemVisibleInLibrary(it)
        }

        val stats = request.stats ?: repository.intelligenceStats.value
        val dna = request.tasteDNA ?: repository.tasteDNA.value
        val profile = request.profile ?: repository.preferenceProfile.value
        val now = System.currentTimeMillis()
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value

        // Category-specific pre-filtering (if applicable)
        val filteredItems = when (request.sortOption) {
            "FROM_YOUR_FAVORITES" -> itemsOnly.filter { it.isFavorite || it.rating >= 4.0f }
            "DEEP_DISCOVERY", "UNDER_THE_RADAR" -> itemsOnly.filter { it.exposureCount < 2 && it.viewCount == 0 }
            "FRESH_FOR_YOU" -> {
                val recentThreshold = 7 * 24 * 60 * 60 * 1000L // 1 week
                itemsOnly.filter { now - it.dateAdded < recentThreshold }
            }
            else -> itemsOnly
        }

        val objective = when (request.sortOption) {
            "A_LITTLE_DIFFERENT", "WILDCARD" -> RecommendationObjective.WILDCARD_DISCOVERY
            "UNDER_THE_RADAR", "DEEP_DISCOVERY" -> RecommendationObjective.DEEP_DISCOVERY
            "FRESH_FOR_YOU" -> RecommendationObjective.NOVELTY_INJECTION
            "FROM_YOUR_FAVORITES" -> RecommendationObjective.CHILL_EXPLOITATION
            else -> RecommendationObjective.GENERAL_DISCOVERY
        }

        val strategy = DiscoveryPolicyManager.resolveStrategy(
            policy = request.policy ?: DiscoveryPolicy(),
            intent = request.intent ?: UserIntent(),
            objective = objective,
            systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats),
            tasteDNA = dna,
            profile = profile
        )

        return filteredItems.map { item ->
            val evidence = collector.timeInference { ExplorationEngine.calculateEvidence(item, dna, stats, creators, now) }
            val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
            
            val evidenceItems = mutableListOf<EvidenceItem>()
            evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine"))
            
            val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)
            
            val matchPercent = (score * 100).toInt().coerceIn(10, 99)

            // AURA STAGE 2: Seeded Jitter
            val jitter = kotlin.random.Random(item.id.hashCode().toLong() xor request.seed).nextDouble() * 0.01

            IntelligenceCandidate(
                item = item.copy(selectionReason = "$matchPercent% Match"),
                evidence = evidenceItems,
                rankScore = score.toDouble() + relBonus + jitter,
                primaryRelevanceScore = score,
                secondaryEvidenceScore = 0f,
                provenance = "Discover:${request.sortOption ?: "General"}"
            )
        }.sortedByDescending { it.rankScore }.take(request.limit)
    }

    private fun scoreAndRank(
        fused: List<RetrievalFusion.FusedCandidate>, 
        request: IntelligenceRequest,
        collector: PerformanceCollector
    ): List<IntelligenceCandidate> {
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value

        // AURA SEARCH REPAIR 3.4: Use principled precision threshold from config
        val config = HybridSearchConfig()
        
        // AURA REPAIR: Distinguish threshold by query modality (Bug B Fix)
        val isTextSearch = request.query != null && request.query.isNotBlank()
        val precisionThreshold = if (isTextSearch && request.queryVectors == null && request.visualVector != null) {
            // Text-only mode (even if CLIP vector resolved, it's text-derived)
            config.minTextSemanticSimilarity
        } else {
            config.minSemanticSimilarity
        }

        DecisionTraceCollector.logEvent(request.requestId, com.example.ui.models.TraceEventType.SCORING_COMPLETED, "Threshold: $precisionThreshold")

        return fused.mapNotNull { fusedCandidate ->
            val item = collector.timeDatabase { repository.getMediaItemById(fusedCandidate.mediaId) } ?: return@mapNotNull null
            if (!isItemVisibleInLibrary(item)) return@mapNotNull null
            
            val keywordScore = fusedCandidate.channelScores[SearchChannel.KEYWORD] ?: 0f
            
            // Precision Gate: Items must exceed precision threshold in at least one neural channel 
            // OR be authoritative lexical matches (exact filename) 
            // OR have strong substring keyword matches (Bug B Fix).
            val maxNeuralSimilarity = fusedCandidate.channelScores.filter { (channel, _) ->
                channel == SearchChannel.SEMANTIC_CONTENT || channel == SearchChannel.SEMANTIC_VISUAL
            }.values.maxOrNull() ?: 0f

            val passesNeuralGate = maxNeuralSimilarity >= precisionThreshold
            val passesLexicalGate = fusedCandidate.isAuthoritative || (isTextSearch && keywordScore >= 70f)

            if (!passesNeuralGate && !passesLexicalGate) {
                return@mapNotNull null
            }

            val evidence = mutableListOf<EvidenceItem>()
            
            // a. Retrieval Evidence
            fusedCandidate.channelRanks.forEach { (channel, _) ->
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
                    provenance = channel.name
                ))
            }
            
            // b. Personalization Evidence
            val personalScore = scorePersonalization(item, tasteDNA)
            evidence.add(EvidenceItem(
                type = EvidenceType.TASTE_DNA_ALIGNMENT,
                score = personalScore,
                confidence = 0.9f,
                status = EvidenceStatus.INFERRED,
                provenance = "TasteDNA"
            ))
            
            // c. Exploration Evidence
            val explorationEvidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators)
            evidence.add(EvidenceItem(
                type = EvidenceType.EXPLORATION_VALUE,
                score = explorationEvidence.explorationScore,
                confidence = 0.7f,
                status = EvidenceStatus.INFERRED,
                provenance = "ExplorationEngine"
            ))

            // d. Pairwise Preference Evidence
            val wins = repository.getPairwiseWins()[item.id] ?: 0
            val losses = repository.getPairwiseLosses()[item.id] ?: 0
            val pairwiseImpact = (wins - losses).toFloat() * 0.1f
            if (wins > 0 || losses > 0) {
                evidence.add(EvidenceItem(
                    type = EvidenceType.PAIRWISE_PREFERENCE,
                    score = pairwiseImpact,
                    confidence = 1.0f, // Historical data is KNOWN
                    status = EvidenceStatus.KNOWN,
                    provenance = "InteractionMemory"
                ))
            }

            // Update 9: Relationship Evidence
            val relBonus = scoreRelationships(item, request.relatedMediaIds, evidence)

            val rankScore = if (request.useLegacyRanking) {
                fusedCandidate.rrfScore
            } else {
                (fusedCandidate.rrfScore * 0.5) + (personalScore * 0.3) + (explorationEvidence.exploitationScore * 0.2) + pairwiseImpact + relBonus
            }
            
            IntelligenceCandidate(
                item = item,
                evidence = evidence,
                rankScore = rankScore,
                primaryRelevanceScore = fusedCandidate.rrfScore.toFloat(),
                secondaryEvidenceScore = if (request.useLegacyRanking) 0f else personalScore,
                provenance = buildProvenance(evidence, rankScore)
            )
        }.sortedWith(
            compareByDescending<IntelligenceCandidate> { it.rankScore }
                .thenByDescending { it.primaryRelevanceScore }
                .thenByDescending { it.secondaryEvidenceScore }
                .thenBy { it.item.id }
        )
    }

    private fun seal(candidates: List<IntelligenceCandidate>, requestId: String): List<IntelligenceCandidate> {
        // Final Visibility Gate (Constraint: Defense-in-Depth)
        val sealed = candidates.filter { 
            val visible = isItemVisibleInLibrary(it.item)
            if (!visible) {
                DecisionTraceCollector.logEvent(
                    requestId, 
                    com.example.ui.models.TraceEventType.ELIGIBILITY_CHECK, 
                    detail = "Dropped at Seal: ${it.item.id}",
                    metadata = mapOf("itemId" to it.item.id, "eligible" to "false", "stage" to "seal")
                )
            }
            visible
        }
        return sealed
    }

    private fun scoreRelationships(
        item: MediaItem,
        relatedIds: List<String>,
        evidence: MutableList<EvidenceItem>
    ): Double {
        if (relatedIds.isEmpty()) return 0.0
        
        var relationshipBonus = 0.0
        val styleProfile = repository.signatureStyleProfile.value
        
        relatedIds.forEach { relatedId ->
            val relatedItem = repository.getMediaItemById(relatedId) ?: return@forEach
            
            // 1. Semantic Relationship (Shared Style Anchors)
            styleProfile.activeStyles.forEach { style ->
                val itemAffinity = SignatureStyleProvider.calculateMediaAffinity(item, style.anchor)
                val relatedAffinity = SignatureStyleProvider.calculateMediaAffinity(relatedItem, style.anchor)
                
                if (itemAffinity > 0.8 && relatedAffinity > 0.8) {
                    relationshipBonus += 0.05
                    evidence.add(EvidenceItem(
                        type = EvidenceType.RELATIONSHIP_MATCH,
                        score = 0.05f,
                        confidence = 0.9f,
                        status = EvidenceStatus.INFERRED,
                        provenance = "Shared Style: ${style.anchor.displayName}"
                    ))
                }
            }

            // 2. Interaction Relationship (Pairwise Continuity)
            // If the user previously preferred THIS item over the related item, it's a strong signal for this context.
            // Placeholder for real pairwise outcome lookup by pair.
            // For now, use relative Elo/Wins if specific outcome is not indexed.
            
            // 3. Temporal/Metadata Relationship
            if (item.genre == relatedItem.genre && item.genre != "Media") {
                relationshipBonus += 0.02
                evidence.add(EvidenceItem(
                    type = EvidenceType.RELATIONSHIP_MATCH,
                    score = 0.02f,
                    confidence = 1.0f,
                    status = EvidenceStatus.KNOWN,
                    provenance = "Shared Genre: ${item.genre}"
                ))
            }
        }
        
        return relationshipBonus.coerceAtMost(0.3) // Absolute ceiling for relationship boost
    }

    private fun buildProvenance(evidence: List<EvidenceItem>, finalScore: Double): String {
        val topEvidence = evidence.sortedByDescending { Math.abs(it.score) }.take(3)
        return "Score ${"%.3f".format(finalScore)} via " + topEvidence.joinToString(" + ") { 
            "${it.type.name}(${"%.2f".format(it.score)})" 
        }
    }

    private fun isItemVisibleInLibrary(item: MediaItem): Boolean {
        return repository.isItemVisibleInLibrary(item)
    }

    private fun matchesFilterType(item: MediaItem, filterType: String): Boolean {
        return when (filterType.uppercase()) {
            "PHOTO" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE")
            "VIDEO" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE")
            else -> true
        }
    }

    internal fun scorePersonalization(item: MediaItem, tasteDNA: TasteDNA): Float {
        val traits = PersonalizationTraitMapper.getTraitAdjustments(item.moodTags)
        if (traits.isEmpty()) return 0.5f
        
        var sumAlignment = 0.0
        traits.forEach { (dim, presence) ->
            val itemTraitValue = (presence + 1.0) / 2.0
            val userPref = getDimensionValue(tasteDNA, dim)
            val alignment = 1.0 - abs(userPref - itemTraitValue)
            sumAlignment += alignment
        }
        return (sumAlignment / traits.size).toFloat()
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
