package com.example.ui.screens

import com.example.data.intelligence.DecisionTraceCollector
import com.example.ui.models.TraceEventType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for Phase 6 Decision Trace system.
 */
class DecisionTraceTest {

    @Before
    fun setup() {
        // Collector is a singleton, clear it for isolation
        // Note: DecisionTraceCollector doesn't have a broad clear, but we clear specific requestIds
    }

    @Test
    fun test01_TraceEventOrdering() {
        val requestId = "test_ordering"
        DecisionTraceCollector.startTrace(requestId, "TEST")
        DecisionTraceCollector.logEvent(requestId, TraceEventType.POOL_FILTERED, "Filter applied")
        DecisionTraceCollector.logEvent(requestId, TraceEventType.SCORING_COMPLETED, "Score: 100")
        
        val trace = DecisionTraceCollector.getTrace(requestId)
        assertNotNull(trace)
        assertEquals(3, trace?.events?.size)
        assertEquals(TraceEventType.REQUEST_RECEIVED, trace?.events?.get(0)?.type)
        assertEquals(TraceEventType.POOL_FILTERED, trace?.events?.get(1)?.type)
        assertEquals(TraceEventType.SCORING_COMPLETED, trace?.events?.get(2)?.type)
    }

    @Test
    fun test02_TraceMetadataPreservation() {
        val requestId = "test_metadata"
        val metadata = mapOf("key" to "value")
        DecisionTraceCollector.startTrace(requestId, "SURFACE")
        DecisionTraceCollector.logEvent(requestId, TraceEventType.CANDIDATES_RETRIEVED, "Found items", metadata)
        
        val trace = DecisionTraceCollector.getTrace(requestId)
        val event = trace?.events?.find { it.type == TraceEventType.CANDIDATES_RETRIEVED }
        assertNotNull(event)
        assertEquals("value", event?.metadata?.get("key"))
    }

    @Test
    fun test03_TraceClearance() {
        val requestId = "test_clear"
        DecisionTraceCollector.startTrace(requestId, "TEMP")
        assertNotNull(DecisionTraceCollector.getTrace(requestId))
        
        DecisionTraceCollector.clearTrace(requestId)
        assertNull(DecisionTraceCollector.getTrace(requestId))
    }
}
