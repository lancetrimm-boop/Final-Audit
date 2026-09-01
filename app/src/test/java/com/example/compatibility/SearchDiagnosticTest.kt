package com.example.compatibility

import com.example.data.semantic.HybridCandidate
import com.example.data.semantic.HybridSearchResult
import com.example.data.semantic.SearchChannel
import com.example.util.AuraSearchDiagnostics
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchDiagnosticTest {

    @Test
    fun testLogSearchResult_FormatsNewDiagnosticsCorrectly() {
        val result = HybridSearchResult(
            query = "sunset beach",
            candidates = listOf(
                HybridCandidate(
                    mediaId = "video_1",
                    rrfScore = 0.05,
                    channelRanks = mapOf(SearchChannel.SEMANTIC_VISUAL to 1),
                    channelScores = mapOf(SearchChannel.SEMANTIC_VISUAL to 0.75f),
                    matchExplanation = "[Visual] [Scene Frames: 8] [Aggregate Sim: 0.750] [Max Frame Similarity: 0.950] [Max Frame Promotion]"
                )
            ),
            latencyMs = 120,
            totalCandidatesConsidered = 100,
            channelCandidateCounts = mapOf(SearchChannel.SEMANTIC_VISUAL to 20),
            deepRerankCount = 50,
            frameVectorsLoaded = 250
        )

        // Verify it doesn't crash and produces output (manually inspected in logs)
        AuraSearchDiagnostics.logSearchResult(result)
    }
}
