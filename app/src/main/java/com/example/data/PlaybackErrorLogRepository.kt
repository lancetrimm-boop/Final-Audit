package com.example.data

import com.example.data.db.PlaybackErrorLogDao
import com.example.data.db.PlaybackErrorLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Repository for managing playback error diagnostic records.
 */
class PlaybackErrorLogRepository(
    private val dao: PlaybackErrorLogDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    /**
     * Observe the most recent error logs.
     */
    fun observeRecentErrors(): Flow<List<PlaybackErrorLogEntity>> {
        return dao.observeRecentErrors()
    }

    /**
     * Observe error logs for a specific media item.
     */
    fun getErrorsForMedia(mediaItemId: String): Flow<List<PlaybackErrorLogEntity>> {
        return dao.getErrorsForMedia(mediaItemId)
    }

    /**
     * Observe error logs for a specific playback session.
     */
    fun getErrorsForSession(sessionId: String): Flow<List<PlaybackErrorLogEntity>> {
        return dao.getErrorsForSession(sessionId)
    }

    /**
     * Insert a new diagnostic record with deduplication.
     * Returns the ID of the record.
     */
    suspend fun recordError(error: PlaybackErrorLogEntity): Long {
        val existing = dao.findExistingError(
            mediaItemId = error.mediaItemId,
            errorCode = error.errorCode,
            exceptionClass = error.exceptionClass,
            sessionId = error.sessionId
        )

        return if (existing != null) {
            // If it's an identical error in the same session, update the existing record
            val updated = existing.copy(
                occurrenceCount = existing.occurrenceCount + 1,
                lastOccurrenceTimestamp = System.currentTimeMillis(),
                playbackPositionMs = error.playbackPositionMs,
                playbackState = error.playbackState,
                playWhenReady = error.playWhenReady
            )
            dao.update(updated)
            existing.id
        } else {
            val insertedId = dao.insert(error)
            dao.trimLog(MAX_ERROR_LOG_SIZE)
            insertedId
        }
    }

    /**
     * Update the recovery status for an error record.
     */
    suspend fun updateRecoveryStatus(id: Long, attempted: Boolean, successful: Boolean?) {
        dao.updateRecoveryStatus(id, attempted, successful)
    }

    /**
     * Delete an individual record.
     */
    fun deleteError(errorId: Long) {
        scope.launch {
            dao.delete(errorId)
        }
    }

    /**
     * Clear all diagnostic records.
     */
    fun clearAllLogs() {
        scope.launch {
            dao.deleteAll()
        }
    }

    companion object {
        private const val MAX_ERROR_LOG_SIZE = 500
    }
}
