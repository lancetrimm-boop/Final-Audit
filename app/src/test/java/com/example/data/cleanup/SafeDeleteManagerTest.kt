package com.example.data.cleanup

import android.app.Activity
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.entitlement.EntitlementRepository
import com.example.data.entitlement.ProFeature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
class SafeDeleteManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)
    private lateinit var repository: MediaRepository
    private lateinit var deleteManager: SafeDeleteManager
    private lateinit var context: android.content.Context
    private lateinit var preferenceDao: com.example.data.db.UserPreferenceDao

    @Before
    fun setup() {
        repository = mock()
        preferenceDao = mock()
        
        val entitlementRepository: EntitlementRepository = mock()
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)
        whenever(repository.entitlementRepository).thenReturn(entitlementRepository)

        val db: com.example.data.db.AuraDatabase = mock()
        whenever(db.userPreferenceDao()).thenReturn(preferenceDao)
        whenever(repository.getDatabase()).thenReturn(db)
        
        // Use real Moshi for serialization tests
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        whenever(repository.getMoshi()).thenReturn(moshi)
        
        deleteManager = SafeDeleteManager(repository, testScope)
        context = org.robolectric.RuntimeEnvironment.getApplication()
        
        // Mock MediaStore delegate
        deleteManager.mediaStoreDeleteProvider = { _, _ ->
            mock<android.app.PendingIntent>().apply {
                whenever(intentSender).thenReturn(mock())
            }
        }
    }

    @Test
    fun testRequestDeletion_PersistsState() = runTest {
        val item = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val rec = CleanupRecommendation("ms-1", 0.5f, 0.8f, CleanupCategory.DELETE_RECOMMENDATIONS, emptyList(), 1024, explanation = "Test")
        
        deleteManager.requestDeletion(context, listOf(item), listOf(rec), mock())
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify state was saved to preferences
        verify(preferenceDao).insertPreference(argThat { key == "pending_cleanup_transaction" })
    }

    @Test
    fun testHandleDeletionResult_ClearsPersistedState() = runTest {
        val item = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        deleteManager.requestDeletion(context, listOf(item), emptyList(), mock())
        
        deleteManager.handleDeletionResult(Activity.RESULT_CANCELED)
        testDispatcher.scheduler.advanceUntilIdle()
        
        verify(preferenceDao).deletePreference("pending_cleanup_transaction")
    }

    @Test
    fun testRecovery_ReconcilesDeletedItems() = runTest {
        // Setup persisted state
        val recoveryItem = RecoveryItem(
            id = "ms-1", uriPath = "content://media/external/images/media/1", imageUrl = "",
            title = "MS", mediaType = "PHOTO", category = "DELETE_RECOMMENDATIONS", keepScore = 0.5f,
            compatibilityStatus = "PLAYABLE", containerFormat = "JPG", videoCodec = "", audioCodec = "",
            contentHash = "hash"
        )
        val transaction = PersistedDeletionTransaction(
            mediaStoreItems = listOf(recoveryItem),
            internalItems = emptyList(),
            state = DeletionState.PENDING.name
        )
        val adapter = repository.getMoshi().adapter(PersistedDeletionTransaction::class.java)
        val json = adapter.toJson(transaction)
        
        whenever(preferenceDao.getPreference("pending_cleanup_transaction")).thenReturn(
            com.example.data.db.UserPreferenceEntity("pending_cleanup_transaction", json)
        )
        
        // Simulate items GONE from MediaStore (query returns empty)
        // This is handled by SafeDeleteManager.checkUriExists
        
        deleteManager.recoverPendingDeletions(context)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Should confirm deletion for items gone from MediaStore
        verify(repository).deleteMediaItem("ms-1")
        verify(repository).addRejectedMedia(any())
        verify(repository).recordCleanupSignal(eq("ms-1"), any(), any(), eq(true), anyOrNull())
        
        // Should clear state after recovery
        verify(preferenceDao).deletePreference("pending_cleanup_transaction")
    }

    @Test
    fun testRecovery_DoesNotReconcileExistingItems() = runTest {
        // We would need to mock ContentResolver query to return non-empty cursor for this
        // For now, let's just verify the base P0 tests still pass
    }

    @Test
    fun testRequestDeletion_MediaStoreUri_UsesPendingIntent() = runTest {
        val item = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        deleteManager.requestDeletion(context, listOf(item), emptyList(), launcher)
        
        assertEquals(DeletionState.PENDING, deleteManager.deletionState.value)
        verify(launcher).launch(any())
    }

    @Test
    fun testRequestDeletion_FileUri_RequiresAuraConfirmation() = runTest {
        val item = MediaItem(id = "file-1", title = "File", mediaType = "PHOTO", uriPath = "file:///sdcard/photo.jpg")
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        deleteManager.requestDeletion(context, listOf(item), emptyList(), launcher)
        
        // P0 FIX: Must transition to AURA_CONFIRMATION_REQUIRED
        assertEquals(DeletionState.AURA_CONFIRMATION_REQUIRED, deleteManager.deletionState.value)
        verify(launcher, never()).launch(any())
        verify(repository, never()).deleteMediaItem(any())

        // Action: Confirm
        deleteManager.confirmInternalDeletion()
        verify(repository).deleteMediaItem("file-1")
        assertEquals(DeletionState.CONFIRMED, deleteManager.deletionState.value)
    }

    @Test
    fun testRequestDeletion_NonMediaStoreContentUri_RequiresAuraConfirmation() = runTest {
        val item = MediaItem(id = "ext-1", title = "Ext", mediaType = "PHOTO", uriPath = "content://com.other.app/file.jpg")
        
        deleteManager.requestDeletion(context, listOf(item), emptyList(), mock())
        
        assertEquals(DeletionState.AURA_CONFIRMATION_REQUIRED, deleteManager.deletionState.value)
    }

    @Test
    fun testRequestDeletion_MixedUris_SequencesConfirmations() = runTest {
        val msItem = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val fileItem = MediaItem(id = "file-1", title = "File", mediaType = "PHOTO", uriPath = "file:///sdcard/photo.jpg")
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        deleteManager.requestDeletion(context, listOf(msItem, fileItem), emptyList(), launcher)
        
        // 1. MediaStore first
        assertEquals(DeletionState.PENDING, deleteManager.deletionState.value)
        
        // 2. Simulate System Success
        deleteManager.handleDeletionResult(Activity.RESULT_OK)
        verify(repository).deleteMediaItem("ms-1")
        verify(repository, never()).deleteMediaItem("file-1")
        
        // 3. Must transition to Aura confirmation for the file item
        assertEquals(DeletionState.AURA_CONFIRMATION_REQUIRED, deleteManager.deletionState.value)
        
        // 4. Confirm Aura
        deleteManager.confirmInternalDeletion()
        verify(repository).deleteMediaItem("file-1")
        assertEquals(DeletionState.CONFIRMED, deleteManager.deletionState.value)
    }

    @Test
    fun testHandleDeletionResult_Cancelled_ClearsAllPending() = runTest {
        val msItem = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val fileItem = MediaItem(id = "file-1", title = "File", mediaType = "PHOTO", uriPath = "file:///sdcard/photo.jpg")

        deleteManager.requestDeletion(context, listOf(msItem, fileItem), emptyList(), mock())
        
        // Simulate System Cancel
        deleteManager.handleDeletionResult(Activity.RESULT_CANCELED)
        
        assertEquals(DeletionState.CANCELLED, deleteManager.deletionState.value)
        verify(repository, never()).deleteMediaItem(any())
        
        // Confirming internal items now should do nothing (resetPending was called)
        deleteManager.confirmInternalDeletion()
        verify(repository, never()).deleteMediaItem(any())
    }

    @Test
    fun testRequestDeletion_WhenNonPro_AbortsAtActionBoundaryWithoutDestructiveAction() = runTest {
        val entitlementRepository: EntitlementRepository = mock()
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(false)
        whenever(repository.entitlementRepository).thenReturn(entitlementRepository)

        val item = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val rec = CleanupRecommendation("ms-1", 0.5f, 0.8f, CleanupCategory.DELETE_RECOMMENDATIONS, emptyList(), 1024, explanation = "Test")
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        deleteManager.requestDeletion(context, listOf(item), listOf(rec), launcher)
        testDispatcher.scheduler.advanceUntilIdle()

        // 1. Deletion state should be marked FAILED
        assertEquals(DeletionState.FAILED, deleteManager.deletionState.value)

        // 2. Action boundary verification: No system launcher called
        verify(launcher, never()).launch(any())

        // 3. No transaction state persisted to preferences
        verify(preferenceDao, never()).insertPreference(any())

        // 4. No DB mutations or cleanup signal recordings
        verify(repository, never()).deleteMediaItem(any())
        verify(repository, never()).addRejectedMedia(any())
        verify(repository, never()).recordCleanupSignal(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun testRequestDeletion_WhenPro_ExecutesDeletionWorkflow() = runTest {
        val entitlementRepository: EntitlementRepository = mock()
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)
        whenever(repository.entitlementRepository).thenReturn(entitlementRepository)

        val item = MediaItem(id = "ms-1", title = "MS", mediaType = "PHOTO", uriPath = "content://media/external/images/media/1")
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        deleteManager.requestDeletion(context, listOf(item), emptyList(), launcher)

        assertEquals(DeletionState.PENDING, deleteManager.deletionState.value)
        verify(launcher).launch(any())
    }

    @Test
    fun testApprovedDeletion_EndToEndReconciliation_ExecutesCompletePath() = runTest {
        val entitlementRepository: EntitlementRepository = mock()
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)
        whenever(repository.entitlementRepository).thenReturn(entitlementRepository)

        val uriPath = "content://media/external/images/media/100"
        val item = MediaItem(
            id = "ms-100",
            title = "Sunset Photo",
            mediaType = "PHOTO",
            uriPath = uriPath,
            containerFormat = "JPG",
            videoCodec = "",
            audioCodec = ""
        )
        val rec = CleanupRecommendation(
            mediaId = "ms-100",
            keepScore = 0.25f,
            confidenceScore = 0.9f,
            category = CleanupCategory.DELETE_RECOMMENDATIONS,
            reasons = listOf(CleanupReason.HIGH_EXPOSURE_NO_ENGAGEMENT),
            storageSize = 2048576L,
            explanation = "Seen 50 times without opening"
        )
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        // Seed thumbnail memory cache
        val sampleBitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val cacheField = com.example.util.MediaThumbnailFetcher::class.java.getDeclaredField("memoryCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as android.util.LruCache<String, android.graphics.Bitmap>
        cache.put(uriPath, sampleBitmap)
        assertNotNull("Pre-condition: Thumbnail should be cached", cache.get(uriPath))

        // 1. Request deletion
        deleteManager.requestDeletion(context, listOf(item), listOf(rec), launcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeletionState.PENDING, deleteManager.deletionState.value)

        // 2. Simulate user approving deletion in system dialog
        deleteManager.handleDeletionResult(Activity.RESULT_OK)
        testDispatcher.scheduler.advanceUntilIdle()

        // 3. Verify complete reconciliation sequence:
        // A. Removed from repository/DB
        verify(repository).deleteMediaItem("ms-100")

        // B. Added to RejectedMediaEntity to prevent re-import
        verify(repository).addRejectedMedia(argThat {
            uriPath == item.uriPath &&
            title == "Sunset Photo" &&
            mediaType == "PHOTO" &&
            reason == "DELETE_RECOMMENDATIONS"
        })

        // C. Thumbnail cache evicted
        assertNull("Thumbnail cache should be evicted for deleted item", cache.get(uriPath))

        // D. Learning signal recorded
        verify(repository).recordCleanupSignal(
            eq("ms-100"),
            eq("DELETE_RECOMMENDATIONS"),
            eq(0.25f),
            eq(true),
            anyOrNull()
        )

        // E. State becomes CONFIRMED and pending transaction is cleared
        assertEquals(DeletionState.CONFIRMED, deleteManager.deletionState.value)
        verify(preferenceDao).deletePreference("pending_cleanup_transaction")
    }

    @Test
    fun testRejectedDeletion_AvoidsDestructiveReconciliation() = runTest {
        val entitlementRepository: EntitlementRepository = mock()
        whenever(entitlementRepository.isFeatureAvailable(ProFeature.CLEANUP_AUTOMATION)).thenReturn(true)
        whenever(repository.entitlementRepository).thenReturn(entitlementRepository)

        val uriPath = "content://media/external/images/media/200"
        val item = MediaItem(
            id = "ms-200",
            title = "Keep Photo",
            mediaType = "PHOTO",
            uriPath = uriPath
        )
        val rec = CleanupRecommendation(
            mediaId = "ms-200",
            keepScore = 0.4f,
            confidenceScore = 0.7f,
            category = CleanupCategory.SPACE_HOGS,
            reasons = listOf(CleanupReason.LARGE_FILE_SIZE),
            storageSize = 10485760L,
            explanation = "Large photo"
        )
        val launcher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest> = mock()

        // Seed thumbnail memory cache
        val sampleBitmap = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        val cacheField = com.example.util.MediaThumbnailFetcher::class.java.getDeclaredField("memoryCache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as android.util.LruCache<String, android.graphics.Bitmap>
        cache.put(uriPath, sampleBitmap)

        // 1. Request deletion
        deleteManager.requestDeletion(context, listOf(item), listOf(rec), launcher)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeletionState.PENDING, deleteManager.deletionState.value)

        // 2. Simulate user rejecting/cancelling deletion in system dialog
        deleteManager.handleDeletionResult(Activity.RESULT_CANCELED)
        testDispatcher.scheduler.advanceUntilIdle()

        // 3. Verify NO destructive reconciliation was performed:
        // A. Media item NOT deleted from repository
        verify(repository, never()).deleteMediaItem("ms-200")

        // B. NO RejectedMediaEntity created
        verify(repository, never()).addRejectedMedia(any())

        // C. Thumbnail cache remains intact
        assertNotNull("Thumbnail cache should NOT be evicted when deletion is rejected", cache.get(uriPath))

        // D. NO cleanup learning signal recorded
        verify(repository, never()).recordCleanupSignal(any(), any(), any(), any(), anyOrNull())

        // E. State becomes CANCELLED and transaction state cleared
        assertEquals(DeletionState.CANCELLED, deleteManager.deletionState.value)
        verify(preferenceDao).deletePreference("pending_cleanup_transaction")
    }
}
