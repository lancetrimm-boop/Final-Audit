package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.data.cleanup.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CleanupReviewUiState(
    val recommendations: List<CleanupRecommendation> = emptyList(),
    val filteredRecommendations: List<CleanupRecommendation> = emptyList(),
    val mediaItems: Map<String, MediaItem> = emptyMap(),
    val selectedCategory: CleanupCategory = CleanupCategory.FORGOTTEN,
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val isLocked: Boolean = false,
    val isDeleting: Boolean = false,
    val requiresInternalConfirmation: Boolean = false,
    val storageRecoveryEstimate: Long = 0L,
    val categoryStats: Map<CleanupCategory, CategoryStat> = emptyMap(),
    val currentSort: ReviewSort = ReviewSort.LOWEST_KEEP_SCORE
)

data class CategoryStat(
    val count: Int,
    val storageBytes: Long,
    val averageKeepScore: Float
)

enum class ReviewSort {
    LOWEST_KEEP_SCORE,
    HIGHEST_CONFIDENCE,
    LARGEST_STORAGE,
    MOST_EXPOSED
}

class CleanupReviewViewModel(
    private val repository: MediaRepository,
    private val entitlementRepository: com.example.data.entitlement.EntitlementRepository,
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default
) : ViewModel() {

    val deleteManager = repository.safeDeleteManager

    private val _uiState = MutableStateFlow(CleanupReviewUiState())
    val uiState: StateFlow<CleanupReviewUiState> = _uiState.asStateFlow()

    init {
        loadRecommendations()
        
        viewModelScope.launch {
            deleteManager.deletionState.collect { state ->
                when (state) {
                    DeletionState.CONFIRMED, DeletionState.CANCELLED, DeletionState.FAILED -> {
                        _uiState.update { it.copy(isDeleting = false, requiresInternalConfirmation = false) }
                        if (state == DeletionState.CONFIRMED) {
                            loadRecommendations() // Refresh
                        }
                    }
                    DeletionState.PENDING -> {
                        _uiState.update { it.copy(isDeleting = true, requiresInternalConfirmation = false) }
                    }
                    DeletionState.AURA_CONFIRMATION_REQUIRED -> {
                        _uiState.update { it.copy(isDeleting = false, requiresInternalConfirmation = true) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun loadRecommendations() {
        if (!entitlementRepository.isFeatureAvailable(com.example.data.entitlement.ProFeature.CLEANUP_AUTOMATION)) {
            _uiState.update { it.copy(isLocked = true, isLoading = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLocked = false) }
            
            val allItems = repository.mediaItems.value
            val tasteDNA = repository.tasteDNA.value
            val stats = repository.intelligenceStats.value
            
            // Perform heavy calculation in background
            kotlinx.coroutines.withContext(backgroundDispatcher) {
                // 1. Generate Keep Scores
                val allIds = allItems.map { it.id }
                val skipCounts = repository.getSkipCounts(allIds)
                val hashFrequenciesList = repository.getContentHashFrequencies()
                val hashFrequenciesMap = hashFrequenciesList.associate { it.contentHash to it.count }

                val keepScoreResults = allItems.map { item ->
                    val evidence = ExplorationEngine.calculateEvidence(item, tasteDNA, stats)
                    val estimatedWatchDuration = (item.progress * item.durationMs) / 1000f
                    
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

                // 2. Generate Recommendations
                val metadataMap = allItems.associate { item ->
                    item.id to CleanupItemMetadata(
                        mediaId = item.id,
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

                // 3. Calculate Category Stats
                val statsMap = CleanupCategory.entries.associateWith { cat ->
                    val catRecs = recommendations.filter { it.category == cat }
                    CategoryStat(
                        count = catRecs.size,
                        storageBytes = catRecs.sumOf { it.storageSize },
                        averageKeepScore = if (catRecs.isNotEmpty()) catRecs.map { it.keepScore }.average().toFloat() else 0f
                    )
                }

                _uiState.update { state ->
                    val newState = state.copy(
                        recommendations = recommendations,
                        mediaItems = allItems.associateBy { it.id },
                        categoryStats = statsMap,
                        isLoading = false,
                        selectedIds = recommendations.map { it.mediaId }.toSet()
                    )
                    applyFiltersAndSort(newState)
                }
            }
        }
    }

    fun selectCategory(category: CleanupCategory) {
        _uiState.update { state ->
            val newState = state.copy(selectedCategory = category, selectedIds = emptySet())
            applyFiltersAndSort(newState)
        }
    }

    fun toggleSelection(mediaId: String) {
        _uiState.update { state ->
            val newSelection = if (state.selectedIds.contains(mediaId)) {
                state.selectedIds - mediaId
            } else {
                state.selectedIds + mediaId
            }
            state.copy(selectedIds = newSelection)
        }
    }

    fun selectAllInCategory() {
        _uiState.update { state ->
            val allIds = state.filteredRecommendations.map { it.mediaId }.toSet()
            state.copy(selectedIds = allIds)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    fun updateSort(sort: ReviewSort) {
        _uiState.update { state ->
            val newState = state.copy(currentSort = sort)
            applyFiltersAndSort(newState)
        }
    }

    fun requestDeleteSelected(
        context: android.content.Context,
        launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>
    ) {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        val items = selectedIds.mapNotNull { repository.getMediaItemById(it) }
        val recs = _uiState.value.recommendations.filter { selectedIds.contains(it.mediaId) }

        deleteManager.requestDeletion(context, items, recs, launcher)
    }

    fun keepSelected() {
        val selectedIds = _uiState.value.selectedIds
        if (selectedIds.isEmpty()) return

        val items = selectedIds.mapNotNull { repository.getMediaItemById(it) }
        val recs = _uiState.value.recommendations.filter { selectedIds.contains(it.mediaId) }

        items.forEach { item ->
            recs.find { it.mediaId == item.id }?.let { rec ->
                deleteManager.markAsKept(item, rec)
            }
        }
        
        clearSelection()
        loadRecommendations()
    }

    fun keepItem(mediaId: String) {
        val item = repository.getMediaItemById(mediaId) ?: return
        val rec = _uiState.value.recommendations.find { it.mediaId == mediaId } ?: return
        deleteManager.markAsKept(item, rec)
        loadRecommendations()
    }

    fun confirmInternalDeletion() {
        deleteManager.confirmInternalDeletion()
    }

    fun cancelDeletion() {
        deleteManager.cancelDeletion()
    }

    private fun applyFiltersAndSort(state: CleanupReviewUiState): CleanupReviewUiState {
        val filtered = state.recommendations.filter { it.category == state.selectedCategory }
        
        val sorted = when (state.currentSort) {
            ReviewSort.LOWEST_KEEP_SCORE -> filtered.sortedBy { it.keepScore }
            ReviewSort.HIGHEST_CONFIDENCE -> filtered.sortedByDescending { it.confidenceScore }
            ReviewSort.LARGEST_STORAGE -> filtered.sortedByDescending { it.storageSize }
            ReviewSort.MOST_EXPOSED -> filtered.sortedByDescending { it.exposureCount }
        }
        
        return state.copy(
            filteredRecommendations = sorted,
            storageRecoveryEstimate = sorted.sumOf { it.storageSize }
        )
    }
}
