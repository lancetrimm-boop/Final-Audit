package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.compatibility.AuraMediaTranscoder
import com.example.data.*
import com.example.data.db.ConversionJobEntity
import com.example.data.db.PlaybackErrorLogEntity
import com.example.data.intelligence.AuraConversionAdvisor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PlaybackDiagnosticsViewModel(
    private val repository: PlaybackErrorLogRepository,
    private val queueRepository: ConversionQueueRepository? = null,
    private val mediaRepository: MediaRepository? = null
) : ViewModel() {

    // Conversion state for the active single-file prototype
    private val _conversionStage = mutableStateOf(ConversionStage.IDLE)
    val conversionStage: State<ConversionStage> = _conversionStage

    private val _conversionProgress = mutableStateOf(0)
    val conversionProgress: State<Int> = _conversionProgress

    private val _lastResult = mutableStateOf<SingleFileConversionResult?>(null)
    val lastResult: State<SingleFileConversionResult?> = _lastResult

    // Selection state for batch conversion candidates
    private val _selectedCandidateIds = mutableStateOf(setOf<String>())
    val selectedCandidateIds: State<Set<String>> = _selectedCandidateIds

    val errorLogs: StateFlow<List<PlaybackErrorLogEntity>> = repository.observeRecentErrors()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Observe the persistent conversion queue.
     */
    val conversionQueue: StateFlow<List<ConversionJobEntity>> = queueRepository?.observeAllJobs()
        ?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: MutableStateFlow(emptyList())

    /**
     * Derived summary of conversion eligibility across all recorded errors and conversion queue.
     */
    val eligibilitySummary: StateFlow<ConversionEligibilitySummary> = combine(errorLogs, conversionQueue) { logs, queue ->
        analyzeEligibility(logs, queue)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversionEligibilitySummary(0, 0, 0, 0, 0, emptyList())
    )

    private fun analyzeEligibility(
        logs: List<PlaybackErrorLogEntity>,
        queue: List<ConversionJobEntity>
    ): ConversionEligibilitySummary {
        // Collect media IDs or URIs that have active or completed conversions
        val remediatedMediaIds = queue.filter { job ->
            job.status == ConversionJobStatus.COMPLETED.name ||
            job.status == ConversionJobStatus.READY_FOR_ORIGINAL_CLEANUP.name ||
            job.status == ConversionJobStatus.CLEANUP_COMPLETED.name ||
            job.status == ConversionJobStatus.REPLACING.name
        }.flatMap { job ->
            listOfNotNull(job.mediaId, job.sourceUri, job.finalMediaUri)
        }.toSet()

        // Group errors by mediaItemId (falling back to mediaUri if ID is null)
        val groups = logs.groupBy { it.mediaItemId ?: it.mediaUri ?: "unknown" }
        
        val candidates = groups.mapNotNull { (id, entries) ->
            if (id == "unknown") return@mapNotNull null
            
            // Take the most recent entry for detailed analysis
            val latestEntry = entries.first()
            val uri = latestEntry.mediaUri ?: return@mapNotNull null
            
            val isRemediated = remediatedMediaIds.contains(id) || remediatedMediaIds.contains(uri)
            val recommendation = AuraConversionAdvisor.createRecommendation(latestEntry)
            
            ConversionCandidate(
                mediaId = id,
                sourceUri = Uri.parse(uri),
                fileName = latestEntry.fileName ?: "Unknown",
                mediaTitle = latestEntry.mediaTitle,
                recommendation = recommendation,
                failureCount = entries.sumOf { it.occurrenceCount },
                lastFailureTimestamp = entries.maxOf { it.lastOccurrenceTimestamp },
                isRemediated = isRemediated
            )
        }

        val activeCandidates = candidates.filter { !it.isRemediated }

        return ConversionEligibilitySummary(
            totalErrors = logs.sumOf { it.occurrenceCount },
            uniqueFiles = activeCandidates.size,
            convertibleCount = activeCandidates.count { it.recommendation.eligibility == ConversionEligibility.CONVERTIBLE },
            notRecommendedCount = activeCandidates.count { it.recommendation.eligibility == ConversionEligibility.NOT_RECOMMENDED },
            unavailableCount = activeCandidates.count { it.recommendation.eligibility == ConversionEligibility.UNAVAILABLE },
            candidates = candidates.sortedByDescending { it.lastFailureTimestamp }
        )
    }

    fun toggleCandidateSelection(mediaId: String) {
        val current = _selectedCandidateIds.value
        _selectedCandidateIds.value = if (current.contains(mediaId)) {
            current - mediaId
        } else {
            current + mediaId
        }
    }

    fun selectAllEligible() {
        val convertible = eligibilitySummary.value.candidates
            .filter { it.recommendation.eligibility == ConversionEligibility.CONVERTIBLE }
            .map { it.mediaId }
            .toSet()
        _selectedCandidateIds.value = convertible
    }

    fun clearSelection() {
        _selectedCandidateIds.value = emptySet()
    }

    fun startBatchConversion() {
        val candidates = eligibilitySummary.value.candidates
            .filter { _selectedCandidateIds.value.contains(it.mediaId) }
        
        if (candidates.isNotEmpty()) {
            viewModelScope.launch {
                queueRepository?.enqueueConversions(candidates)
                _selectedCandidateIds.value = emptySet()
            }
        }
    }

    fun cancelJob(jobId: Long) {
        viewModelScope.launch {
            queueRepository?.cancelJob(jobId)
        }
    }

    fun retryJob(jobId: Long) {
        viewModelScope.launch {
            queueRepository?.retryJob(jobId)
        }
    }

    fun clearCompletedJobs() {
        viewModelScope.launch {
            queueRepository?.clearCompleted()
        }
    }

    fun replaceOriginal(context: Context, jobId: Long) {
        viewModelScope.launch {
            queueRepository?.replaceOriginal(context, jobId)
        }
    }

    fun cleanupOriginalNow(jobId: Long) {
        viewModelScope.launch {
            queueRepository?.cleanupOriginalNow(jobId)
        }
    }

    /**
     * Attempts to perform the cleanup directly from the UI context.
     * This allows handling of SecurityExceptions that require user interaction.
     */
    suspend fun performDirectCleanup(context: Context, jobId: Long): Result<Unit> {
        return queueRepository?.performDirectCleanup(context, jobId) 
            ?: Result.failure(Exception("Queue repository not available"))
    }

    fun startConversion(context: Context, error: PlaybackErrorLogEntity) {
        if (_conversionStage.value != ConversionStage.IDLE && _conversionStage.value != ConversionStage.COMPLETE) {
            android.util.Log.d("AuraConversion", "Conversion already active in stage ${_conversionStage.value}. Ignoring duplicate request.")
            return
        }

        val uriStr = error.mediaUri ?: return
        val uri = Uri.parse(uriStr)
        val mediaId = error.mediaItemId ?: ""
        
        android.util.Log.d("AuraConversion", "Convert started for id=$mediaId, uri=$uriStr")
        _lastResult.value = null
        _conversionStage.value = ConversionStage.PREPARING
        _conversionProgress.value = 0
        
        viewModelScope.launch {
            val result = AuraMediaTranscoder.transcodeAndValidate(context, uri) { stage, progress ->
                _conversionStage.value = stage
                _conversionProgress.value = progress
                android.util.Log.d("AuraConversion", "Convert progress for id=$mediaId stage=$stage progress=$progress%")
            }
            _lastResult.value = result
            if (result.status == ConversionStatus.CONVERTED && !result.outputPath.isNullOrBlank()) {
                android.util.Log.d("AuraConversion", "Convert success for id=$mediaId output=${result.outputPath}")
                mediaRepository?.updateConvertedMedia(mediaId, uriStr, result.outputPath)
            } else {
                android.util.Log.e("AuraConversion", "Convert failed for id=$mediaId error=${result.errorMessage}")
            }
        }
    }

    fun resetConversion() {
        _conversionStage.value = ConversionStage.IDLE
        _conversionProgress.value = 0
        _lastResult.value = null
    }

    private val _isAutoCleanupEnabled = mutableStateOf(true)
    val isAutoCleanupEnabled: State<Boolean> = _isAutoCleanupEnabled

    init {
        loadAutoCleanupPref()
    }

    private fun loadAutoCleanupPref() {
        viewModelScope.launch {
            _isAutoCleanupEnabled.value = queueRepository?.isAutoCleanupEnabled() ?: true
        }
    }

    fun toggleAutoCleanup() {
        val newValue = !_isAutoCleanupEnabled.value
        _isAutoCleanupEnabled.value = newValue
        viewModelScope.launch {
            queueRepository?.setAutoCleanupEnabled(newValue)
        }
    }

    fun deleteError(errorId: Long) {
        viewModelScope.launch {
            repository.deleteError(errorId)
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearAllLogs()
        }
    }
}
