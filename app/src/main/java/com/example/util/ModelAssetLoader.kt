package com.example.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for managing large neural model assets.
 * Handles the extraction of assets to internal storage to avoid JVM heap pressure
 * and enable memory-mapped loading via ONNX Runtime path APIs.
 */
object ModelAssetLoader {
    private const val TAG = "ModelAssetLoader"
    private const val MODELS_DIR = "aura_models"

    /**
     * Ensures an asset is available as a local file.
     * Reuses existing files if present and valid.
     */
    @Synchronized
    fun getLocalPath(context: Context, assetPath: String): String {
        val storageDir = File(context.filesDir, MODELS_DIR)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val fileName = assetPath.substringAfterLast("/")
        val localFile = File(storageDir, fileName)

        // Basic validation: exist and non-empty. 
        // In a production app, we'd also check CRC/Hash or version code.
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Using existing model file: ${localFile.absolutePath} (${localFile.length()} bytes)")
            return localFile.absolutePath
        }

        Log.i(TAG, "Extracting model asset: $assetPath -> ${localFile.absolutePath}")
        
        val tempFile = File(storageDir, "$fileName.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16384) // 16KB small heap-safe buffer
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            
            if (!tempFile.renameTo(localFile)) {
                throw IllegalStateException("Failed to finalize model extraction for $fileName")
            }
            
            Log.i(TAG, "Model extraction successful: $fileName")
            return localFile.absolutePath
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            Log.e(TAG, "Failed to extract model asset $assetPath", e)
            throw e
        }
    }
}
