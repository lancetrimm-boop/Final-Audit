package com.example.data.media

import android.content.Context
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.*
import java.io.File

/**
 * Abstraction for the video encoding engine to enable deterministic testing.
 */
@androidx.media3.common.util.UnstableApi
interface MomentEncodingEngine {
    suspend fun encode(composition: Composition, outputFile: File, onProgress: (Int) -> Unit): Result<File>
}

/**
 * Production implementation using Media3 Transformer.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3MomentEncodingEngine(private val context: Context) : MomentEncodingEngine {
    
    override suspend fun encode(
        composition: Composition,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Result<File>>()
        
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .build()
            
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                deferred.complete(Result.success(outputFile))
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                deferred.complete(Result.failure(exception))
            }
        }
        
        transformer.addListener(listener)
        transformer.start(composition, outputFile.absolutePath)
        
        val progressJob = launch(Dispatchers.Main) {
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            while (deferred.isActive) {
                val state = transformer.getProgress(progressHolder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(progressHolder.progress)
                }
                delay(500)
            }
        }
        
        val result = deferred.await()
        progressJob.cancel()
        result
    }
}
