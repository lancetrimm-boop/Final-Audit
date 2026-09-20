package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.models.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RegressionArtifactTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Clean up developer_tooling dir
        val baseDir = File(context.filesDir, "developer_tooling")
        baseDir.deleteRecursively()
        
        RegressionArtifactManager.resetForTesting()
        RegressionArtifactManager.initialize(context)
    }

    @Test
    fun testReportPersistence_PASS() {
        val summary = RegressionGateSummary(
            result = RegressionGateResult.PASS,
            entries = emptyList(),
            totalScenarios = 1,
            unchangedCount = 1,
            reproducedCount = 0,
            notReproducedCount = 0,
            insufficientEvidenceCount = 0
        )
        
        val reportId = RegressionArtifactManager.createReport(summary, "baseline_1")
        assertNotNull(reportId)
        
        // Reload manager state (simulated)
        val report = RegressionArtifactManager.history.value.find { it.id == reportId }
        assertNotNull(report)
        assertEquals(RegressionGateResult.PASS, report?.gateSummary?.result)
        assertEquals("baseline_1", report?.baselineId)
        assertNotNull(report?.buildIdentity)
    }

    @Test
    fun testSealHandoff_OnlyOnPASS() {
        val passSummary = RegressionGateSummary(
            result = RegressionGateResult.PASS,
            entries = listOf(RegressionMatrixEntry("s1", "Scenario 1", "LIBRARY", RegressionStatus.MATCH)),
            totalScenarios = 1, unchangedCount = 1, reproducedCount = 0, notReproducedCount = 0, insufficientEvidenceCount = 0
        )
        val failSummary = RegressionGateSummary(
            result = RegressionGateResult.FAIL,
            entries = listOf(RegressionMatrixEntry("s1", "Scenario 1", "LIBRARY", RegressionStatus.MISMATCH, replayStatus = ReproductionStatus.REPRODUCED)),
            totalScenarios = 1, unchangedCount = 0, reproducedCount = 1, notReproducedCount = 0, insufficientEvidenceCount = 0
        )

        val passId = RegressionArtifactManager.createReport(passSummary, "b1")!!
        val failId = RegressionArtifactManager.createReport(failSummary, "b1")!!

        assertTrue(RegressionArtifactManager.sealHandoff(passId))
        assertFalse(RegressionArtifactManager.sealHandoff(failId))
        
        assertEquals(passId, RegressionArtifactManager.latestSeal.value?.reportId)
    }

    @Test
    fun testRetention_EnforcesLimitOf5() {
        for (i in 1..7) {
            val summary = RegressionGateSummary(
                result = RegressionGateResult.PASS,
                entries = emptyList(), totalScenarios = 0, unchangedCount = 0, reproducedCount = 0, notReproducedCount = 0, insufficientEvidenceCount = 0
            )
            RegressionArtifactManager.createReport(summary, null)
        }
        
        val reportsDir = File(context.filesDir, "developer_tooling/reports")
        val files = reportsDir.listFiles { f -> f.extension == "json" }
        assertEquals(5, files?.size)
    }

    @Test
    fun testRetention_ProtectsSealedReport() {
        // 1. Create a report and seal it
        val summary = RegressionGateSummary(
            result = RegressionGateResult.PASS,
            entries = emptyList(), totalScenarios = 0, unchangedCount = 0, reproducedCount = 0, notReproducedCount = 0, insufficientEvidenceCount = 0
        )
        val sealedReportId = RegressionArtifactManager.createReport(summary, "b1")!!
        RegressionArtifactManager.sealHandoff(sealedReportId)
        
        // 2. Create 10 more reports to trigger retention
        for (i in 1..10) {
            RegressionArtifactManager.createReport(summary, null)
        }
        
        // 3. Confirm sealed report still exists
        val reportsDir = File(context.filesDir, "developer_tooling/reports")
        val sealedFile = File(reportsDir, "$sealedReportId.json")
        assertTrue("Sealed report should be protected from retention", sealedFile.exists())
        
        // Total reports should be 6 (5 standard + 1 protected sealed)
        val allFiles = reportsDir.listFiles { f -> f.extension == "json" }
        assertEquals(6, allFiles?.size)
    }

    @Test
    fun testMarkdownGeneration_ContainsEvidence() {
        val entry = RegressionMatrixEntry(
            scenarioId = "s1", scenarioName = "Test Scenario", surface = "LIBRARY", 
            status = RegressionStatus.MISMATCH, failureCategory = "SCORING_DIVERGENCE",
            mismatches = listOf("Score mismatch: 0.9 vs 0.8")
        )
        val summary = RegressionGateSummary(
            result = RegressionGateResult.FAIL,
            entries = listOf(entry),
            totalScenarios = 1, unchangedCount = 0, reproducedCount = 1, notReproducedCount = 0, insufficientEvidenceCount = 0
        )
        val reportId = RegressionArtifactManager.createReport(summary, "b1")!!
        
        val markdown = RegressionArtifactManager.generateMarkdown(reportId)
        
        assertTrue(markdown.contains("GATE STATUS: **FAIL**"))
        assertTrue(markdown.contains("SCORING_DIVERGENCE"))
        assertTrue(markdown.contains("Score mismatch: 0.9 vs 0.8"))
        assertTrue(markdown.contains("Test Scenario"))
    }
    
    @Test
    fun testPersistenceRoundTrip() {
        val entry = RegressionMatrixEntry(
            scenarioId = "s1", scenarioName = "Test Scenario", surface = "LIBRARY", 
            status = RegressionStatus.MISMATCH, replayStatus = ReproductionStatus.REPRODUCED,
            timeline = RegressionTimeline("s1", listOf(TimelineEvent(TraceEventType.SCORING_COMPLETED, "DETAIL", isDivergent = true)))
        )
        val summary = RegressionGateSummary(
            result = RegressionGateResult.FAIL,
            entries = listOf(entry),
            totalScenarios = 1, unchangedCount = 0, reproducedCount = 1, notReproducedCount = 0, insufficientEvidenceCount = 0
        )
        val reportId = RegressionArtifactManager.createReport(summary, "b1")!!
        
        // Simulate app restart
        RegressionArtifactManager.resetForTesting()
        RegressionArtifactManager.initialize(context)
        
        val reloaded = RegressionArtifactManager.history.value.find { it.id == reportId }
        assertNotNull(reloaded)
        assertEquals(RegressionGateResult.FAIL, reloaded?.gateSummary?.result)
        assertEquals(1, reloaded?.gateSummary?.entries?.size)
        assertEquals(true, reloaded?.gateSummary?.entries?.first()?.timeline?.events?.first()?.isDivergent)
    }
}
