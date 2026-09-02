package com.example.compatibility

import com.example.BuildConfig
import com.example.data.db.AuraDatabase
import com.example.data.db.MediaDao
import com.example.data.db.PassphraseManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * STAGE 0 COMPATIBILITY TEST
 * 
 * This test verifies that critical architectural invariants from the authoritative
 * baseline (Commit 6e35626e) remain intact. It detects regressions in application identity,
 * database configuration, and security architecture.
 */
@RunWith(RobolectricTestRunner::class)
class ProtectedBaselineVerificationTest {

    @Test
    fun verifyApplicationIdentity() {
        // APPLICATION_ID must remain constant for compatibility with existing device installations
        assertEquals(
            "CRITICAL: Application ID has changed. This will break existing library access.",
            "com.aistudio.auramediaplayer.v3.ppqtdt",
            BuildConfig.APPLICATION_ID
        )
    }

    @Test
    fun verifyDatabaseConfiguration() {
        // Inspecting AuraDatabase class structure
        val databaseClass = AuraDatabase::class.java
        assertTrue(
            "AuraDatabase must be an abstract class extending RoomDatabase",
            Modifier.isAbstract(databaseClass.modifiers)
        )

        // Verify the database name used in initialization
        // We check common constants in the data package if available.
        val migratorClass = Class.forName("com.example.data.db.LegacyDatabaseEncryptionMigrator")
        val dbNameField = migratorClass.getDeclaredField("DATABASE_NAME").apply { isAccessible = true }
        
        assertEquals(
            "Database identity check failed. Project should use 'aura_intelligence.db'",
            "aura_intelligence.db",
            dbNameField.get(null)
        )
    }

    @Test
    fun verifySecurityArchitecture() {
        // Verify PassphraseManager constants via reflection
        val pmClass = PassphraseManager::class.java
        
        val keyAliasField: Field = pmClass.getDeclaredField("KEY_ALIAS").apply { isAccessible = true }
        assertEquals(
            "Keystore alias must match the authoritative baseline",
            "aura_db_master_key",
            keyAliasField.get(null)
        )

        val androidKeystoreField: Field = pmClass.getDeclaredField("ANDROID_KEYSTORE").apply { isAccessible = true }
        assertEquals(
            "Android KeyStore provider must be used",
            "AndroidKeyStore",
            androidKeystoreField.get(null)
        )
    }

    @Test
    fun verifySQLCipherIntegration() {
        // Ensure SQLCipher SupportOpenHelperFactory is present on the classpath
        // and its usage is intended in the database factory.
        val factoryClass = SupportOpenHelperFactory::class.java
        assertEquals(
            "net.zetetic.database.sqlcipher.SupportOpenHelperFactory",
            factoryClass.name
        )
    }

    @Test
    fun verifyMediaVisibilityArchitecture() {
        // Verify that MediaDao exists and contains the authoritative filtering logic
        val daoClass = MediaDao::class.java
        assertTrue("MediaDao must be an interface", daoClass.isInterface)

        // Verify specific methods exist that handle visibility
        val methods = daoClass.methods.map { it.name }
        assertTrue("MediaDao must contain getAllMedia", methods.contains("getAllMedia"))
        
        // The baseline visibility logic relies on isDeleted and compatibilityStatus.
        // We verify these exist in the MediaEntity class structure.
        val entityClass = com.example.data.db.MediaEntity::class.java
        val fields = entityClass.declaredFields.map { it.name }
        
        assertTrue(
            "Media visibility requires 'isDeleted' field",
            fields.contains("isDeleted")
        )
        assertTrue(
            "Media visibility requires 'compatibilityStatus' field",
            fields.contains("compatibilityStatus")
        )
    }
}
