package com.example.ui.screens

import com.example.data.*
import com.example.data.intelligence.*
import com.example.ui.models.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegressionPathFaithfulnessTest {

    @Test
    fun test02_Replay_GeneratesFreshTrace() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val entry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N", surface = "L", status = RegressionStatus.MATCH
        )
        
        val repository = mock<MediaRepository>()
        val router = mock<RetrievalRouter>()
        
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        whenever(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        whenever(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.signatureStyleProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))
        
        val core = AuraIntelligenceCore(repository, router)
        
        val result = RegressionMatrix.replay(entry, core)
        
        assertNotNull(result.replaySnapshot)
        assertTrue(result.replaySnapshot!!.traceSequence.any { it.type == TraceEventType.REQUEST_RECEIVED })
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun test03_DivergenceDiagnosis_Ranking() = runTest {
        val scenario = MirrorFixtures.scenarios.first()
        val requestId = scenario.replayRequest!!.requestId
        
        val original = BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = listOf("1", "2"), topRankedId = "1",
            traceSequence = emptyList(), provenanceSummary = "P1", isEmpty = false
        )
        
        val entry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N1", surface = "LIBRARY", status = RegressionStatus.MISMATCH,
            currentSnapshot = original
        )
        
        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<com.example.data.intelligence.IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            IntelligenceResponse(requestId = req.requestId, mode = req.mode, candidates = emptyList(), latencyMs = 0)
        }
        
        val result = RegressionMatrix.replay(entry, mockCore)
        
        assertEquals(ReproductionStatus.NOT_REPRODUCED, result.replayStatus)
        // Original trace was empty, replay has REQUEST_RECEIVED.
        assertEquals("CANDIDATE_GENERATION_DIVERGENCE", result.failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }
}
