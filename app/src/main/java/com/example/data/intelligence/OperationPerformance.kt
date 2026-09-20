package com.example.data.intelligence

/**
 * Immutable model for contextual performance metrics.
 */
data class OperationPerformance(
    val operationName: String,
    val totalDurationMs: Long = -1,
    val intelligenceDurationMs: Long = -1,
    val rankingDurationMs: Long = -1,
    val databaseDurationMs: Long = -1,
    val inferenceDurationMs: Long = -1,
    val candidateCount: Int = -1,
    val resultCount: Int = -1,
    val cacheHit: Boolean? = null,
    val timestamp: Long = System.currentTimeMillis()
)
