package com.example.ui.components

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreviewPriority {
    VISIBLE,   // Priority 1: Currently in viewport
    NEARBY,    // Priority 2: In the 10-item window but not visible
    NONE       // Priority 3: Outside the window
}

enum class ScrollDirection {
    DOWN, UP, IDLE
}

/**
 * Viewport-aware coordinator for Aura video previews.
 * Maintains the "Window of Interest" (10 items) and communicates priorities to the pool.
 */
object PreviewCoordinator {
    private val _visibleIds = MutableStateFlow<Set<String>>(emptySet())
    val visibleIds: StateFlow<Set<String>> = _visibleIds.asStateFlow()

    private val _nearbyIds = MutableStateFlow<Set<String>>(emptySet())
    val nearbyIds: StateFlow<Set<String>> = _nearbyIds.asStateFlow()

    // High-priority "Next" IDs based on scroll direction
    private val _priorityNearbyIds = MutableStateFlow<Set<String>>(emptySet())
    val priorityNearbyIds: StateFlow<Set<String>> = _priorityNearbyIds.asStateFlow()

    private val _scrollDirection = MutableStateFlow(ScrollDirection.IDLE)
    val scrollDirection: StateFlow<ScrollDirection> = _scrollDirection.asStateFlow()

    // Scroll speed state for adaptive preparation
    private val _isScrollingFast = MutableStateFlow(false)
    val isScrollingFast: StateFlow<Boolean> = _isScrollingFast.asStateFlow()

    private var lastFirstVisibleIndex = -1

    /**
     * Updates the current window based on LazyGrid layout info.
     * @param visible Ordered list of media IDs currently in the viewport.
     * @param all Ordered list of all media IDs in the current grid context.
     * @param firstVisibleIndex The current scroll index for direction detection.
     */
    fun updateWindow(visible: List<String>, all: List<String>, firstVisibleIndex: Int) {
        // 1. Detect Direction
        if (lastFirstVisibleIndex != -1) {
            _scrollDirection.value = when {
                firstVisibleIndex > lastFirstVisibleIndex -> ScrollDirection.DOWN
                firstVisibleIndex < lastFirstVisibleIndex -> ScrollDirection.UP
                else -> _scrollDirection.value // Maintain last if equal
            }
        }
        lastFirstVisibleIndex = firstVisibleIndex

        _visibleIds.value = visible.toSet()
        
        if (visible.isEmpty() || all.isEmpty()) {
            _nearbyIds.value = emptySet()
            _priorityNearbyIds.value = emptySet()
            return
        }

        val firstVisibleIdx = all.indexOf(visible.first()).coerceAtLeast(0)
        val lastVisibleIdx = all.indexOf(visible.last()).coerceAtLeast(0)

        // 10-item window total
        val totalWindowSize = 10
        val remainingBuffer = (totalWindowSize - visible.size).coerceAtLeast(0)
        
        // Direction-Aware Split
        // If scrolling DOWN, buffer more BELOW. If UP, buffer more ABOVE.
        val bufferAbove: Int
        val bufferBelow: Int
        
        when (_scrollDirection.value) {
            ScrollDirection.DOWN -> {
                bufferBelow = (remainingBuffer * 0.75).toInt().coerceAtLeast(1)
                bufferAbove = remainingBuffer - bufferBelow
            }
            ScrollDirection.UP -> {
                bufferAbove = (remainingBuffer * 0.75).toInt().coerceAtLeast(1)
                bufferBelow = remainingBuffer - bufferAbove
            }
            else -> {
                bufferAbove = remainingBuffer / 2
                bufferBelow = remainingBuffer - bufferAbove
            }
        }

        val startIndex = (firstVisibleIdx - bufferAbove).coerceAtLeast(0)
        val endIndex = (lastVisibleIdx + bufferBelow).coerceAtMost(all.size - 1)

        val windowIds = all.subList(startIndex, endIndex + 1).toSet()
        val nearby = windowIds - _visibleIds.value
        _nearbyIds.value = nearby

        // Priority Nearby: Items in the direction of travel
        _priorityNearbyIds.value = when (_scrollDirection.value) {
            ScrollDirection.DOWN -> all.subList(lastVisibleIdx + 1, (lastVisibleIdx + bufferBelow + 1).coerceAtMost(all.size)).toSet()
            ScrollDirection.UP -> all.subList((firstVisibleIdx - bufferAbove).coerceAtLeast(0), firstVisibleIdx).toSet()
            else -> nearby
        }
    }

    fun setScrollingFast(fast: Boolean) {
        if (_isScrollingFast.value != fast) {
            _isScrollingFast.value = fast
        }
    }

    fun getPriority(mediaId: String): PreviewPriority {
        return when {
            _visibleIds.value.contains(mediaId) -> PreviewPriority.VISIBLE
            _nearbyIds.value.contains(mediaId) -> PreviewPriority.NEARBY
            else -> PreviewPriority.NONE
        }
    }
}
