package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MediaRepository
import com.example.data.intelligence.IntelligentSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FavoritesViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _sections = MutableStateFlow<List<IntelligentSection>>(emptyList())
    val sections: StateFlow<List<IntelligentSection>> = _sections.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refreshFavorites()
    }

    fun refreshFavorites() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.getIntelligentFavorites()
                _sections.value = result
            } catch (e: Exception) {
                // Fallback to empty list or handle error
            } finally {
                _isLoading.value = false
            }
        }
    }
}
