package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AuraMomentsEngine
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.MomentsMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SlideshowViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SlideshowUiState>(SlideshowUiState.Loading)
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

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

    sealed class SlideshowUiState {
        object Loading : SlideshowUiState()
        data class Ready(val items: List<MediaItem>) : SlideshowUiState()
        data class Error(val message: String) : SlideshowUiState()
    }
}
