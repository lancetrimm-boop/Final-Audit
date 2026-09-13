package com.example.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AuraMomentsEngine
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.MomentsMode
import com.example.data.media.MomentExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SlideshowViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SlideshowUiState>(SlideshowUiState.Loading)
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    private val _exportState = MutableStateFlow<MomentExporter.ExportState>(MomentExporter.ExportState.Idle)
    val exportState: StateFlow<MomentExporter.ExportState> = _exportState.asStateFlow()

    private var momentExporter: MomentExporter? = null

    fun generateSlideshow(mode: MomentsMode) {
        viewModelScope.launch {
            _uiState.value = SlideshowUiState.Loading
            val items = try {
                AuraMomentsEngine.generateIntelligentSlideshow(repository, mode)
            } catch (e: Exception) {
                emptyList()
            }
            
            if (items.isEmpty()) {
                _uiState.value = SlideshowUiState.Error("No media found for your Visual Style. Continue importing to build your profile.")
            } else {
                _uiState.value = SlideshowUiState.Ready(items)
            }
        }
    }

    fun exportMoment(context: Context) {
        val currentState = _uiState.value
        if (currentState !is SlideshowUiState.Ready) return

        viewModelScope.launch {
            if (momentExporter == null) {
                val entitlementRepo = repository.entitlementRepository 
                    ?: throw IllegalStateException("EntitlementRepository not initialized")
                momentExporter = MomentExporter(context.applicationContext, entitlementRepo)
            }

            _exportState.value = MomentExporter.ExportState.Exporting(0)
            val result = momentExporter!!.exportMoment(currentState.items) { progress ->
                _exportState.value = MomentExporter.ExportState.Exporting(progress)
            }
            _exportState.value = result
        }
    }

    fun clearExportState() {
        _exportState.value = MomentExporter.ExportState.Idle
    }

    sealed class SlideshowUiState {
        object Loading : SlideshowUiState()
        data class Ready(val items: List<MediaItem>) : SlideshowUiState()
        data class Error(val message: String) : SlideshowUiState()
    }
}
