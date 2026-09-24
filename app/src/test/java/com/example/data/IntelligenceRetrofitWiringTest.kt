package com.example.data

import com.example.data.intelligence.*
import com.example.data.semantic.*
import com.example.ui.models.TraceEventType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class IntelligenceRetrofitWiringTest {

    private lateinit var repository: MediaRepository
    private lateinit var core: AuraIntelligenceCore
    private lateinit var router: RetrievalRouter
    
    private val lexicalRetriever: LexicalCandidateRetriever = mock()
    private val semanticProvider: SemanticRetrievalProvider = mock()
    private val visualProvider: VisualRetrievalProvider = mock()
    
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        repository = mock()
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(MutableStateFlow(TasteDNA()))
        whenever(repository.preferenceProfile).thenReturn(MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.intelligenceStats).thenReturn(MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(MutableStateFlow(emptyMap()))
        whenever(repository.signatureStyleProfile).thenReturn(MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))
        
        router = RetrievalRouter(lexicalRetriever, semanticProvider, visualProvider)
        core = AuraIntelligenceCore(repository, router, dispatcher = testDispatcher)
    }

    private fun createMediaItem(id: String): MediaItem {
        return MediaItem(
            id = id,
            title = "Item $id",
            mediaType = "PHOTO",
            year = 2024,
            duration = "",
            genre = "Media",
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )
    }

    @Test
    fun testSearchWiring_TraversesAllStages() = runTest {
        val requestId = "test_search_123"
        val query = "test query"
        val item1 = createMediaItem("1")
        
        whenever(repository.getMediaItemById("1")).thenReturn(item1)
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)
        whenever(lexicalRetriever.retrieveKeywordCandidates(eq(query), any())).thenReturn(
            listOf(RankedChannelItem("1", 100f, 1))
        )
        whenever(semanticProvider.isReady()).thenReturn(true)
        whenever(visualProvider.isReady()).thenReturn(true)

        val request = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = query,
            requestId = requestId,
            useLegacyRanking = true // Initial verified baseline uses legacy ranking
        )

        val response = core.processRequest(request)
        
        // 1. Verify Response Success
        assertTrue(response.isSuccess)
        assertEquals(1, response.candidates.size)
        assertEquals("1", response.candidates[0].item.id)

        // 2. Verify Trace Traversal
        val trace = DecisionTraceCollector.getTrace(requestId)
        assertNotNull("Trace should be collected in Developer mode", trace)
        
        val eventTypes = trace!!.events.map { it.type }
        
        assertTrue("Should log REQUEST_RECEIVED", eventTypes.contains(TraceEventType.REQUEST_RECEIVED))
        assertTrue("Should log CHANNEL_RETRIEVAL_START", eventTypes.contains(TraceEventType.CHANNEL_RETRIEVAL_START))
        assertTrue("Should log CHANNEL_RETRIEVAL_COMPLETE", eventTypes.contains(TraceEventType.CHANNEL_RETRIEVAL_COMPLETE))
        assertTrue("Should log FUSION_COMPLETED", eventTypes.contains(TraceEventType.FUSION_COMPLETED))
        assertTrue("Should log RANK_ASSIGNED", eventTypes.contains(TraceEventType.RANK_ASSIGNED))
    }

    @Test
    fun testPersonalizedSortWiring_TraversesAllStages() = runTest {
        val requestId = "test_sort_123"
        val item1 = createMediaItem("1")
        val item2 = createMediaItem("2")
        
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(listOf(item1, item2)))
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)

        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = "PERSONALIZED",
            requestId = requestId
        )

        val response = core.processRequest(request)
        
        // 1. Verify Response Success
        assertTrue(response.isSuccess)
        assertEquals(2, response.candidates.size)

        // 2. Verify Trace Traversal
        val trace = DecisionTraceCollector.getTrace(requestId)
        assertNotNull(trace)
        
        val eventTypes = trace!!.events.map { it.type }
        
        assertTrue("Should log REQUEST_RECEIVED", eventTypes.contains(TraceEventType.REQUEST_RECEIVED))
        assertTrue("Should log POOL_FILTERED", eventTypes.contains(TraceEventType.POOL_FILTERED))
        assertTrue("Should log SCORING_COMPLETED", eventTypes.contains(TraceEventType.SCORING_COMPLETED))
        assertTrue("Should log RANK_ASSIGNED", eventTypes.contains(TraceEventType.RANK_ASSIGNED))
    }

    @Test
    fun testDiscoverSortWiring_PopulatesSecondaryScore() = runTest {
        val item1 = createMediaItem("1")
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(listOf(item1)))
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)

        val request = IntelligenceRequest(
            mode = IntelligenceMode.DISCOVER,
            sortOption = "FRESH_FOR_YOU",
            limit = 1
        )

        val response = core.processRequest(request)
        
        assertTrue(response.isSuccess)
        assertEquals(1, response.candidates.size)
        // Verify secondaryEvidenceScore is populated (should be explorationScore > 0 in this mock setup)
        assertTrue("Secondary score should be > 0", response.candidates[0].secondaryEvidenceScore > 0f)
    }

    @Test
    fun testDefaultHybridSearchEngine_PreservesMetadata() = runTest {
        val engine = DefaultHybridSearchEngine(core)
        val query = "test"
        val item1 = createMediaItem("1")
        
        whenever(repository.getMediaItemById("1")).thenReturn(item1)
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)
        whenever(lexicalRetriever.retrieveKeywordCandidates(eq(query), any())).thenReturn(
            listOf(RankedChannelItem("1", 90f, 2, metadata = mapOf("test_meta" to "val")))
        )
        whenever(semanticProvider.isReady()).thenReturn(true)
        whenever(visualProvider.isReady()).thenReturn(true)

        val result = engine.search(SearchRequest.Text(query))
        
        assertTrue(result.isSuccess)
        assertEquals(1, result.candidates.size)
        val candidate = result.candidates[0]
        assertEquals("1", candidate.mediaId)
        
        // Verify channel scores and ranks are preserved via the retrofit
        assertEquals(90f, candidate.channelScores[SearchChannel.KEYWORD])
        assertEquals(2, candidate.channelRanks[SearchChannel.KEYWORD])
    }
}
