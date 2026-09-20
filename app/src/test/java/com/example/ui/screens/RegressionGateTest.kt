package com.example.ui.screens

import com.example.ui.models.*
import com.example.data.*
import com.example.data.intelligence.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegressionGateTest {

    @Test
    fun testGate_AllUnchanged_ProducesPASS() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val expectedSnapshot = BehavioralSnapshot(
            scenarioId = scenario.id,
            surface = "LIBRARY",
            itemIds = listOf(RegressionMatrix.hashId("fixture_1"), RegressionMatrix.hashId("fixture_2"), RegressionMatrix.hashId("fixture_3")),
            topRankedId = RegressionMatrix.hashId("fixture_1"),
            traceSequence = listOf(NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED")),
            provenanceSummary = "MATCH",
            isEmpty = false
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to expectedSnapshot))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            val candidates = scenario.libraryState!!.mediaItems.map { IntelligenceCandidate(it, emptyList(), 1.0, 1.0f, 1.0f, "MATCH") }
            IntelligenceResponse(requestId = req.requestId, mode = req.mode, candidates = candidates, latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)

        assertEquals(RegressionGateResult.PASS, summary.result)
        assertEquals(1, summary.unchangedCount)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_Divergence_DifferentScore_SameType_A() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val baselineTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.SCORING_COMPLETED, itemId = "f1", score = 0.95)
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = baselineTrace, provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.SCORING_COMPLETED, metadata = mapOf("itemId" to "f1", "score" to "0.80"))
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.FAIL, summary.result)
        assertEquals("SCORING_DIVERGENCE", summary.entries.first().failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_Divergence_DifferentCount_SameType_B() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId

        val baselineTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED, count = 10)
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = baselineTrace, provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.CANDIDATES_RETRIEVED, metadata = mapOf("count" to "5"))
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.FAIL, summary.result)
        assertEquals("CANDIDATE_GENERATION_DIVERGENCE", summary.entries.first().failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_Divergence_DifferentEligibility_SameType_C() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId

        val baselineTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.ELIGIBILITY_CHECK, itemId = "f1")
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = baselineTrace, provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.ELIGIBILITY_CHECK, metadata = mapOf("itemId" to "f2"))
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.FAIL, summary.result)
        assertEquals("ELIGIBILITY_DIVERGENCE", summary.entries.first().failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_Divergence_DifferentRank_SameScore_D() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId

        val baselineTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.RANK_ASSIGNED, itemId = "f1", rank = 1)
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = baselineTrace, provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.RANK_ASSIGNED, metadata = mapOf("itemId" to "f2", "rank" to "1"))
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.FAIL, summary.result)
        assertEquals("RANKING_DIVERGENCE", summary.entries.first().failureCategory)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_VolatileDataShift_ProducesPASS() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        
        val baselineSnapshot = BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = listOf(RegressionMatrix.hashId("f1")), topRankedId = RegressionMatrix.hashId("f1"),
            traceSequence = listOf(NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED")),
            provenanceSummary = "HASH_A", isEmpty = false
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to baselineSnapshot))

        val mockCore = mock<AuraIntelligenceCore>()
        var callCount = 0
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            val hash = if (callCount == 0) "HASH_B" else "HASH_A"
            callCount++
            val items = listOf(IntelligenceCandidate(scenario.libraryState!!.mediaItems.first(), emptyList(), 1.0, 1.0f, 1.0f, hash))
            IntelligenceResponse(req.requestId, req.mode, items, latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.PASS, summary.result)
        assertEquals(ReproductionStatus.NOT_REPRODUCED, summary.entries.first().replayStatus)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_3WayIndependence_I() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId

        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = listOf(
                NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
                NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED, detail = "BASE")
            ), provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        var callCount = 0
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            DecisionTraceCollector.logEvent(req.requestId, TraceEventType.CANDIDATES_RETRIEVED, if (callCount == 0) "EVAL" else "REPLAY")
            callCount++
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        val timeline = summary.entries.first().timeline!!
        val event = timeline.events[1]
        assertEquals("BASE", event.baselineEvidence?.detail)
        assertEquals("EVAL", event.evalEvidence?.detail)
        assertEquals("REPLAY", event.replayEvidence?.detail)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_InsufficientEvidence_ReplayFailure_J() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = emptyList(), topRankedId = null,
            traceSequence = emptyList(), provenanceSummary = "", isEmpty = true
        )))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            if (req.skipPersistence) throw RuntimeException("REPLAY_CRASH")
            IntelligenceResponse("eval", IntelligenceMode.SORT, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        
        assertEquals(RegressionGateResult.INSUFFICIENT_EVIDENCE, summary.result)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_LogicRegression_ProducesFAIL() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val requestId = scenario.replayRequest!!.requestId
        val baselineSnapshot = BehavioralSnapshot(
            scenarioId = scenario.id, surface = "LIBRARY", itemIds = listOf(RegressionMatrix.hashId("f1")), topRankedId = RegressionMatrix.hashId("f1"),
            traceSequence = listOf(NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED")),
            provenanceSummary = "MATCH", isEmpty = false
        )
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to baselineSnapshot))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenAnswer { inv ->
            val req = inv.getArgument<IntelligenceRequest>(0)
            DecisionTraceCollector.startTrace(req.requestId, req.mode.name)
            IntelligenceResponse(req.requestId, req.mode, emptyList(), latencyMs = 0)
        }

        val summary = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)

        assertEquals(RegressionGateResult.FAIL, summary.result)
        DecisionTraceCollector.clearTrace(requestId)
    }

    @Test
    fun testGate_NoProductionStateMutation() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val repository = mock<MediaRepository>()
        val router = mock<RetrievalRouter>()
        
        whenever(repository.mediaItems).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(repository.tasteDNA).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA()))
        whenever(repository.intelligenceStats).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(IntelligenceStats()))
        whenever(repository.creatorProfiles).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyMap()))
        whenever(repository.preferenceProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(TasteDNA.PreferenceProfile()))
        whenever(repository.signatureStyleProfile).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())))
        
        val core = AuraIntelligenceCore(repository, router)

        RegressionMatrix.runGate(listOf(scenario), core, null)

        verify(repository, never()).reportPerformance(any())
    }

    @Test
    fun testGate_DeterministicClassification() = runTest {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val baselineSnapshot = RegressionMatrix.takeSnapshot(scenario.id, scenario.libraryState, null)
        val baseline = RegressionBaseline("b1", 1L, mapOf(scenario.id to baselineSnapshot))

        val mockCore = mock<AuraIntelligenceCore>()
        whenever(mockCore.processRequest(any())).thenReturn(IntelligenceResponse(
            requestId = "r1", mode = IntelligenceMode.SORT, candidates = emptyList(), latencyMs = 0
        ))

        val summary1 = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)
        val summary2 = RegressionMatrix.runGate(listOf(scenario), mockCore, baseline)

        assertEquals(summary1.result, summary2.result)
        assertEquals(summary1.reproducedCount, summary2.reproducedCount)
    }
}
