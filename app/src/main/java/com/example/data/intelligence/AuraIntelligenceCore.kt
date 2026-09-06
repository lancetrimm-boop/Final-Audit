package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import com.example.compatibility.AuraMediaCompatibilityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reusable orchestration layer for Aura's intelligence capabilities.
 * Unifies Retrieval, Fusion, Evidence Generation, and Ranking.
 */
class AuraIntelligenceCore(
    private val repository: MediaRepository,
    private val retrievalRouter: RetrievalRouter,
    private val reranker: MultimodalReranker = VideoIntelligenceReranker()
) {
    /**
     * Processes a generalized intelligence request.
     */
    suspend fun processRequest(request: IntelligenceRequest): IntelligenceResponse = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        val candidates = when(request.mode) {
            IntelligenceMode.SEARCH -> handleSearch(request)
            IntelligenceMode.SORT -> handleSort(request)
            IntelligenceMode.SIMILAR -> handleSimilar(request)
            IntelligenceMode.DISCOVER -> handleDiscover(request)
        }

        val latency = System.currentTimeMillis() - startTime
        
        IntelligenceResponse(
            requestId = request.requestId,
            mode = request.mode,
            candidates = candidates,
            latencyMs = latency
        )
    }

    private suspend fun handleSearch(request: IntelligenceRequest): List<IntelligenceCandidate> {
        val channelResults = retrievalRouter.retrieve(request)
        val fusionConfig = HybridSearchConfig(topK = request.limit * 2)
        val fused = RetrievalFusion.fuse(channelResults, fusionConfig)
        
        // Post-retrieval Reranking (Stage 8 Video Intelligence Integration)
        val queryVector = request.visualVector
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
        val frameVectors = repository.semanticRepresentationRepository?.getFramesForBatch(mediaIds)?.groupBy { it.mediaId } ?: emptyMap()

        val reranked = reranker.rerank(
            candidates = topForRerank,
            queryVector = queryVector,
            frameVectors = frameVectors
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

        return scoreAndRank(rerankedFused, request)
    }

    private fun handleSort(request: IntelligenceRequest): List<IntelligenceCandidate> {
        val allItems = repository.mediaItems.value
        val now = System.currentTimeMillis()
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value
        
        val items = allItems.filter { 
            matchesFilterType(it, request.filterType) && isItemVisibleInLibrary(it) 
        }

        val scored = when (request.sortOption) {
            "PERSONALIZED" -> {
                val systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats)
                val strategy = DiscoveryPolicyManager.resolveStrategy(
                    policy = request.policy ?: DiscoveryPolicy(),
                    intent = request.intent ?: UserIntent(),
                    objective = RecommendationObjective.LIBRARY_INTELLIGENT_DISCOVERY,
                    systemState = systemState,
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                )

                items.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    val personalScore = scorePersonalization(item, tasteDNA)
                    val evidenceItems = listOf(
                        EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.exploitationScore, 0.9f, EvidenceStatus.INFERRED, "ExplorationEngine"),
                        EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, personalScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA")
                    )

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score.toDouble(),
                        primaryRelevanceScore = score,
                        secondaryEvidenceScore = personalScore,
                        provenance = "Personalized Sort"
                    )
                }
            }
            "DISCOVER" -> {
                val strategy = DiscoveryPolicyManager.resolveStrategy(
                    policy = request.policy ?: DiscoveryPolicy(),
                    intent = request.intent ?: UserIntent(),
                    objective = RecommendationObjective.GENERAL_DISCOVERY,
                    systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats),
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                ).copy(exploitationWeight = 0.2f, explorationWeight = 0.8f)

                items.filter { it.viewCount == 0 || it.exposureCount < 3 }.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine")),
                        rankScore = score.toDouble(),
                        primaryRelevanceScore = score,
                        secondaryEvidenceScore = 0f,
                        provenance = "Discover Sort"
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

    private suspend fun handleSimilar(request: IntelligenceRequest): List<IntelligenceCandidate> {
        val refId = request.referenceItemId ?: return emptyList()
        val refItem = repository.getMediaItemById(refId) ?: return emptyList()
        
        // Use existing similarity engine but wrap results (useCore = false to prevent recursion)
        val legacySimilar = repository.getSimilarMedia(refItem, request.requestId, useCore = false)
        
        return legacySimilar.mapNotNull { item ->
            if (!isItemVisibleInLibrary(item)) return@mapNotNull null

            // Re-score item using Core ranking for consistent ordering
            val personalScore = scorePersonalization(item, request.tasteDNA ?: repository.tasteDNA.value)
            val exploration = ExplorationEngine.calculateEvidence(item, request.tasteDNA ?: repository.tasteDNA.value)
            
            IntelligenceCandidate(
                item = item,
                evidence = emptyList(),
                rankScore = if (request.useLegacyRanking) 1.0 else (1.0 * 0.5) + (personalScore * 0.3) + (exploration.exploitationScore * 0.2),
                primaryRelevanceScore = 1.0f,
                secondaryEvidenceScore = personalScore
            )
        }.sortedWith(
            compareByDescending<IntelligenceCandidate> { it.rankScore }
                .thenByDescending { it.primaryRelevanceScore }
                .thenByDescending { it.secondaryEvidenceScore }
                .thenBy { it.item.id }
        ).take(request.limit)
    }

    private fun handleDiscover(request: IntelligenceRequest): List<IntelligenceCandidate> {
        // Enforce visibility gate
        val allItems = repository.mediaItems.value
        val itemsOnly = allItems.filter { 
            it.itemCount == null && 
            AuraMediaCompatibilityEngine.isEligibleForImport(it.compatibilityStatus) &&
            !it.isDeleted &&
            isItemVisibleInLibrary(it)
        }
        
        // Return structured candidates for Discover categories (Phase 4 integration)
        // For now returning empty as Discover UI still uses RecommendationEngine directly
        return emptyList()
    }

    private fun scoreAndRank(
        fused: List<RetrievalFusion.FusedCandidate>, 
        request: IntelligenceRequest
    ): List<IntelligenceCandidate> {
        val tasteDNA = request.tasteDNA ?: repository.tasteDNA.value
        val stats = request.stats ?: repository.intelligenceStats.value
        val creators = request.creatorProfiles ?: repository.creatorProfiles.value

        return fused.mapNotNull { fusedCandidate ->
            val item = repository.getMediaItemById(fusedCandidate.mediaId) ?: return@mapNotNull null
            if (!isItemVisibleInLibrary(item)) return@mapNotNull null
            
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

            val rankScore = if (request.useLegacyRanking) {
                fusedCandidate.rrfScore
            } else {
                (fusedCandidate.rrfScore * 0.5) + (personalScore * 0.3) + (explorationEvidence.exploitationScore * 0.2) + pairwiseImpact
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

    private fun buildProvenance(evidence: List<EvidenceItem>, finalScore: Double): String {
        val topEvidence = evidence.sortedByDescending { Math.abs(it.score) }.take(3)
        return "Score ${"%.3f".format(finalScore)} via " + topEvidence.joinToString(" + ") { 
            "${it.type.name}(${"%.2f".format(it.score)})" 
        }
    }

    private fun isItemVisibleInLibrary(item: MediaItem): Boolean {
        val visibleStatuses = listOf(
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE,
            CompatibilityStatus.PLAYABLE_AFTER_CONVERSION,
            CompatibilityStatus.THUMBNAIL_FAILED,
            CompatibilityStatus.NEEDS_TRANSCODE,
            CompatibilityStatus.ANALYSIS_PENDING,
            CompatibilityStatus.UNTESTED
        )
        return !item.isDeleted && item.compatibilityStatus in visibleStatuses
    }

    private fun matchesFilterType(item: MediaItem, filterType: String): Boolean {
        return when (filterType.uppercase()) {
            "PHOTO" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE")
            "VIDEO" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE")
            else -> true
        }
    }

    private fun scorePersonalization(item: MediaItem, tasteDNA: TasteDNA): Float {
        val traits = PersonalizationTraitMapper.getTraitAdjustments(item.moodTags)
        if (traits.isEmpty()) return 0.5f
        
        var sumAlignment = 0.0
        traits.forEach { (dim, presence) ->
            val itemTraitValue = (presence + 1.0) / 2.0
            val userPref = getDimensionValue(tasteDNA, dim)
            val alignment = 1.0 - Math.abs(userPref - itemTraitValue)
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
