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
    private val retrievalRouter: RetrievalRouter,
    private val reranker: MultimodalReranker = VideoIntelligenceReranker()
) {
    /**
     * Processes a generalized intelligence request.
     */
    suspend fun processRequest(request: IntelligenceRequest): IntelligenceResponse = withContext(Dispatchers.Default) {
        // 0. Cache Lookup
        IntelligenceCache.getResponse(request)?.let { return@withContext it }

        val startTime = System.currentTimeMillis()
        
        val candidates = when(request.mode) {
            IntelligenceMode.SEARCH -> handleSearch(request)
            IntelligenceMode.SORT -> handleSort(request)
            IntelligenceMode.SIMILAR -> handleSimilar(request)
            IntelligenceMode.DISCOVER -> handleDiscover(request)
        }

        val sealedResults = seal(candidates)

        val latency = System.currentTimeMillis() - startTime
        
        val response = IntelligenceResponse(
            requestId = request.requestId,
            mode = request.mode,
            candidates = sealedResults,
            latencyMs = latency
        )

        // 1. Cache Write
        IntelligenceCache.putResponse(request, response)
        
        response
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

        val results = scoreAndRank(rerankedFused, request)
        
        // Update 7: Style Annotation
        val styleProfile = repository.signatureStyleProfile.value
        return results.map { IntelligentPresentationProvider.annotateCandidate(it, styleProfile) }
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

                val recentThreshold = 3600000L // 1 hour
                items.filter { item ->
                    // Standard Personalized Sort filter: Exclude already liked or recently viewed
                    val isLiked = item.isFavorite || item.rating >= 4.0f
                    val isRecent = item.lastViewedTimestamp?.let { now - it < recentThreshold } ?: false
                    !isLiked && !isRecent
                }.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    val personalScore = scorePersonalization(item, tasteDNA)
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.exploitationScore, 0.9f, EvidenceStatus.INFERRED, "ExplorationEngine"))
                    evidenceItems.add(EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, personalScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA"))
                    
                    // Update 9: Relationship Evidence
                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score.toDouble() + relBonus,
                        primaryRelevanceScore = score,
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
                    systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats),
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                ).copy(exploitationWeight = 0.2f, explorationWeight = 0.8f)

                items.filter { it.viewCount == 0 || it.exposureCount < 3 }.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    val evidenceItems = mutableListOf<EvidenceItem>()
                    evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine"))
                    
                    // Update 9: Relationship Evidence
                    val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

                    IntelligenceCandidate(
                        item = item,
                        evidence = evidenceItems,
                        rankScore = score.toDouble() + relBonus,
                        primaryRelevanceScore = score,
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
            "LEAST_INTERACTED" -> {
                val winsMap = repository.getPairwiseWins()
                val lossesMap = repository.getPairwiseLosses()
                items.map { item ->
                    val comparisonCount = (winsMap[item.id] ?: 0) + (lossesMap[item.id] ?: 0)
                    val score = 100.0 / (item.exposureCount + comparisonCount + 1.0)
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.EXPLORATION_VALUE, score.toFloat(), 1.0f, EvidenceStatus.KNOWN, "LeastInteracted")),
                        rankScore = score,
                        primaryRelevanceScore = score.toFloat(),
                        secondaryEvidenceScore = 0f
                    )
                }
            }
            "EXPLORE" -> {
                items.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = (evidence.uncertaintyScore * 5.0) + (evidence.noveltyScore * 5.0)
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.uncertaintyScore, 0.7f, EvidenceStatus.INFERRED, "Uncertainty")),
                        rankScore = score,
                        primaryRelevanceScore = score.toFloat(),
                        secondaryEvidenceScore = 0f
                    )
                }
            }
            "HIDDEN_GEMS" -> {
                val strategy = DiscoveryPolicyManager.resolveStrategy(
                    policy = request.policy ?: DiscoveryPolicy(),
                    intent = request.intent ?: UserIntent(),
                    objective = RecommendationObjective.LIBRARY_INTELLIGENT_DISCOVERY,
                    systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats),
                    tasteDNA = tasteDNA,
                    profile = request.profile ?: repository.preferenceProfile.value
                )
                
                items.filter { item ->
                    item.exposureCount < 5 && item.viewCount < 2 && item.rating == 0f
                }.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
                    
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine")),
                        rankScore = score.toDouble(),
                        primaryRelevanceScore = score,
                        secondaryEvidenceScore = 0f
                    )
                }
            }
            "FAVORITES" -> {
                items.filter { item ->
                    item.isFavorite || item.rating >= 4.0f
                }.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats, creators, now)
                    // Favorites are already high quality, sort by newest added
                    val score = evidence.exploitationScore * 10f + (item.dateAdded.toDouble() / 1e12).toFloat()
                    
                    IntelligenceCandidate(
                        item = item,
                        evidence = listOf(EvidenceItem(EvidenceType.TASTE_DNA_ALIGNMENT, evidence.exploitationScore, 0.9f, EvidenceStatus.INFERRED, "TasteDNA")),
                        rankScore = score.toDouble(),
                        primaryRelevanceScore = evidence.exploitationScore,
                        secondaryEvidenceScore = item.dateAdded.toFloat()
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
        val refItem = repository.getMediaItemById(refId)
        
        // 1. Resolve context and embedding for reference item (Update 9.1 Consolidation)
        val query = request.query ?: refItem?.title
        val visualVector = request.visualVector ?: repository.let { repo ->
            val provider = repo.mobileCLIPProvider
            val semanticRepo = repo.semanticRepresentationRepository
            if (provider != null && semanticRepo != null) {
                 semanticRepo.getSpecificRepresentation(refId, SemanticRepresentationType.VISUAL, provider.descriptor)?.vector
            } else null
        }
        
        // 2. Execute retrieval via Router (Unified Path)
        val similarRequest = request.copy(visualVector = visualVector, query = query)
        val channelResults = retrievalRouter.retrieve(similarRequest)
        
        // 3. Fusion and Ranking
        val fused = RetrievalFusion.fuse(channelResults, HybridSearchConfig(topK = request.limit * 2))
        
        // Exclude the reference item itself
        val filteredFused = fused.filter { it.mediaId != refId }
        
        val results = scoreAndRank(filteredFused, request)
        
        // Update 7: Style Annotation
        val styleProfile = repository.signatureStyleProfile.value
        return results.map { IntelligentPresentationProvider.annotateCandidate(it, styleProfile) }
    }

    private fun handleDiscover(request: IntelligenceRequest): List<IntelligenceCandidate> {
        val allItems = repository.mediaItems.value
        val itemsOnly = allItems.filter { 
            it.itemCount == null && 
            AuraMediaCompatibilityEngine.isEligibleForImport(it.compatibilityStatus) &&
            !it.isDeleted &&
            isItemVisibleInLibrary(it)
        }

        val stats = request.stats ?: repository.intelligenceStats.value
        val dna = request.tasteDNA ?: repository.tasteDNA.value
        val profile = request.profile ?: repository.preferenceProfile.value
        val systemState = ConfidenceEngine.calculateDiscoveryState(allItems, stats)

        // Map contextual intent to RecommendationObjective (Aura Phase 9 Alignment)
        val objective = when (request.contextualIntent) {
            ContextualIntent.DISCOVER_CATEGORY -> RecommendationObjective.GENERAL_DISCOVERY
            ContextualIntent.MOMENTS_FLOW -> RecommendationObjective.GENERAL_DISCOVERY
            else -> RecommendationObjective.GENERAL_DISCOVERY
        }

        val strategy = DiscoveryPolicyManager.resolveStrategy(
            policy = request.policy ?: DiscoveryPolicy(),
            intent = request.intent ?: UserIntent(),
            objective = objective,
            systemState = systemState,
            tasteDNA = dna,
            profile = profile
        )

        return itemsOnly.map { item ->
            val evidence = ExplorationEngine.calculateEvidence(item, dna, stats, request.creatorProfiles ?: repository.creatorProfiles.value)
            val score = ExplorationEngine.calculatePolicyScore(evidence, strategy)
            
            val evidenceItems = mutableListOf<EvidenceItem>()
            evidenceItems.add(EvidenceItem(EvidenceType.EXPLORATION_VALUE, evidence.explorationScore, 0.8f, EvidenceStatus.INFERRED, "ExplorationEngine"))
            
            // Update 9: Relationship Evidence
            val relBonus = scoreRelationships(item, request.relatedMediaIds, evidenceItems)

            IntelligenceCandidate(
                item = item,
                evidence = evidenceItems,
                rankScore = score.toDouble() + relBonus,
                primaryRelevanceScore = score,
                secondaryEvidenceScore = 0f
            )
        }.sortedByDescending { it.rankScore }.take(request.limit)
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

    private fun seal(candidates: List<IntelligenceCandidate>): List<IntelligenceCandidate> {
        // Final Visibility Gate (Constraint: Defense-in-Depth)
        return candidates.filter { isItemVisibleInLibrary(it.item) }
    }

    private fun scoreRelationships(
        item: MediaItem,
        relatedIds: List<String>,
        evidence: MutableList<EvidenceItem>
    ): Double {
        if (relatedIds.isEmpty()) return 0.0
        
        // 1. Semantic Relationship (Shared Style Anchors)
        val styleProfile = repository.signatureStyleProfile.value
        var relationshipBonus = 0.0
        
        relatedIds.forEach { relatedId ->
            val relatedItem = repository.getMediaItemById(relatedId) ?: return@forEach
            
            // If both items align with the same strong style anchor
            styleProfile.activeStyles.forEach { style ->
                val itemAffinity = SignatureStyleProvider.calculateMediaAffinity(item, style.anchor)
                val relatedAffinity = SignatureStyleProvider.calculateMediaAffinity(relatedItem, style.anchor)
                
                if (itemAffinity > 0.8 && relatedAffinity > 0.8) {
                    relationshipBonus += 0.05 // Bounded boost per shared style
                    evidence.add(EvidenceItem(
                        type = EvidenceType.RELATIONSHIP_MATCH,
                        score = 0.05f,
                        confidence = 0.9f,
                        status = EvidenceStatus.INFERRED,
                        provenance = "Shared Style: ${style.anchor.displayName}"
                    ))
                }
            }
        }
        
        return relationshipBonus.coerceAtMost(0.2) // Absolute ceiling for relationship boost
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
