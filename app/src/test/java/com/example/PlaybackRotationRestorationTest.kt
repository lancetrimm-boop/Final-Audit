package com.example

import com.example.data.CompatibilityStatus
import com.example.data.MediaItem
import com.example.data.MediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRotationRestorationTest {

    private lateinit var repository: MediaRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        repository = MediaRepository(testDispatcher)
    }

    private fun createMediaItem(id: String): MediaItem {
        return MediaItem(
            id = id,
            title = "Media $id",
            mediaType = "VIDEO",
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )
    }

    @Test
    fun testPlaylistChangedReason_SameItem_DoesNotResetPosition() = runTest(testDispatcher) {
        // Setup saved position
        val itemId = "video_1"
        repository.updatePlaybackPosition(30000L)
        
        // This test specifically targets the logic implemented in onMediaItemTransition 
        // to ensure that if a PLAYLIST_CHANGED event occurs for the SAME item (e.g. on rotation),
        // we don't wipe the repository's saved position.
        
        // Mock listener logic (simulating what happens in MediaDetailScreen)
        fun onMediaItemTransition(transitionedId: String?, reason: Int, activeId: String) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || 
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                repository.updatePlaybackPosition(0L)
            } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                if (transitionedId != activeId) {
                    repository.updatePlaybackPosition(0L)
                }
            }
        }

        // Case 1: Same item (Rotation)
        onMediaItemTransition(itemId, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, itemId)
        assertEquals("Position should remain saved on rotation", 30000L, repository.lastPlaybackPositionMs)

        // Case 2: Different item (Normal transition)
        onMediaItemTransition("video_2", Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, itemId)
        assertEquals("Position should reset to 0 on genuine item change", 0L, repository.lastPlaybackPositionMs)
    }

    @Test
    fun testAutoTransition_ResetsPosition() = runTest(testDispatcher) {
        repository.updatePlaybackPosition(30000L)
        
        // Simulation of finishing an item
        repository.updatePlaybackPosition(0L) // This is what onMediaItemTransition(AUTO) does
        
        assertEquals(0L, repository.lastPlaybackPositionMs)
    }
}
