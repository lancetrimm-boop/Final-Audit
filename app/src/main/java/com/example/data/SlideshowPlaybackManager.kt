package com.example.data

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Central manager for slideshow playback resources.
 * Ensures strict sequential lifecycle for the shared Media3 instance.
 */
class SlideshowPlaybackManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private val TAG = "SlideshowPlaybackManager"

    fun getPlayer(): Player {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }
            Log.d(TAG, "Unified ExoPlayer instance initialized")
        }
        return exoPlayer!!
    }

    @OptIn(UnstableApi::class)
    fun prepareVideo(item: MediaItem) {
        val player = exoPlayer ?: getPlayer()
        val uri = item.uriPath.ifBlank { item.imageUrl }
        
        Log.d(TAG, "Preparing video candidate: ${item.title}")
        player.setMediaItem(Media3Item.fromUri(uri))
        player.prepare()
    }

    fun stop() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        Log.d(TAG, "Slideshow playback resources released")
    }
}
