package com.example.data

import com.example.data.db.InteractionDao
import com.example.data.db.InteractionEventEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Authoritative repository for local behavioral interaction memory.
 * Centralizes the persistence and retrieval of raw interaction signals.
 */
class InteractionRepository(
    private val dao: InteractionDao,
    private val moshi: Moshi = Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val contextAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    /**
     * Records a standardized behavioral signal.
     */
    fun recordSignal(
        mediaId: String?,
        type: String,
        value: Double,
        context: Map<String, String>? = null
    ) {
        scope.launch {
            val event = InteractionEventEntity(
                id = UUID.randomUUID().toString(),
                mediaId = mediaId,
                type = type,
                value = value,
                timestamp = System.currentTimeMillis(),
                contextJson = context?.let { contextAdapter.toJson(it) }
            )
            dao.insert(event)
        }
    }

    /**
     * Observes recent interaction events for real-time preference updates.
     */
    fun observeEventsAfter(timestamp: Long): Flow<List<InteractionEventEntity>> =
        dao.observeEventsAfter(timestamp)

    /**
     * Retrieves interaction events for batch processing.
     */
    suspend fun getEventsAfter(timestamp: Long): List<InteractionEventEntity> =
        dao.getEventsAfter(timestamp)

    /**
     * Prunes old interaction events to maintain database performance.
     */
    suspend fun pruneOldEvents(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        dao.pruneOldEvents(cutoff)
    }
}
