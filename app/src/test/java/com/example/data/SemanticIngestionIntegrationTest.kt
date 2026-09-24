package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AuraDatabase
import com.example.data.db.MediaEntity
import com.example.data.semantic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SemanticIngestionIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AuraDatabase
    private lateinit var repository: MediaRepository
    private val testDispatcher = StandardTestDispatcher()
    
    private val descriptor = EmbeddingModelDescriptor(
        modelId = "test", modelVersion = 1, dimensionality = 384, primaryType = SemanticRepresentationType.CONTENT
    )

    private lateinit var fakeEmbeddingProvider: FakeEmbeddingProvider
    private lateinit var retriever: SemanticCandidateRetriever

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        repository = MediaRepository(dispatcher = testDispatcher)
        repository.setApplicationContextForTesting(context)
        repository.setDatabaseForTesting(database)

        val semanticRepo = RoomSemanticRepresentationRepository(database.semanticRepresentationDao())
        fakeEmbeddingProvider = FakeEmbeddingProvider(descriptor)
        
        // Use test scope for retriever
        retriever = DefaultSemanticCandidateRetriever(semanticRepo, testDispatcher)
        val indexingService = DefaultSemanticIndexingService(fakeEmbeddingProvider, retriever, semanticRepo, testDispatcher)

        // Inject semantic components
        val fields = mapOf(
            "semanticRepresentationRepository" to semanticRepo,
            "embeddingProvider" to fakeEmbeddingProvider,
            "semanticIndexingService" to indexingService,
            "semanticCandidateRetriever" to retriever
        )
        
        val repoClass = MediaRepository::class.java
        fields.forEach { (name, value) ->
            val field = repoClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(repository, value)
        }
    }

    @After
    fun tearDown() {
        if (::repository.isInitialized) {
            repository.close()
        }
        database.close()
    }

    @Test
    fun `test processPendingMedia triggers embedding generation and persistence`() = runTest(testDispatcher) {
        val item = MediaEntity(id = "m1", title = "Sunrise", mediaType = "PHOTO", uriPath = "/path/1.jpg")
        database.mediaDao().insert(item)
        
        // Sync repo items
        repository.scanLocalMedia(context)
        advanceUntilIdle()

        repository.processPendingMedia(context, 1L, setOf("external"), isManual = true)
        advanceUntilIdle()

        val representations = database.semanticRepresentationDao().getByType(SemanticRepresentationType.CONTENT.name)
        assertEquals(1, representations.size)
        assertEquals("m1", representations[0].mediaId)
        assertEquals(1, retriever.getIndexSize(SemanticRepresentationType.CONTENT, descriptor))
    }

    @Test
    fun `test semantic failure does not abort media ingestion`() = runTest(testDispatcher) {
        fakeEmbeddingProvider.shouldFail = true
        
        val item = MediaEntity(id = "m1", title = "Sunrise", mediaType = "PHOTO", uriPath = "/path/1.jpg")
        database.mediaDao().insert(item)
        
        repository.scanLocalMedia(context)
        advanceUntilIdle()

        repository.processPendingMedia(context, 1L, setOf("external"), isManual = true)
        advanceUntilIdle()

        // Media should still be in DB
        val dbItem = database.mediaDao().getMediaById("m1")
        assertNotNull(dbItem)
        
        // But no semantic rep
        val representations = database.semanticRepresentationDao().getByType(SemanticRepresentationType.CONTENT.name)
        assertEquals(0, representations.size)
    }

    @Test
    fun `test multiple items with partial semantic failure`() = runTest(testDispatcher) {
        val item1 = MediaEntity(id = "m1", title = "Success", mediaType = "PHOTO", uriPath = "/path/1.jpg")
        val item2 = MediaEntity(id = "m2", title = "Fail", mediaType = "PHOTO", uriPath = "/path/2.jpg")
        database.mediaDao().insert(item1)
        database.mediaDao().insert(item2)
        
        repository.scanLocalMedia(context)
        advanceUntilIdle()

        // Mock failure for item2 only
        fakeEmbeddingProvider.failId = "m2"

        repository.processPendingMedia(context, 1L, setOf("external"), isManual = true)
        advanceUntilIdle()

        // One success, one failure
        val representations = database.semanticRepresentationDao().getByType(SemanticRepresentationType.CONTENT.name)
        assertEquals(1, representations.size)
        assertEquals("m1", representations[0].mediaId)
    }

    private class FakeEmbeddingProvider(override val descriptor: EmbeddingModelDescriptor) : EmbeddingProvider {
        var shouldFail = false
        var failId: String? = null
        override val supportedTypes = setOf(SemanticRepresentationType.CONTENT)
        override fun isReady() = true
        override suspend fun generateEmbedding(mediaId: String, input: SemanticInput, sourceDataHash: String): EmbeddingResult {
            if (shouldFail || mediaId == failId) {
                return EmbeddingResult.Failure(EmbeddingErrorCode.INFERENCE_ERROR, "Mock failure")
            }
            return EmbeddingResult.Success(SemanticRepresentation(
                id = "rep_$mediaId", mediaId = mediaId, type = SemanticRepresentationType.CONTENT,
                modelDescriptor = descriptor, dimensionality = 384,
                vector = FloatArray(384) { 0.1f }, sourceDataHash = sourceDataHash
            ))
        }
        override fun close() {}
    }
}
