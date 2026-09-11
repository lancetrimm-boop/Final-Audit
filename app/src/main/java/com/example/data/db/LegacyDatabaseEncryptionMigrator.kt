package com.example.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase as StandardSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase as EncryptedSQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Handles the secure transition of a legacy plaintext SQLite database to SQLCipher encryption,
 * and manages key-format migrations for forward compatibility.
 */
object LegacyDatabaseEncryptionMigrator {
    private const val TAG = "AURA_TRANSITION_FORENSIC"
    private const val DATABASE_NAME = "aura_intelligence.db"

    sealed class TransitionResult {
        object Success : TransitionResult()
        object AlreadyEncrypted : TransitionResult()
        object LegacyNotPresent : TransitionResult()
        data class Failure(val reason: String, val cause: Throwable? = null) : TransitionResult()
    }

    /**
     * Ensures the database is encrypted and uses the current hardware-backed key format.
     */
    fun ensureEncryption(context: Context): TransitionResult {
        val dbPath = context.getDatabasePath(DATABASE_NAME)
        val backupDbPath = File(dbPath.parent, "${DATABASE_NAME}.legacy_bak")

        if (!dbPath.exists() && backupDbPath.exists()) {
            Log.w(TAG, "Interrupted transition detected. Restoring legacy backup for retry.")
            if (!backupDbPath.renameTo(dbPath)) {
                return TransitionResult.Failure("Failed to restore legacy backup from interrupted swap.")
            }
        }

        if (!dbPath.exists() || dbPath.length() == 0L) {
            return TransitionResult.LegacyNotPresent
        }

        if (isDatabaseEncrypted(dbPath)) {
            Log.i(TAG, "Database is already encrypted. Validating key format...")
            
            val binaryKey = try {
                PassphraseManager.getPassphrase(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to retrieve encryption key for validation: ${e.message}")
                // AURA REPAIR: If we can't get the key but it IS encrypted, return AlreadyEncrypted 
                // to signal that we don't need a transition from plaintext.
                return TransitionResult.AlreadyEncrypted 
            }
            
            // 1. Try primary binary key (Current Standard)
            try {
                EncryptedSQLiteDatabase.openDatabase(dbPath.absolutePath, binaryKey, null, EncryptedSQLiteDatabase.OPEN_READONLY, null).use { 
                    Log.i(TAG, "Encryption validation successful (Binary Key).")
                }
                return TransitionResult.AlreadyEncrypted
            } catch (t: Throwable) {
                Log.w(TAG, "Primary binary key failed (HMAC mismatch). Checking legacy key format...")
            }

            // 2. Try legacy hex-string key (Historical/Baseline format)
            try {
                val hexKeyBytes = PassphraseManager.getPassphraseAsHex(context).toByteArray()
                EncryptedSQLiteDatabase.openDatabase(dbPath.absolutePath, hexKeyBytes, null, EncryptedSQLiteDatabase.OPEN_READONLY, null).use { 
                    Log.i(TAG, "Legacy key format working. Migration required.")
                }
                return performLegacyFormatMigration(context, dbPath, hexKeyBytes, binaryKey)
            } catch (t: Throwable) {
                Log.e(TAG, "All known key formats failed validation.")
                return handleUnrecoverableDatabase(dbPath)
            }
        }

        Log.i(TAG, "Plaintext legacy database detected. Starting transition...")
        
        val requiredSpace = dbPath.length() * 2
        val usableSpace = dbPath.parentFile?.usableSpace ?: 0L
        if (usableSpace < requiredSpace) {
            return TransitionResult.Failure("Insufficient disk space for transition.")
        }

        return try {
            val binaryKey = PassphraseManager.getPassphrase(context)
            performTransition(dbPath, binaryKey)
            TransitionResult.Success
        } catch (t: Throwable) {
            Log.e(TAG, "Transition failed: ${t.message}", t)
            TransitionResult.Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun isDatabaseEncrypted(dbPath: File): Boolean {
        if (!dbPath.exists() || dbPath.length() < 16L) return false
        return try {
            val header = ByteArray(16)
            java.io.FileInputStream(dbPath).use { fis -> fis.read(header) }
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            !header.contentEquals(sqliteMagic)
        } catch (e: Exception) { true }
    }

    /**
     * Migrates data from a plaintext source to a new encrypted container.
     */
    private fun performTransition(originalDbPath: File, rawKey: ByteArray) {
        val tempDbPath = File(originalDbPath.parent, "${DATABASE_NAME}.tmp")
        val backupDbPath = File(originalDbPath.parent, "${DATABASE_NAME}.legacy_bak")

        if (tempDbPath.exists()) tempDbPath.delete()
        
        var legacyVersion = 0
        val baselineMetrics = mutableMapOf<String, Int>()
        val criticalTables = listOf("media_items", "user_preferences", "pairwise_outcomes")
        
        StandardSQLiteDatabase.openDatabase(originalDbPath.absolutePath, null, StandardSQLiteDatabase.OPEN_READONLY).use { db ->
            legacyVersion = db.version
            criticalTables.forEach { table ->
                try {
                    db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                        if (cursor.moveToFirst()) baselineMetrics[table] = cursor.getInt(0)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Table $table not found in legacy schema.")
                }
            }
        }

        // 1. Create encrypted destination
        EncryptedSQLiteDatabase.openOrCreateDatabase(tempDbPath, rawKey, null, null).use { db ->
            db.version = legacyVersion
        }

        // 2. Open source via SQLCipher using empty key (treats as plaintext)
        val plaintextDb = EncryptedSQLiteDatabase.openDatabase(originalDbPath.absolutePath, "".toByteArray(), null, EncryptedSQLiteDatabase.OPEN_READWRITE, null)
        try {
            // 3. Attach using x'HEX' literal
            val hexKeyLiteral = bytesToHex(rawKey)
            plaintextDb.execSQL("ATTACH DATABASE '${tempDbPath.absolutePath}' AS encrypted KEY \"x'$hexKeyLiteral'\"")
            
            try {
                Log.i(TAG, "Exporting data to encrypted container...")
                plaintextDb.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
            } finally {
                plaintextDb.execSQL("DETACH DATABASE encrypted")
            }
        } finally {
            plaintextDb.close()
        }

        // 4. Verify Integrity
        verifyIntegrity(tempDbPath, rawKey, baselineMetrics)

        // 5. Atomic Swap
        swapFiles(originalDbPath, tempDbPath, backupDbPath)
        Log.i(TAG, "Plaintext-to-Encrypted transition complete.")
    }

    /**
     * Migrates an encrypted database between different key formats.
     */
    private fun performLegacyFormatMigration(
        context: Context,
        originalDbPath: File,
        legacyKey: ByteArray,
        binaryKey: ByteArray
    ): TransitionResult {
        Log.i(TAG, "Performing key-format migration (String -> Binary Entropy)...")
        val tempDbPath = File(originalDbPath.parent, "${DATABASE_NAME}.migrated")
        val backupDbPath = File(originalDbPath.parent, "${DATABASE_NAME}.key_format_bak")
        
        if (tempDbPath.exists()) tempDbPath.delete()

        try {
            val baselineMetrics = mutableMapOf<String, Int>()
            val criticalTables = listOf("media_items", "user_preferences", "pairwise_outcomes")

            // 1. Open legacy and capture baselines
            EncryptedSQLiteDatabase.openDatabase(originalDbPath.absolutePath, legacyKey, null, EncryptedSQLiteDatabase.OPEN_READWRITE, null).use { legacyDb ->
                criticalTables.forEach { table ->
                    try {
                        legacyDb.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                            if (cursor.moveToFirst()) baselineMetrics[table] = cursor.getInt(0)
                        }
                    } catch (e: Exception) {}
                }

                // 2. Attach new container with binary key
                val binaryHexLiteral = bytesToHex(binaryKey)
                legacyDb.execSQL("ATTACH DATABASE '${tempDbPath.absolutePath}' AS migrated KEY \"x'$binaryHexLiteral'\"")
                
                try {
                    // 3. Export
                    legacyDb.rawQuery("SELECT sqlcipher_export('migrated')", null).use { it.moveToFirst() }
                    legacyDb.version.let { ver ->
                        // Transfer version pragma
                        legacyDb.execSQL("PRAGMA migrated.user_version = $ver")
                    }
                } finally {
                    legacyDb.execSQL("DETACH DATABASE migrated")
                }
            }

            // 4. Verify Integrity
            verifyIntegrity(tempDbPath, binaryKey, baselineMetrics)

            // 5. Atomic Swap
            swapFiles(originalDbPath, tempDbPath, backupDbPath)
            
            Log.i(TAG, "Key-format migration complete.")
            return TransitionResult.Success

        } catch (t: Throwable) {
            Log.e(TAG, "Key-format migration failed", t)
            if (tempDbPath.exists()) tempDbPath.delete()
            return TransitionResult.Failure("Failed to migrate legacy key format: ${t.message}", t)
        }
    }

    private fun verifyIntegrity(dbPath: File, key: ByteArray, baselineMetrics: Map<String, Int>) {
        EncryptedSQLiteDatabase.openDatabase(dbPath.absolutePath, key, null, EncryptedSQLiteDatabase.OPEN_READONLY, null).use { db ->
            baselineMetrics.forEach { (table, expected) ->
                db.rawQuery("SELECT count(*) FROM $table", null).use { cursor ->
                    if (cursor.moveToFirst() && cursor.getInt(0) != expected) {
                        throw IllegalStateException("Data loss detected in $table: Expected $expected, found ${cursor.getInt(0)}")
                    }
                }
            }
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(0) != "ok") {
                    throw IllegalStateException("Integrity check failed: ${cursor.getString(0)}")
                }
            }
        }
    }

