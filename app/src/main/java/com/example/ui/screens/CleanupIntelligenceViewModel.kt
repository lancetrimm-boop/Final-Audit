package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.data.cleanup.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class CleanupIntelligenceUiState(
    val totalRecommendations: Int = 0,
    val deleteRecommendationsCount: Int = 0,
    val unplayableCount: Int = 0,
    val spaceHogCount: Int = 0,
    val redundantCount: Int = 0,
    val forgottenCount: Int = 0,
    val neverConnectedCount: Int = 0,
    val potentialStorageRecovery: Long = 0L,
    val averageConfidence: Float = 0f,
    val averageKeepScore: Float = 0f,
    val lowestScoreItems: List<CleanupRecommendation> = emptyList(),
    val highestScoreItems: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLocked: Boolean = false,
    val currentSort: CleanupSort = CleanupSort.LOWEST_KEEP_SCORE
)

enum class CleanupSort {
    LOWEST_KEEP_SCORE,
    HIGHEST_CONFIDENCE,
    LARGEST_STORAGE_IMPACT,
    CATEGORY
}

class CleanupIntelligenceViewModel(
    private val repository: MediaRepository,
    private val entitlementRepository: com.example.data.entitlement.EntitlementRepository,
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanupIntelligenceUiState())
    val uiState: StateFlow<CleanupIntelligenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val dbStateFlow = repository.databaseState ?: kotlinx.coroutines.flow.flowOf(com.example.data.DatabaseState.READY)
            val mediaItemsFlow = repository.mediaItems ?: kotlinx.coroutines.flow.flowOf(emptyList())
            val proStateFlow = entitlementRepository.proState ?: kotlinx.coroutines.flow.flowOf(com.example.data.entitlement.ProState.Free)

            kotlinx.coroutines.flow.combine(
                dbStateFlow,
                mediaItemsFlow,
                proStateFlow
            ) { dbState, items, proState ->
                Triple(dbState, items, proState)
            }.collect { (dbState, _, _) ->
                if (dbState != com.example.data.DatabaseState.READY) {
                    _uiState.update { it.copy(isLoading = true, isLocked = false) }
                } else {
                    refreshAnalysis()
                }
            }
        }
    }

    fun refreshAnalysis() {
        if (!entitlementRepository.isFeatureAvailable(com.example.data.entitlement.ProFeature.CLEANUP_AUTOMATION)) {
            _uiState.update { it.copy(isLocked = true, isLoading = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLocked = false) }
            
            // 1. Fetch all media items
            val allItems = repository.mediaItems.value
            if (allItems.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            // Perform heavy processing in background
            withContext(backgroundDispatcher) {
                // 2. Gather signals and calculate Keep Scores
                val tasteDNA = repository.tasteDNA?.value ?: com.example.data.TasteDNA()
                val stats = repository.intelligenceStats?.value ?: com.example.data.IntelligenceStats()
                
                val allIds = allItems.map { it.id }
                val skipCounts = repository.getSkipCounts(allIds)
                val hashFrequenciesList = repository.getContentHashFrequencies()
                val hashFrequenciesMap = hashFrequenciesList.associate { it.contentHash to it.count }

                val keepScoreResults = allItems.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats)
                    
                    // Estimate watch duration based on current progress and total duration
                    // Documented: Estimated watched duration (progress * duration)
                    val estimatedWatchDuration = (item.progress * item.durationMs) / 1000f

                    // Rarity: 1.0 / frequency of same content hash
                    val frequency = hashFrequenciesMap[item.contentHash ?: ""] ?: 1
                    val rarityScore = 1.0f / frequency

                    KeepScoreEngine.calculateScore(
                        KeepScoreInput(
                            mediaId = item.id,
                            fileSize = item.sizeBytes,
                            dateAdded = item.dateAdded,
                            exposureCount = item.exposureCount,
                            lastExposedTimestamp = item.lastExposedTimestamp,
                            viewCount = item.viewCount,
                            playCount = item.viewCount, 
                            averageWatchDuration = estimatedWatchDuration,
                            completionPercentage = item.progress,
                            skipCount = skipCounts[item.id] ?: 0,
                            rating = item.rating,
                            isFavorite = item.isFavorite,
                            tasteAlignmentScore = evidence.exploitationScore,
                            rarityScore = rarityScore,
                            contentHash = item.contentHash
                        )
                    )
                }

                // 3. Generate Recommendations
                val metadataMap = allItems.associate { item ->
                    item.id to CleanupItemMetadata(
                        mediaId = item.id,
                        title = item.title,
                        sizeBytes = item.sizeBytes,
                        exposureCount = item.exposureCount,
                        viewCount = item.viewCount,
                        mediaType = item.mediaType,
                        contentHash = item.contentHash,
                        isFavorite = item.isFavorite,
                        width = item.width,
                        height = item.height,
                        durationMs = item.durationMs,
                        dateAdded = item.dateAdded
                    )
                }

                val recommendations = CleanupRecommendationEngine.generateRecommendations(
                    keepScoreResults,
                    metadataMap
                )

                // 4. Calculate Stats
                val recovery = CleanupRecommendationEngine.calculatePotentialRecovery(recommendations)
                val avgConf = if (recommendations.isNotEmpty()) recommendations.map { it.confidenceScore }.average().toFloat() else 0f
                val avgKeep = if (keepScoreResults.isNotEmpty()) keepScoreResults.map { it.keepScore }.average().toFloat() else 0f
                
                val sortedRecs = sortRecommendations(recommendations, _uiState.value.currentSort)
                
                // Protect high-value items list (Top 10 by Keep Score)
                val resultsMap = keepScoreResults.associateBy { it.mediaId }
                val protectedItems = allItems
                    .map { it to (resultsMap[it.id]?.keepScore ?: 0f) }
                    .sortedByDescending { it.second }
                    .take(10)
                    .map { it.first }

                _uiState.update { state ->
                    state.copy(
                        totalRecommendations = recommendations.size,
                        deleteRecommendationsCount = recommendations.count { it.category == CleanupCategory.DELETE_RECOMMENDATIONS },
                        unplayableCount = recommendations.count { it.category == CleanupCategory.UNPLAYABLE_FILES },
                        spaceHogCount = recommendations.count { it.category == CleanupCategory.SPACE_HOGS },
                        redundantCount = recommendations.count { it.category == CleanupCategory.REDUNDANT },
                        forgottenCount = recommendations.count { it.category == CleanupCategory.DELETE_RECOMMENDATIONS },
                        neverConnectedCount = recommendations.count { it.category == CleanupCategory.UNPLAYABLE_FILES },
                        potentialStorageRecovery = recovery,
                        averageConfidence = avgConf,
                        averageKeepScore = avgKeep,
                        lowestScoreItems = sortedRecs.take(20),
                        highestScoreItems = protectedItems,
                        isLoading = false
                    )
                }

                // Structured validation signals (Internal only)
                println("CleanupIntelligence: Generated recommendations: ${recommendations.size}")
                println("CleanupIntelligence: Average confidence: ${(avgConf * 100).toInt()}%")
                println("CleanupIntelligence: Potential recovery: ${recovery / (1024 * 1024)} MB")
                println("CleanupIntelligence: Category distribution:")
                println("  DELETE_RECOMMENDATIONS: ${recommendations.count { it.category == CleanupCategory.DELETE_RECOMMENDATIONS }}")
                println("  UNPLAYABLE_FILES: ${recommendations.count { it.category == CleanupCategory.UNPLAYABLE_FILES }}")
                println("  SPACE_HOGS: ${recommendations.count { it.category == CleanupCategory.SPACE_HOGS }}")
                println("  REDUNDANT: ${recommendations.count { it.category == CleanupCategory.REDUNDANT }}")
            }
        }
    }

    fun updateSort(sort: CleanupSort) {
        _uiState.update { it.copy(currentSort = sort) }
        refreshAnalysis() // Re-sort and update
    }

    private fun sortRecommendations(recs: List<CleanupRecommendation>, sort: CleanupSort): List<CleanupRecommendation> {
        return when (sort) {
            CleanupSort.LOWEST_KEEP_SCORE -> recs.sortedBy { it.keepScore }
            CleanupSort.HIGHEST_CONFIDENCE -> recs.sortedByDescending { it.confidenceScore }
            CleanupSort.LARGEST_STORAGE_IMPACT -> recs.sortedByDescending { it.storageSize }
            CleanupSort.CATEGORY -> recs.sortedBy { it.category.name }
        }
    }
}
