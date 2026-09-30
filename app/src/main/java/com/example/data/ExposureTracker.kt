package com.example.data

import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide Exposure Tracker managing two layers of exposure:
 * 1. Session Exposure (in-memory, strong short-term repetition penalty).
 * 2. Persistent Exposure (survives restarts, backed by Room/MediaRepository).
 */
class ExposureTracker(
    private val repositoryProvider: (() -> MediaRepository)? = null
) {
    private val _sessionExposures = ConcurrentHashMap<String, Int>()
    val sessionExposures: Map<String, Int> get() = _sessionExposures

    fun recordExposure(mediaId: String) {
        if (mediaId.isBlank()) return
        _sessionExposures[mediaId] = (_sessionExposures[mediaId] ?: 0) + 1
        repositoryProvider?.invoke()?.recordExposure(mediaId)
    }

    fun recordExposures(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            if (id.isNotBlank()) {
                _sessionExposures[id] = (_sessionExposures[id] ?: 0) + 1
            }
        }
        repositoryProvider?.invoke()?.recordExposures(ids)
    }

    fun getSessionExposure(mediaId: String): Int {
        return _sessionExposures[mediaId] ?: 0
    }

    fun resetSession() {
        _sessionExposures.clear()
    }
}
