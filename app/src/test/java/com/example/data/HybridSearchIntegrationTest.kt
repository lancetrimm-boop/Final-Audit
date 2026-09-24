package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AuraDatabase
import com.example.data.db.MediaEntity
import com.example.data.semantic.*
import com.example.data.intelligence.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HybridSearchIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: AuraDatabase
    private lateinit var repository: MediaRepository
    private val testDispatcher = StandardTestDispatcher()
    
    private val descriptor = EmbeddingModelDescriptor(
        modelId = "test-model",
        modelVersion = 1,
        dimensionality = 384,
        primaryType = SemanticRepresentationType.CONTENT
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        repository = MediaRepository(dispatcher = testDispatcher)
        repository.setApplicationContextForTesting(context)
        repository.setDatabaseForTesting(database)

        // Manually setup semantic stack for integration testing
        val semanticRepo = RoomSemanticRepresentationRepository(database.semanticRepresentationDao())
        val mockProvider = object : EmbeddingProvider {
            override val descriptor = this@HybridSearchIntegrationTest.descriptor
            override val supportedTypes = setOf(SemanticRepresentationType.CONTENT)
            override fun isReady() = true
            override suspend fun generateEmbedding(mediaId: String, input: SemanticInput, sourceDataHash: String): EmbeddingResult {
                return EmbeddingResult.Success(SemanticRepresentation(
                    id = "query_rep", mediaId = mediaId, type = SemanticRepresentationType.CONTENT,
                    modelDescriptor = descriptor, dimensionality = 384,
                    vector = FloatArray(384) { 0.1f }, sourceDataHash = sourceDataHash
                ))
            }
            override fun close() {}
        }

        // Use test scope for retriever to ensure determinism
        val retriever = DefaultSemanticCandidateRetriever(semanticRepo, testDispatcher)
        val semanticService = DefaultSemanticSearchService(mockProvider, retriever)
        
        val repoClass = MediaRepository::class.java
        val lexicalClassName = "${repoClass.name}\$ProductionLexicalRetriever"
        val lexicalClass = Class.forName(lexicalClassName)
        val lexicalConstructor = lexicalClass.getDeclaredConstructor(repoClass)
        lexicalConstructor.isAccessible = true
        val lexicalRetriever = lexicalConstructor.newInstance(repository) as LexicalCandidateRetriever
        
        val core = AuraIntelligenceCore(
            repository = repository,
            retrievalRouter = RetrievalRouter(
                lexicalRetriever = lexicalRetriever,
                semanticProvider = semanticService,
                visualProvider = null
            )
        )
        val hybridEngine = DefaultHybridSearchEngine(core)

        // Inject all components
        val fields = mapOf(
            "semanticRepresentationRepository" to semanticRepo,
            "embeddingProvider" to mockProvider,
            "semanticCandidateRetriever" to retriever,
            "semanticSearchService" to semanticService,
            "intelligenceCore" to core,
            "hybridSearchEngine" to hybridEngine
        )
        
        fields.forEach { (name, value) ->
            val field = repoClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(repository, value)
        }

        val mockTextProvider: MobileCLIPTextEmbeddingProvider = mock()
        whenever(mockTextProvider.isReady()).thenReturn(true)
        runBlocking {
            whenever(mockTextProvider.generateEmbedding(any(), any(), any())).thenReturn(
                EmbeddingResult.Success(SemanticRepresentation(
                    id = "query", mediaId = "query", type = SemanticRepresentationType.VISUAL,
                    modelDescriptor = descriptor, dimensionality = 384,
                    vector = FloatArray(384) { 0.1f },
                    sourceDataHash = "query"
                ))
            )
        }
        val textProviderField = repoClass.getDeclaredField("mobileClipTextProvider")
        textProviderField.isAccessible = true
        textProviderField.set(repository, mockTextProvider)

        val mockContentProvider: EmbeddingProvider = mock()
        whenever(mockContentProvider.descriptor).thenReturn(descriptor)
        whenever(mockContentProvider.supportedTypes).thenReturn(setOf(SemanticRepresentationType.CONTENT))
        whenever(mockContentProvider.isReady()).thenReturn(true)
        runBlocking {
            whenever(mockContentProvider.generateEmbedding(any(), any(), any())).thenReturn(
                EmbeddingResult.Success(SemanticRepresentation(
                    id = "query_content", mediaId = "query_content", type = SemanticRepresentationType.CONTENT,
                    modelDescriptor = descriptor, dimensionality = 384,
                    vector = FloatArray(384) { 0.1f },
                    sourceDataHash = "query_content"
                ))
            )
        }
        val contentField = repoClass.getDeclaredField("embeddingProvider")
        contentField.isAccessible = true
        contentField.set(repository, mockContentProvider)
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
    }

    private fun createMediaItem(id: String, title: String, dateAdded: Long = System.currentTimeMillis()): MediaItem {
        return MediaItem(
            id = id,
            title = title,
            mediaType = "PHOTO",
            compatibilityStatus = CompatibilityStatus.PLAYABLE,
            dateAdded = dateAdded
        )
    }

    @Test
    fun `test hybrid search returns fused results for valid query`() = runTest(testDispatcher) {
        val items = listOf(
            createMediaItem("m1", "Neon Light"),
            createMediaItem("m2", "Cyberpunk Vibe")
        )
        repository.setMediaItemsForTesting(items)

        // Add semantic representation for m2 that matches our test query vector
        val semanticRepo = repository.semanticRepresentationRepository!!
        val representation = SemanticRepresentation(
            id = "sem_m2", mediaId = "m2", type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor, dimensionality = 384,
            vector = FloatArray(384) { 0.1f },
            sourceDataHash = "h2"
        )
        semanticRepo.saveRepresentation(representation)
        
        // Manual index hydration for integration test (Stage 2 Fix)
        val retrieverField = repository.javaClass.getDeclaredField("semanticCandidateRetriever")
        retrieverField.isAccessible = true
        val retriever = retrieverField.get(repository) as DefaultSemanticCandidateRetriever
        
        retriever.initializeIndex(SemanticRepresentationType.CONTENT, descriptor)
        retriever.onRepresentationAdded(representation)

        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.librarySearchQuery = "cyberpunk"
        
        // Wait for debounce and parallel retrieval
        advanceUntilIdle()

        val results = repository.latestAiSortRecommendation.value
        assertNotNull("Search should return a response", results)
        assertTrue("Search should return items", results.isNotEmpty())
        assertEquals("m2", results[0].id)
        
        job.cancel()
    }

    @Test
    fun `test hybrid search includes semantic-only matches`() = runTest(testDispatcher) {
        val items = listOf(
            createMediaItem("m1", "Something Else"),
            createMediaItem("m2", "No Match In Title")
        )
        repository.setMediaItemsForTesting(items)

        val semanticRepo = repository.semanticRepresentationRepository!!
        val representation = SemanticRepresentation(
            id = "sem_m2", mediaId = "m2", type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor, dimensionality = 384,
            vector = FloatArray(384) { 0.1f }, // Matches our mock query vector 0.1f
            sourceDataHash = "h2"
        )
        semanticRepo.saveRepresentation(representation)
        
        val retrieverField = repository.javaClass.getDeclaredField("semanticCandidateRetriever")
        retrieverField.isAccessible = true
        val retriever = retrieverField.get(repository) as DefaultSemanticCandidateRetriever
        
        retriever.initializeIndex(SemanticRepresentationType.CONTENT, descriptor)
        retriever.onRepresentationAdded(representation)

        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.librarySearchQuery = "concept"
        
        advanceUntilIdle()

        val results = repository.latestAiSortRecommendation.value
        assertTrue("m2 should be found semantically", results.any { it.id == "m2" })
        
        job.cancel()
    }

    @Test
    fun `test lazy index hydration on first search`() = runTest(testDispatcher) {
        val items = listOf(createMediaItem("m1", "Title"))
        repository.setMediaItemsForTesting(items)
        
        val semanticRepo = repository.semanticRepresentationRepository!!
        semanticRepo.saveRepresentation(SemanticRepresentation(
            id = "sem1", mediaId = "m1", type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor, dimensionality = 384,
            vector = FloatArray(384) { 0.1f }, sourceDataHash = "h1"
        ))

        // Trigger search - should trigger lazy init
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        repository.librarySearchQuery = "test"
        
        advanceUntilIdle()
        
        val retrieverField = repository.javaClass.getDeclaredField("semanticCandidateRetriever")
        retrieverField.isAccessible = true
        val retriever = retrieverField.get(repository) as DefaultSemanticCandidateRetriever
        
        assertEquals("Index should be hydrated after search", 1, retriever.getIndexSize(SemanticRepresentationType.CONTENT, descriptor))
        job.cancel()
    }

    @Test
    fun `test search ignores personalized mode filters`() = runTest(testDispatcher) {
        val items = listOf(createMediaItem("m1", "Target"))
        repository.setMediaItemsForTesting(items)
        
        // Setup semantic match
        val semanticRepo = repository.semanticRepresentationRepository!!
        val representation = SemanticRepresentation(
            id = "sem1", mediaId = "m1", type = SemanticRepresentationType.CONTENT,
            modelDescriptor = descriptor, dimensionality = 384,
            vector = FloatArray(384) { 0.1f }, sourceDataHash = "h1"
        )
        semanticRepo.saveRepresentation(representation)
        val retrieverField = repository.javaClass.getDeclaredField("semanticCandidateRetriever")
        retrieverField.isAccessible = true
        val retriever = retrieverField.get(repository) as DefaultSemanticCandidateRetriever
        
        retriever.initializeIndex(SemanticRepresentationType.CONTENT, descriptor)
        retriever.onRepresentationAdded(representation)

        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.librarySearchQuery = "target"
        repository.sortCategory = SortCategory.INTELLIGENT
        
        advanceUntilIdle()

        val results = repository.latestAiSortRecommendation.value
        assertEquals(1, results.size)
        
        job.cancel()
    }

    @Test
    fun `test regression 187886 - exact filename match priority over semantic neighbors`() = runTest(testDispatcher) {
        val items = listOf(
            createMediaItem("m1", "beach_sunset.jpg"),
            createMediaItem("m2", "mountain_lake.jpg")
        )
        repository.setMediaItemsForTesting(items)
        
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)

        repository.librarySearchQuery = "beach_sunset.jpg"
        
        advanceUntilIdle()

        val results = repository.latestAiSortRecommendation.value
        assertNotNull("Results should not be null", results)
        assertFalse("Results should not be empty", results.isEmpty())
        assertEquals("m1", results[0].id)
        
        job.cancel()
    }

    @Test
    fun `test search fallback to legacy when hybrid returns zero`() = runTest(testDispatcher) {
        // Items that match keyword but NOT mock semantic vector
        val items = listOf(createMediaItem("m1", "UniqueTitle"))
        repository.setMediaItemsForTesting(items)
        
        val job = repository.latestAiSortRecommendation.onEach { }.launchIn(this)
        repository.librarySearchQuery = "UniqueTitle"
        
        advanceUntilIdle()

        val results = repository.latestAiSortRecommendation.value
        assertEquals("Should find item via legacy fallback", 1, results.size)
        
        job.cancel()
    }
}