    private fun swapFiles(original: File, new: File, backup: File) {
        if (backup.exists()) backup.delete()
        if (!original.renameTo(backup)) throw IllegalStateException("Failed to create backup")
        if (!new.renameTo(original)) {
            backup.renameTo(original)
            throw IllegalStateException("Failed to swap new database into place")
        }
    }

    private fun handleUnrecoverableDatabase(dbPath: File): TransitionResult {
        Log.e(TAG, "Database is unrecoverable (Key Mismatch or Corruption). Quarantining...")
        val quarantinePath = File(dbPath.parent, "aura_quarantine_${System.currentTimeMillis()}.db")
        return if (dbPath.renameTo(quarantinePath)) {
            Log.i(TAG, "Unrecoverable database moved to: ${quarantinePath.name}")
            TransitionResult.Success // Allow Aura to start fresh
        } else {
            TransitionResult.Failure("Failed to quarantine unrecoverable database.")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    fun cleanupLegacyBackups(context: Context) {
        val dir = context.getDatabasePath(DATABASE_NAME).parentFile ?: return
        val backups = dir.listFiles { _, name -> 
            name.endsWith(".legacy_bak") || name.endsWith(".key_format_bak") 
        } ?: return

        val prefs = context.getSharedPreferences("aura_transition_prefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("successful_launches", 0) + 1
        
        if (count >= 5) {
            backups.forEach { it.delete() }
            Log.i(TAG, "Legacy backups cleaned up after stable period.")
        } else {
            prefs.edit().putInt("successful_launches", count).apply()
        }
    }
}
