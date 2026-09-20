package com.example.ui.screens

import com.example.ui.models.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import com.example.data.intelligence.AuraIntelligenceCore
import com.example.data.intelligence.IntelligenceResponse
import com.example.data.intelligence.IntelligenceCandidate
import com.example.data.intelligence.DecisionTraceCollector

@RunWith(RobolectricTestRunner::class)
class RegressionReplayTest {

    @Test
    fun test01_ReplayReproduction_Reproduced() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<com.example.data.intelligence.IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.CANDIDATES_RETRIEVED, "3 items found")
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.SCORING_COMPLETED, "TasteDNA alignment: 0.8")
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.RANK_ASSIGNED, "#1 position")
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.PRESENTATION_MAPPED)
            
            val items = scenario.libraryState!!.mediaItems.map { 
                IntelligenceCandidate(it, emptyList(), 1.0, 1.0f, 1.0f, "M") 
            }
            IntelligenceResponse(requestId = req.requestId, mode = req.mode, candidates = items, latencyMs = 0)
        }

        val originalResponse = mockCore.processRequest(scenario.replayRequest!!)
        val originalTrace = DecisionTraceCollector.getTrace(requestId)
        val originalSnapshot = RegressionMatrix.takeSnapshot(scenario.id, originalResponse, originalTrace)

        val entry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N", surface = "LIBRARY", status = RegressionStatus.MATCH,
            currentSnapshot = originalSnapshot
        )

        val replayResult = RegressionMatrix.replay(entry, mockCore)
        
        assertEquals(ReproductionStatus.REPRODUCED, replayResult.replayStatus)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun test02_ReplayReproduction_NotReproduced() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val originalSnapshot = RegressionMatrix.takeSnapshot(scenario.id, scenario.libraryState, null)
        val manipulatedEntry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N", surface = "LIBRARY", status = RegressionStatus.MISMATCH,
            currentSnapshot = originalSnapshot.copy(itemIds = emptyList())
        )
        
        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<com.example.data.intelligence.IntelligenceRequest>(0)
            val items = scenario.libraryState!!.mediaItems.map { 
                IntelligenceCandidate(it, emptyList(), 1.0, 1.0f, 1.0f, "P1") 
            }
            IntelligenceResponse(requestId = req.requestId, mode = req.mode, candidates = items, latencyMs = 0)
        }

        val replayResult = RegressionMatrix.replay(manipulatedEntry, mockCore)
        
        assertEquals(ReproductionStatus.NOT_REPRODUCED, replayResult.replayStatus)
        DecisionTraceCollector.clearTrace(scenario.replayRequest!!.requestId)
    }

    @Test
    fun test03_Replay_InvokesProductionPathWithCorrectFlags() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val entry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N", surface = "LIBRARY", status = RegressionStatus.MATCH,
            currentSnapshot = RegressionMatrix.takeSnapshot(scenario.id, scenario.libraryState, null)
        )

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenReturn(IntelligenceResponse(
            requestId = "r1", mode = com.example.data.intelligence.IntelligenceMode.SORT, candidates = emptyList(), latencyMs = 0
        ))

        RegressionMatrix.replay(entry, mockCore)

        val requestCaptor = argumentCaptor<com.example.data.intelligence.IntelligenceRequest>()
        verify(mockCore).processRequest(requestCaptor.capture())
        
        assertTrue(requestCaptor.firstValue.skipPersistence)
        assertNotNull(requestCaptor.firstValue.poolOverride)
    }

    @Test
    fun test04_Replay_DetectsTraceDivergence() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val originalSnapshot = RegressionMatrix.takeSnapshot(scenario.id, scenario.libraryState, null)
        val manipulatedEntry = RegressionMatrixEntry(
            scenarioId = scenario.id, scenarioName = "N", surface = "LIBRARY", status = RegressionStatus.MATCH,
            currentSnapshot = originalSnapshot.copy(traceSequence = listOf(NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "D1")))
        )

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<com.example.data.intelligence.IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name) 
            IntelligenceResponse(requestId = req.requestId, mode = req.mode, candidates = emptyList(), latencyMs = 0)
        }

        val replayResult = RegressionMatrix.replay(manipulatedEntry, mockCore)
        
        assertEquals(ReproductionStatus.NOT_REPRODUCED, replayResult.replayStatus)
        assertEquals("CANDIDATE_GENERATION_DIVERGENCE", replayResult.failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }
}
