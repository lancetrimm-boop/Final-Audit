package com.example.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.models.LibraryItemUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: MediaRepository? = null
) : ViewModel() {

    private val _displayedItems = MutableStateFlow<List<LibraryItemUi>>(emptyList())
    val displayedItems: StateFlow<List<LibraryItemUi>> = _displayedItems.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    private var currentOffset = 0
    private val pageSize = 25

    init {
        if (repository != null) {
            viewModelScope.launch {
                repository.mediaItems.collect { items ->
                    if (items.isNotEmpty() && _displayedItems.value.isEmpty()) {
                        refreshLibrary()
                    }
                }
            }
        }
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            currentOffset = 0
            _endReached.value = false
            _isLoadingMore.value = true
            try {
                val totalMediaCount = repository?.mediaItems?.value?.size ?: 0
                val firstPage = if (repository != null) {
                    repository.loadLibraryPage(
                        offset = 0,
                        limit = pageSize,
                        filter = repository.libraryFilter,
                        sortCategory = repository.sortCategory,
                        standardSort = repository.standardSort,
                        intelligentSort = repository.intelligentSort
                    )
                } else {
                    emptyList()
                }
                _displayedItems.value = firstPage
                currentOffset = firstPage.size
                if (firstPage.size < pageSize && totalMediaCount > 0) {
                    _endReached.value = true
                }
                Log.d("LibraryPaging", "refreshLibrary → loaded ${firstPage.size}, totalMediaCount=$totalMediaCount")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value || _endReached.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val next = if (repository != null) {
                    repository.loadLibraryPage(
                        offset = currentOffset,
                        limit = pageSize,
                        filter = repository.libraryFilter,
                        sortCategory = repository.sortCategory,
                        standardSort = repository.standardSort,
                        intelligentSort = repository.intelligentSort
                    )
                } else {
                    emptyList()
                }
                if (next.isEmpty()) {
                    _endReached.value = true
                } else {
                    _displayedItems.value = _displayedItems.value + next
                    currentOffset += next.size
                    if (next.size < pageSize) {
                        _endReached.value = true
                    }
                }
                Log.d("LibraryPaging", "loadMore → added ${next.size}, total=${_displayedItems.value.size}")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }
}
