package com.example.ui.screens

import com.example.ui.models.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Phase 8 Regression Matrix + Behavioral Snapshot System.
 */
class RegressionMatrixTest {

    @Test
    fun test01_MatrixExecution_AllMatch() {
        // Use a subset of deterministic scenarios that should match
        val scenarios = listOf(
            MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!,
            MirrorFixtures.scenarios.find { it.id == "scenario_empty_discovery" }!!
        )
        
        val summary = RegressionMatrix.execute(scenarios)
        
        assertEquals(2, summary.totalCount)
        assertEquals(2, summary.matchCount)
        assertEquals(0, summary.mismatchCount)
        assertTrue(summary.entries.all { it.status == RegressionStatus.MATCH })
    }

    @Test
    fun test02_MatrixExecution_MismatchDetection() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        // Force a mismatch in a copy
        val corruptedScenario = scenario.copy(
            id = "scenario_corrupted",
            libraryState = scenario.libraryState!!.copy(
                mediaItems = emptyList() // Should mismatch expectedItemIds
            )
        )
        
        val summary = RegressionMatrix.execute(listOf(corruptedScenario))
        
        assertEquals(1, summary.mismatchCount)
        val entry = summary.entries.first()
        assertEquals(RegressionStatus.MISMATCH, entry.status)
        // Without trace evidence for the missing item, we expect INSUFFICIENT EVIDENCE
        assertEquals("INSUFFICIENT EVIDENCE", entry.failureCategory)
        assertNotNull(entry.diff)
        assertTrue(entry.diff!!.deletions.isNotEmpty())
    }

    @Test
    fun test03_BehavioralSnapshot_Library() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val snapshot = RegressionMatrix.takeSnapshot(scenario.id, scenario.libraryState, null)
        
        assertEquals(scenario.id, snapshot.scenarioId)
        assertEquals("LIBRARY", snapshot.surface)
        assertEquals(3, snapshot.itemIds.size)
        assertEquals(RegressionMatrix.hashId("fixture_1"), snapshot.topRankedId)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun test04_Independence_NoStateLeakage() {
        val scenario1 = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val scenario2 = MirrorFixtures.scenarios.find { it.id == "scenario_empty_discovery" }!!
        
        // Execute matrix
        val summary = RegressionMatrix.execute(listOf(scenario1, scenario2))
        
        // Ensure scenario1 result is MATCH
        assertEquals(RegressionStatus.MATCH, summary.entries.find { it.scenarioId == scenario1.id }?.status)
        // Ensure scenario2 result is MATCH
        assertEquals(RegressionStatus.MATCH, summary.entries.find { it.scenarioId == scenario2.id }?.status)
    }
}
