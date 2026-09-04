package com.example.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for managing large neural model assets.
 * Handles the extraction of assets to internal storage to avoid JVM heap pressure
 * and enable memory-mapped loading via ONNX Runtime path APIs.
 *
 * Supports Play Asset Delivery (PAD) as a secondary retrieval path.
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
        if (localFile.exists() && localFile.length() > 0) {
            Log.d(TAG, "Using existing model file: ${localFile.absolutePath} (${localFile.length()} bytes)")
            return localFile.absolutePath
        }

        Log.i(TAG, "Extracting model asset: $assetPath -> ${localFile.absolutePath}")
        
        val tempFile = File(storageDir, "$fileName.tmp")
        try {
            // 1. Try to open via standard AssetManager (works for merged install-time packs)
            val inputStream = try {
                context.assets.open(assetPath)
            } catch (e: Exception) {
                Log.d(TAG, "Standard assets.open failed for $assetPath, trying Play Asset Delivery path...")
                
                // 2. Try to find the file via AssetPackManager (works if delivered as a split)
                val packName = "model_pack"
                val assetPackManager = com.google.android.play.core.assetpacks.AssetPackManagerFactory.getInstance(context)
                val location = assetPackManager.getPackLocation(packName)
                
                if (location != null) {
                    val assetsPath = location.assetsPath()
                    Log.d(TAG, "Found $packName at $assetsPath")
                    val fileInPack = File(assetsPath, assetPath)
                    if (fileInPack.exists()) {
                        java.io.FileInputStream(fileInPack)
                    } else {
                        Log.e(TAG, "File $assetPath not found in pack location $assetsPath")
                        null
                    }
                } else {
                    // Try createContextForSplit if on O+
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        try {
                            val splitContext = context.createContextForSplit(packName)
                            Log.d(TAG, "Created context for split $packName")
                            splitContext.assets.open(assetPath)
                        } catch (e2: Exception) {
                            Log.e(TAG, "createContextForSplit failed for $packName", e2)
                            null
                        }
                    } else {
                        Log.e(TAG, "Asset pack $packName not found/not installed.")
                        null
                    }
                }
            }

            if (inputStream == null) {
                throw java.io.FileNotFoundException("Could not locate model asset $assetPath in APK or Asset Pack")
            }

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16384)
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
