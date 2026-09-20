package com.example.ui.screens

import com.example.ui.models.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Tests for Phase 7 Golden-State Regression Harness.
 */
class GoldenRegressionTest {

    private fun invokeCompareGolden(scenario: MirrorScenario, libState: LibraryPresentationState?, discoverState: DiscoverPresentationState?): GoldenComparisonResult {
        // ConsumerMirrorScreen.kt contains the private compareGolden function.
        // In a real project, we might expose it as internal or move to a helper.
        // For this task, I'll implement a test-side replica or use reflection if I can find the class.
        // Since I'm the one who wrote it as private in ConsumerMirrorScreenKt, I'll use reflection on the file's facade class.
        
        val clazz = Class.forName("com.example.ui.screens.ConsumerMirrorScreenKt")
        val method = clazz.getDeclaredMethods().find { it.name.contains("compareGolden") }
        method?.isAccessible = true
        return method?.invoke(null, scenario, libState, discoverState) as GoldenComparisonResult
    }

    @Test
    fun test01_GoldenMatch_PopulatedLibrary() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        val result = invokeCompareGolden(scenario, scenario.libraryState, null)
        
        assertTrue("Golden scenario should match its own state", result.isMatch)
        assertEquals(0, result.mismatches.size)
    }

    @Test
    fun test02_GoldenMismatch_ItemOrder() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        // Swap items in observed state
        val corruptedState = scenario.libraryState!!.copy(
            mediaItems = scenario.libraryState!!.mediaItems.reversed()
        )
        
        val result = invokeCompareGolden(scenario, corruptedState, null)
        
        assertFalse("Ordering mismatch should be detected", result.isMatch)
        assertTrue(result.mismatches.any { it.contains("Top Rank") })
    }

    @Test
    fun test03_GoldenMismatch_TraceSequence() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_perfect_match" }!!
        // Observed trace is missing events
        val badTrace = scenario.libraryState!!.provenanceMap["fixture_1"]!!.trace!!.copy(
            events = emptyList()
        )
        val corruptedState = scenario.libraryState!!.copy(
            provenanceMap = mapOf("fixture_1" to scenario.libraryState!!.provenanceMap["fixture_1"]!!.copy(trace = badTrace))
        )
        
        val result = invokeCompareGolden(scenario, corruptedState, null)
        
        assertFalse("Trace sequence divergence should be detected", result.isMatch)
        assertTrue(result.mismatches.any { it.contains("Trace") })
    }

    @Test
    fun test04_GoldenMismatch_ProvenanceFragment() {
        val scenario = MirrorFixtures.scenarios.find { it.id == "scenario_low_confidence" }!!
        // Observed provenance missing expected fragment
        val corruptedState = scenario.libraryState!!.copy(
            provenanceMap = mapOf("fixture_2" to scenario.libraryState!!.provenanceMap["fixture_2"]!!.copy(provenanceSummary = "Generic Score"))
        )
        
        val result = invokeCompareGolden(scenario, corruptedState, null)
        
        assertFalse("Provenance fragment mismatch should be detected", result.isMatch)
        assertTrue(result.mismatches.any { it.contains("Provenance") })
    }
}
