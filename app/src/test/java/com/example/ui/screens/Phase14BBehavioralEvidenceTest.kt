package com.example.ui.screens

import com.example.ui.models.*
import com.example.data.*
import org.junit.Assert.*
import org.junit.Test

class Phase14BBehavioralEvidenceTest {

    @Test
    fun testNormalizedEvent_FloatingPointNoise_DoesNotDiverge() {
        val event1 = TraceEvent(type = TraceEventType.SCORING_COMPLETED, metadata = mapOf("score" to "0.95001"))
        val event2 = TraceEvent(type = TraceEventType.SCORING_COMPLETED, metadata = mapOf("score" to "0.95002"))
        
        val norm1 = RegressionMatrix.normalizeEvent(event1)
        val norm2 = RegressionMatrix.normalizeEvent(event2)
        
        // Both round to 0.9500
        assertEquals(norm1, norm2)
    }

    @Test
    fun testNormalizedEvent_FloatingPointSignificantChange_Diverges() {
        val event1 = TraceEvent(type = TraceEventType.SCORING_COMPLETED, metadata = mapOf("score" to "0.9500"))
        val event2 = TraceEvent(type = TraceEventType.SCORING_COMPLETED, metadata = mapOf("score" to "0.9400"))
        
        val norm1 = RegressionMatrix.normalizeEvent(event1)
        val norm2 = RegressionMatrix.normalizeEvent(event2)
        
        assertNotEquals(norm1, norm2)
    }

    @Test
    fun testTimeline_IdentifiesFirstValueDivergence() {
        val scenarioId = "s1"
        val baseTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED, count = 10)
        )
        val evalTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED, count = 5)
        )
        
        val baseline = BehavioralSnapshot(scenarioId, "L", emptyList(), null, baseTrace, "", false)
        val eval = BehavioralSnapshot(scenarioId, "L", emptyList(), null, evalTrace, "", false)
        
        val timeline = RegressionMatrix.buildTimeline(scenarioId, baseline, eval, eval)
        
        assertTrue(timeline.events[1].isDivergent)
        assertEquals(1, timeline.firstDivergenceIndex)
        assertFalse(timeline.events[0].isDivergent)
    }

    @Test
    fun testTimeline_IdentifiesMissingEvent() {
        val scenarioId = "s1"
        val baseTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.POOL_FILTERED),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED)
        )
        val evalTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED)
        )
        
        val baseline = BehavioralSnapshot(scenarioId, "L", emptyList(), null, baseTrace, "", false)
        val eval = BehavioralSnapshot(scenarioId, "L", emptyList(), null, evalTrace, "", false)
        
        val timeline = RegressionMatrix.buildTimeline(scenarioId, baseline, eval, eval)
        
        // At index 1: base has POOL_FILTERED, eval has CANDIDATES_RETRIEVED
        assertTrue(timeline.events[1].isDivergent)
        assertEquals(TraceEventType.POOL_FILTERED, timeline.events[1].type)
        assertEquals(TraceEventType.CANDIDATES_RETRIEVED, timeline.events[1].evalEvidence?.type)
    }

    @Test
    fun testTimeline_IdentifiesExtraEvent() {
        val scenarioId = "s1"
        val baseTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED")
        )
        val evalTrace = listOf(
            NormalizedTraceEvent(TraceEventType.REQUEST_RECEIVED, detail = "NORMALIZED"),
            NormalizedTraceEvent(TraceEventType.CANDIDATES_RETRIEVED)
        )
        
        val baseline = BehavioralSnapshot(scenarioId, "L", emptyList(), null, baseTrace, "", false)
        val eval = BehavioralSnapshot(scenarioId, "L", emptyList(), null, evalTrace, "", false)
        
        val timeline = RegressionMatrix.buildTimeline(scenarioId, baseline, eval, eval)
        
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events[1].isDivergent)
        assertNull(timeline.events[1].baselineEvidence)
        assertNotNull(timeline.events[1].evalEvidence)
    }

    @Test
    fun testPrivacy_NoInternalIdsInTrace() {
        val event = TraceEvent(type = TraceEventType.ELIGIBILITY_CHECK, metadata = mapOf("itemId" to "internal-uuid-123", "itemId_hash" to "hash-123"))
        val norm = RegressionMatrix.normalizeEvent(event)
        
        // It should prefer the hash
        assertEquals("hash-123", norm.itemId)
    }

    @Test
    fun testPrivacy_SnapshotUsesHashedIds() {
        val libState = LibraryPresentationState(
            mediaItems = listOf(MediaItem(id = "uuid-123", title = "T", mediaType = "PHOTO"))
        )
        val snapshot = RegressionMatrix.takeSnapshot("s1", libState, null)
        
        // Should NOT contain uuid-123
        assertFalse(snapshot.itemIds.contains("uuid-123"))
        assertEquals(RegressionMatrix.hashId("uuid-123"), snapshot.itemIds.first())
    }
}
