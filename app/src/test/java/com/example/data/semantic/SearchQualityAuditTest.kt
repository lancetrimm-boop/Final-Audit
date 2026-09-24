package com.example.data.semantic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.data.db.*
import com.example.data.intelligence.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchQualityAuditTest {

    private lateinit var context: Context
    private lateinit var database: AuraDatabase
    private lateinit var repository: MediaRepository
    private val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()

    private val minilmPath = "src/main/assets/models/all-minilm-l6-v2.onnx"
    private val clipTextPath = "src/main/assets/models/mobileclip_s0_text.onnx"
    private val minilmVocabPath = "src/main/assets/models/vocab.txt"
    private val clipVocabPath = "src/main/assets/models/mobileclip_vocab.json"
    private val clipMergesPath = "src/main/assets/models/mobileclip_merges.txt"

    private fun getAssetPath(path: String): String {
        return if (File(path).exists()) path else "../model_pack/$path"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java).build()
        repository = MediaRepository(dispatcher = testDispatcher)
        repository.setDatabaseForTesting(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun auditSearchScores() = runTest {
        // --- 1. MiniLM (Text-to-Title) Audit ---
        val minilmEngine = OnnxRuntimeMiniLMInferenceEngine(modelPath = getAssetPath(minilmPath))
        val minilmTokenizer = BertWordPieceTokenizer.fromVocabText(File(getAssetPath(minilmVocabPath)).readText())
        val minilmProvider = MiniLMEmbeddingProvider(minilmEngine, minilmTokenizer)
        
        val semanticRepo = RoomSemanticRepresentationRepository(database.semanticRepresentationDao())
        val retriever = DefaultSemanticCandidateRetriever(semanticRepo)
        val searchService = DefaultSemanticSearchService(minilmProvider, retriever)
        
        val items = listOf(
            MediaEntity(id = "red_title", title = "A bright red sports car", mediaType = "PHOTO"),
            MediaEntity(id = "blue_title", title = "Deep blue ocean waves", mediaType = "PHOTO"),
            MediaEntity(id = "green_title", title = "Lush green forest", mediaType = "PHOTO"),
            MediaEntity(id = "unrelated", title = "IMG_0001.jpg", mediaType = "PHOTO")
        )
        
        items.forEach { entity ->
            val result = minilmProvider.generateEmbedding(entity.id, SemanticInput.Text(entity.title), "hash")
            semanticRepo.saveRepresentation((result as EmbeddingResult.Success).representation)
        }
        
        retriever.initializeIndex(SemanticRepresentationType.CONTENT, minilmProvider.descriptor)
        
        val queries = listOf("red", "blue", "green", "water", "nature", "nature", "relaxing")
        val moreConcepts = listOf("ocean waves", "forest", "sunset", "beach", "peaceful", "landscape", "outdoors")
        
        println("\n=== MINI-LM SCORE AUDIT (Text-to-Text Semantic) ===")
        queries.distinct().forEach { query ->
            val qRes = minilmProvider.generateEmbedding("query", SemanticInput.Text(query), "h")
            val qVec = (qRes as EmbeddingResult.Success).representation.vector
            
            println("Query: '$query'")
            moreConcepts.forEach { concept ->
                val cRes = minilmProvider.generateEmbedding("concept", SemanticInput.Text(concept), "h")
                val cVec = (cRes as EmbeddingResult.Success).representation.vector
                val sim = VectorMath.cosineSimilarity(qVec, cVec)
                println("  -> Concept: '$concept', Sim: $sim")
            }
        }
        minilmEngine.close()

        // --- 2. MobileCLIP (Text-to-Visual) Audit ---
        val clipTextEngine = OnnxRuntimeMobileCLIPTextInferenceEngine(modelPath = getAssetPath(clipTextPath))
        val clipVocab = File(getAssetPath(clipVocabPath)).readText()
        val clipMerges = File(getAssetPath(clipMergesPath)).readText()
        val clipTokenizer = ClipBpeTokenizer.fromAssets(clipVocab, clipMerges)
        val clipTextProvider = MobileCLIPTextEmbeddingProvider(clipTextEngine, clipTokenizer)

        // Mock visual embeddings (Manual vector creation would be too complex, 
        // but we can use the text encoder to generate "ideal" target vectors for concepts)
        val targetConcepts = listOf("red car", "blue water", "green tree", "sunset", "person")
        val conceptVectors = targetConcepts.associateWith { concept ->
            val res = clipTextProvider.generateEmbedding("concept", SemanticInput.Text(concept, SemanticRepresentationType.VISUAL), "h")
            (res as EmbeddingResult.Success).representation.vector
        }

        println("\n=== MOBILE-CLIP SCORE AUDIT (Text-to-Visual Similarity) ===")
        queries.forEach { query ->
            val qRes = clipTextProvider.generateEmbedding("query", SemanticInput.Text(query, SemanticRepresentationType.VISUAL), "h")
            val qVec = (qRes as EmbeddingResult.Success).representation.vector
            
            println("Query: '$query'")
            targetConcepts.forEach { concept ->
                val cVec = conceptVectors[concept]!!
                val sim = VectorMath.cosineSimilarity(qVec, cVec)
                println("  -> Concept: '$concept', Sim: $sim")
            }
        }
        clipTextEngine.close()
        
        println("AURA AUDIT: Score distribution verification complete.")
    }
}
