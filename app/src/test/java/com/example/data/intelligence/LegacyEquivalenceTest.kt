package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class LegacyEquivalenceTest {

    @Test
    fun testSearchEquivalence() = runBlocking {
        val descriptor = EmbeddingModelDescriptor("test-model", 1, 128, SemanticRepresentationType.CONTENT)

        // 1. Setup Retrieval Logic
        val keywordItems = listOf(
            RankedChannelItem("item_shared", 0.95f, 1),
            RankedChannelItem("item_lex_2", 0.80f, 2)
        )
        val semanticItems = listOf(
            SemanticRetrievalCandidate("item_sem_1", "rep_1", 0.92f, SemanticRepresentationType.CONTENT, descriptor, 1.0f),
            SemanticRetrievalCandidate("item_shared", "rep_2", 0.85f, SemanticRepresentationType.CONTENT, descriptor, 1.0f)
        )

        val fakeLexical = object : LexicalCandidateRetriever {
            override suspend fun retrieveKeywordCandidates(query: String, topK: Int): List<RankedChannelItem> = keywordItems
        }

        val fakeSemanticService = object : SemanticSearchService, SemanticRetrievalProvider {
            override suspend fun search(query: String, topK: Int, minSimilarity: Float, targetType: SemanticRepresentationType, expectedDescriptor: EmbeddingModelDescriptor?): SemanticSearchResult =
                SemanticSearchResult(query, semanticItems, descriptor, targetType, 1L, 10)

            override suspend fun search(qv: FloatArray, ql: String, k: Int, s: Float, tt: SemanticRepresentationType, ed: EmbeddingModelDescriptor?) = 
                SemanticSearchResult(ql, emptyList(), descriptor, tt, 1L, 0)
            
            override fun isReady(): Boolean = true
            override fun getIndexSize(tt: SemanticRepresentationType, ed: EmbeddingModelDescriptor?): Int = 10

            override suspend fun retrieveSemanticCandidates(query: String, topK: Int, minSimilarity: Float): List<RankedChannelItem> {
                 return semanticItems.mapIndexed { i, c -> 
                     RankedChannelItem(c.mediaId, c.similarityScore, i + 1)
                 }
            }
        }

        // 2. Mock Repository
        val repository = mock(MediaRepository::class.java)
        val item1 = MediaItem(id = "item_shared", title = "Shared", mediaType = "PHOTO")
        val item2 = MediaItem(id = "item_lex_2", title = "Lex 2", mediaType = "PHOTO")
        val item3 = MediaItem(id = "item_sem_1", title = "Sem 1", mediaType = "PHOTO")
        
        `when`(repository.getMediaItemById("item_shared")).thenReturn(item1)
        `when`(repository.getMediaItemById("item_lex_2")).thenReturn(item2)
        `when`(repository.getMediaItemById("item_sem_1")).thenReturn(item3)
        `when`(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        `when`(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        `when`(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))

        // 3. Execution
        // Legacy (Simplified for test comparison)
        val legacyFusion = ReciprocalRankFusion.fuse(mapOf(
            SearchChannel.KEYWORD to keywordItems,
            SearchChannel.SEMANTIC_CONTENT to semanticItems.mapIndexed { i, c -> RankedChannelItem(c.mediaId, c.similarityScore, i + 1) }
        ))
        
        // Core
        val core = AuraIntelligenceCore(
            repository = repository,
            retrievalRouter = RetrievalRouter(fakeLexical, fakeSemanticService, null)
        )
        val coreRequest = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = "query",
            tasteDNA = TasteDNA(isFineTuningEnabled = true),
            stats = IntelligenceStats(),
            creatorProfiles = emptyMap()
        )
        val coreResponse = core.processRequest(coreRequest)

        // 4. Comparison
        val legacyIds = legacyFusion.map { it.mediaId }
        val coreIds = coreResponse.candidates.map { it.item.id }
        
        assertEquals("Set of candidates should match", legacyIds.toSet(), coreIds.toSet())
        assertEquals("Top candidate should remain identical under neutral personalization", legacyIds[0], coreIds[0])
    }

    @Test
    fun testSortExecution() = runBlocking {
        // Mock Repository with some items
        val repository = mock(MediaRepository::class.java)
        val tasteDNA = TasteDNA(isFineTuningEnabled = true)
        
        val item1 = MediaItem(id = "item1", title = "A", mediaType = "PHOTO", rating = 5f, viewCount = 10, compatibilityStatus = CompatibilityStatus.PLAYABLE)
        val item2 = MediaItem(id = "item2", title = "B", mediaType = "PHOTO", rating = 0f, viewCount = 0, compatibilityStatus = CompatibilityStatus.PLAYABLE)
        val items = listOf(item1, item2)
        
        `when`(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(items))
        `when`(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(tasteDNA))
        `when`(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        `when`(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        `when`(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        `when`(repository.getMediaItemById("item1")).thenReturn(item1)
        `when`(repository.getMediaItemById("item2")).thenReturn(item2)

        val core = AuraIntelligenceCore(repository, mock(RetrievalRouter::class.java))
        
        val coreRequest = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "PERSONALIZED",
            limit = 10,
            tasteDNA = tasteDNA
        )
        
        val response = core.processRequest(coreRequest)
        assertTrue(response.isSuccess)
        assertEquals(2, response.candidates.size)
        // High rated item should be first
        assertEquals("item1", response.candidates[0].item.id)
    }
}
