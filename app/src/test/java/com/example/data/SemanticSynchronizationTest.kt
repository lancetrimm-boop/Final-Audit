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
class SemanticSynchronizationTest {

    private lateinit var context: Context
    private lateinit var database: AuraDatabase
    private lateinit var repository: MediaRepository
    private val testDispatcher = StandardTestDispatcher()
    
    private val descriptor = EmbeddingModelDescriptor(
        modelId = "test", modelVersion = 1, dimensionality = 384, primaryType = SemanticRepresentationType.CONTENT
    )

    private lateinit var semanticRepo: SemanticRepresentationRepository
    private lateinit var retriever: SemanticCandidateRetriever
    private lateinit var fakeEmbeddingProvider: FakeEmbeddingProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        repository = MediaRepository(dispatcher = testDispatcher)
        repository.setApplicationContextForTesting(context)
        repository.setDatabaseForTesting(database)

        semanticRepo = RoomSemanticRepresentationRepository(database.semanticRepresentationDao())
        fakeEmbeddingProvider = FakeEmbeddingProvider(descriptor)
        
        // Provide test dispatcher to retriever for determinism
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
        database.close()
    }

    @Test
    fun `test addition synchronization`() = runTest(testDispatcher) {
        val item = MediaEntity(id = "m1", title = "New Image", mediaType = "PHOTO", uriPath = "/path/1.jpg")
        database.mediaDao().insert(item)
        
        // Re-sync items in repo
        repository.scanLocalMedia(context)
        advanceUntilIdle()
        
        // Trigger manual processing which should trigger semantic indexing
        repository.processPendingMedia(context, 1L, setOf("external"), isManual = true)
        advanceUntilIdle()
        
        // Verify index has the item
        assertEquals("Index should have 1 item after ingestion", 1, retriever.getIndexSize(SemanticRepresentationType.CONTENT, descriptor))
    }

    @Test
    fun `test deletion synchronization`() = runTest(testDispatcher) {
        // Setup initial item
        val item = MediaEntity(id = "m1", title = "To Delete", mediaType = "PHOTO", uriPath = "/path/1.jpg")
        database.mediaDao().insert(item)
        repository.scanLocalMedia(context)
        repository.processPendingMedia(context, 1L, setOf("external"), isManual = true)
        advanceUntilIdle()
        
        assertEquals(1, retriever.getIndexSize(SemanticRepresentationType.CONTENT, descriptor))

        // Delete via repository
        repository.deleteMediaItem("m1")
        advanceUntilIdle()
        
        // Verify index is empty
        assertEquals("Index should be empty after deletion", 0, retriever.getIndexSize(SemanticRepresentationType.CONTENT, descriptor))
    }

    private class FakeEmbeddingProvider(override val descriptor: EmbeddingModelDescriptor) : EmbeddingProvider {
        override val supportedTypes = setOf(SemanticRepresentationType.CONTENT)
        override fun isReady() = true
        override suspend fun generateEmbedding(mediaId: String, input: SemanticInput, sourceDataHash: String): EmbeddingResult {
            return EmbeddingResult.Success(SemanticRepresentation(
                id = "rep_$mediaId", mediaId = mediaId, type = SemanticRepresentationType.CONTENT,
                modelDescriptor = descriptor, dimensionality = 384,
                vector = FloatArray(384) { 0.1f }, sourceDataHash = sourceDataHash
            ))
        }
        override fun close() {}
    }
}
