package com.example.data

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.compatibility.PlaybackRouteResult
import com.example.data.db.AuraDatabase
import com.example.data.db.PlaybackErrorLogEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackErrorIntegrationTest {

    private lateinit var database: AuraDatabase
    private lateinit var repository: MediaRepository
    private lateinit var errorRepo: PlaybackErrorLogRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        repository = MediaRepository.instance
        repository.setDatabaseForTesting(database)
        
        errorRepo = PlaybackErrorLogRepository(database.playbackErrorLogDao())
        repository.setPlaybackErrorLogRepositoryForTesting(errorRepo)
    }

    @After
    fun tearDown() {
        repository.resetTestingState()
        database.close()
    }

    @Test
    fun testEndToEndErrorReporting() = runBlocking {
        val mediaItem = MediaItem(id = "error_media", title = "Corrupt Video", mediaType = "VIDEO", uriPath = "local://corrupt.mp4")
        val playbackException = PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        
        val mockPlayer: Player = mock()
        whenever(mockPlayer.playbackState).thenReturn(Player.STATE_IDLE)
        whenever(mockPlayer.currentPosition).thenReturn(5000L)
        
        repository.recordPlaybackError(playbackException, mockPlayer, mediaItem)
        
        var logs: List<PlaybackErrorLogEntity> = emptyList()
        for (i in 1..10) {
            logs = errorRepo.observeRecentErrors().first()
            if (logs.isNotEmpty()) break
            delay(50)
        }
        
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals("error_media", log.mediaItemId)
        assertEquals("Corrupt Video", log.mediaTitle)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, log.errorCode)
        assertEquals(5000L, log.playbackPositionMs)
        assertEquals("Media file not found or inaccessible", log.diagnosticSummary)
    }

    @Test
    fun testRouterFailureReporting() = runBlocking {
        val mediaItem = MediaItem(id = "router_media", title = "Unsupported Codec Video", mediaType = "VIDEO", uriPath = "local://unsupported.mkv")
        val route = PlaybackRouteResult.NeedsConversion("Format requires conversion for smooth playback", mediaItem)

        repository.recordRouterFailure(mediaItem, route)

        var logs: List<PlaybackErrorLogEntity> = emptyList()
        for (i in 1..10) {
            logs = errorRepo.observeRecentErrors().first()
            if (logs.isNotEmpty()) break
            delay(50)
        }

        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals("router_media", log.mediaItemId)
        assertEquals("PlaybackRouteResult.NeedsConversion", log.exceptionClass)
        assertEquals("ROUTER_NEEDS_CONVERSION", log.errorCodeName)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, log.errorCode)
        assertEquals("Router: Format requires conversion for smooth playback", log.diagnosticSummary)
    }

    @Test
    fun testInitializationRaceBuffering() = runBlocking {
        // Temporarily clear repository to simulate database initialization delay
        repository.setPlaybackErrorLogRepositoryForTesting(null)

        val mediaItem = MediaItem(id = "buffered_media", title = "Buffered Item", mediaType = "VIDEO", uriPath = "local://buffered.mp4")
        val route = PlaybackRouteResult.Corrupt("File header corrupt", mediaItem)

        // Record error while repository is unavailable -> should enter pending queue
        repository.recordRouterFailure(mediaItem, route)

        // Assign repository -> simulates database initialization completing
        repository.setPlaybackErrorLogRepositoryForTesting(errorRepo)

        var logs: List<PlaybackErrorLogEntity> = emptyList()
        for (i in 1..10) {
            logs = errorRepo.observeRecentErrors().first()
            if (logs.isNotEmpty()) break
            delay(50)
        }

        assertEquals(1, logs.size)
        assertEquals("buffered_media", logs[0].mediaItemId)
        assertEquals("ROUTER_CORRUPT", logs[0].errorCodeName)
    }

    @Test
    fun testDeduplication() = runBlocking {
        val mediaItem = MediaItem(id = "dedup_media", title = "Dedup Video", mediaType = "VIDEO", uriPath = "local://dedup.mp4")
        val playbackException = PlaybackException("Decoder error", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        val mockPlayer: Player = mock()

        repository.recordPlaybackError(playbackException, mockPlayer, mediaItem)
        delay(100)
        repository.recordPlaybackError(playbackException, mockPlayer, mediaItem)

        var logs: List<PlaybackErrorLogEntity> = emptyList()
        for (i in 1..10) {
            logs = errorRepo.observeRecentErrors().first()
            if (logs.isNotEmpty() && logs[0].occurrenceCount > 1) break
            delay(50)
        }

        assertEquals(1, logs.size)
        assertEquals(2, logs[0].occurrenceCount)
    }
}
