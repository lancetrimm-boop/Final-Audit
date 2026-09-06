package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InteractionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: InteractionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<InteractionEventEntity>)

    @Query("SELECT * FROM interaction_events WHERE timestamp >= :timestamp ORDER BY timestamp DESC")
    fun observeEventsAfter(timestamp: Long): Flow<List<InteractionEventEntity>>

    @Query("SELECT * FROM interaction_events WHERE timestamp >= :timestamp ORDER BY timestamp DESC")
    suspend fun getEventsAfter(timestamp: Long): List<InteractionEventEntity>

    @Query("SELECT * FROM interaction_events WHERE mediaId = :mediaId ORDER BY timestamp DESC")
    suspend fun getEventsForMedia(mediaId: String): List<InteractionEventEntity>

    @Query("DELETE FROM interaction_events WHERE timestamp < :timestamp")
    suspend fun pruneOldEvents(timestamp: Long)

    @Query("SELECT COUNT(*) FROM interaction_events")
    suspend fun getEventCount(): Int
}
