package com.example.data.intelligence

import com.example.data.*
import com.example.data.semantic.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuraIntelligenceCoreCalibrationTest {

    private lateinit var core: AuraIntelligenceCore
    private lateinit var repository: MediaRepository
    private val retrievalRouter: RetrievalRouter = mock()
    private val reranker: MultimodalReranker = mock()

    @Before
    fun setup() {
        repository = mock()
        core = AuraIntelligenceCore(repository, retrievalRouter, reranker)
        
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        whenever(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        whenever(repository.signatureStyleProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))
        whenever(repository.isItemVisibleInLibrary(any())).thenReturn(true)
    }

    @Test
    fun testPrecisionGate_Accepts011_ForTextOnlySearch() = runTest {
        val query = "water"
        val request = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = query,
            visualVector = FloatArray(512), // Text-derived vector
            limit = 10,
            requestId = "test-11"
        )

        val mediaId = "vid_water"
        val mockItem = MediaItem(id = mediaId, title = "Ocean waves", mediaType = "VIDEO")
        whenever(repository.getMediaItemById(mediaId)).thenReturn(mockItem)

        val channelResults = mapOf(
            SearchChannel.SEMANTIC_CONTENT to listOf(RankedChannelItem(mediaId, 0.11f, 1))
        )
        whenever(retrievalRouter.retrieve(any())).thenReturn(channelResults)
        
        whenever(reranker.rerank(any(), anyOrNull(), anyOrNull(), any())).thenReturn(listOf(
            HybridCandidate(mediaId, 1.0, mapOf(SearchChannel.SEMANTIC_CONTENT to 1), mapOf(SearchChannel.SEMANTIC_CONTENT to 0.11f))
        ))

        val response = core.processRequest(request)
        assertTrue("Candidate with 0.11 should pass for text search", response.candidates.any { it.item.id == mediaId })
    }

    @Test
    fun testPrecisionGate_Rejects009_ForTextOnlySearch() = runTest {
        val query = "water"
        val request = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            query = query,
            visualVector = FloatArray(512),
            limit = 10,
            requestId = "test-09"
        )

        val mediaId = "vid_noise"
        val mockItem = MediaItem(id = mediaId, title = "Noise", mediaType = "VIDEO")
        whenever(repository.getMediaItemById(mediaId)).thenReturn(mockItem)

        val channelResults = mapOf(
            SearchChannel.SEMANTIC_CONTENT to listOf(RankedChannelItem(mediaId, 0.09f, 1))
        )
        whenever(retrievalRouter.retrieve(any())).thenReturn(channelResults)
        
        whenever(reranker.rerank(any(), anyOrNull(), anyOrNull(), any())).thenReturn(listOf(
            HybridCandidate(mediaId, 1.0, mapOf(SearchChannel.SEMANTIC_CONTENT to 1), mapOf(SearchChannel.SEMANTIC_CONTENT to 0.09f))
        ))

        val response = core.processRequest(request)
        assertTrue("Candidate with 0.09 should be rejected for text search", response.candidates.isEmpty())
    }

    @Test
    fun testPrecisionGate_StricterForPureVisual() = runTest {
        val request = IntelligenceRequest(
            mode = IntelligenceMode.SEARCH,
            visualVector = FloatArray(512), // Reference vector (no query string)
            limit = 10,
            requestId = "test-visual"
        )

        val mediaId = "vid_visual_match"
        val mockItem = MediaItem(id = mediaId, title = "Visual Match", mediaType = "VIDEO")
        whenever(repository.getMediaItemById(mediaId)).thenReturn(mockItem)

        val channelResults = mapOf(
            SearchChannel.SEMANTIC_VISUAL to listOf(RankedChannelItem(mediaId, 0.34f, 1))
        )
        whenever(retrievalRouter.retrieve(any())).thenReturn(channelResults)
        
        whenever(reranker.rerank(any(), anyOrNull(), anyOrNull(), any())).thenReturn(listOf(
            HybridCandidate(mediaId, 1.0, mapOf(SearchChannel.SEMANTIC_VISUAL to 1), mapOf(SearchChannel.SEMANTIC_VISUAL to 0.34f))
        ))

        val response = core.processRequest(request)
        assertTrue("Candidate with 0.34 should still be rejected for pure visual search", response.candidates.isEmpty())
    }
}
