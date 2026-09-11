package com.example.ui.screens

import com.example.data.*
import com.example.data.db.*
import com.example.data.blueprint.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for System Analysis logic in IntelligenceViewModel.
 */
class SystemAnalysisTest {

    private lateinit var viewModel: IntelligenceViewModel
    private lateinit var fakeDao: FakeIntelligenceDao
    private lateinit var repository: IntelligenceRepository

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
        viewModel = IntelligenceViewModel(repository)
    }

    @Test
    fun test01_ProblemRequiresAction() = runTest {
        // 1. Create a finding with a regression
        val report = ClosedLoopEngine.evaluate(50.0, 35.0, 60.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 50, score = 35.0)
        ))
        repository.createFindingFromReport(report, "Regression Detected")
        
        // Wait for ViewModel to process the update
        val state = viewModel.state.first { it.findings.isNotEmpty() }
        val analysis = state.systemAnalysis
        
        assertNotNull(analysis)
        assertEquals(ActionStatus.ACTION_REQUIRED, analysis?.actionStatus)
        assertTrue(analysis?.actionRequired == true)
        assertTrue(analysis?.whatsNotWorking?.any { it.contains("regression", ignoreCase = true) } == true)
    }

    @Test
    fun test02_OpportunityReviewRecommended() = runTest {
        // 1. Create a finding with improvement
        val report = ClosedLoopEngine.evaluate(50.0, 65.0, 65.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 100, score = 65.0)
        ))
        repository.createFindingFromReport(report, "Performance Boost")
        
        val state = viewModel.state.first { it.findings.isNotEmpty() }
        val analysis = state.systemAnalysis
        
        assertNotNull(analysis)
        assertEquals(ActionStatus.REVIEW_RECOMMENDED, analysis?.actionStatus)
        assertTrue(analysis?.whatsWorking?.any { it.contains("positive", ignoreCase = true) || it.contains("increase", ignoreCase = true) } == true)
    }

    @Test
    fun test03_LowConfidenceMoreEvidenceNeeded() = runTest {
        // 1. Create a finding with very few samples
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 2, score = 60.0)
        ))
        repository.createFindingFromReport(report, "Potential Improvement")
        
        val state = viewModel.state.first { it.findings.isNotEmpty() }
        val analysis = state.systemAnalysis
        
        assertNotNull(analysis)
        assertEquals(ActionStatus.MORE_EVIDENCE_NEEDED, analysis?.actionStatus)
        assertTrue(analysis?.confidenceExplanation?.contains("limited sample size") == true)
    }

    @Test
    fun test04_NoActionRequired() = runTest {
        // 1. Create an informational finding (no change)
        val report = ClosedLoopEngine.evaluate(50.0, 50.0, 50.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 50, score = 50.0)
        ))
        repository.createFindingFromReport(report, "System Stable")
        
        val state = viewModel.state.first { it.findings.isNotEmpty() }
        val analysis = state.systemAnalysis
        
        assertNotNull(analysis)
        assertEquals(ActionStatus.NO_ACTION_REQUIRED, analysis?.actionStatus)
        assertFalse(analysis?.actionRequired == true)
    }

    /**
     * Re-using the fake DAO from the repository test.
     */
    class FakeIntelligenceDao : StubIntelligenceDao() {
        internal val findings = mutableMapOf<String, FindingEntity>()
        internal val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        internal val alerts = mutableMapOf<String, RegressionAlertEntity>()
        internal val events = mutableListOf<LifecycleEventEntity>()
        internal val actions = mutableMapOf<String, IntelligenceActionEntity>()
        internal val checkpoint = MutableStateFlow<UserCheckpointEntity?>(null)

        private val findingsFlow = MutableStateFlow<List<FindingEntity>>(emptyList<FindingEntity>())
        private val improvementsFlow = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList<SuggestedImprovementEntity>())
        private val alertsFlow = MutableStateFlow<List<RegressionAlertEntity>>(emptyList<RegressionAlertEntity>())

        override fun getAllFindings() = findingsFlow
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList().sortedByDescending { it.dateDiscovered }
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList().sortedByDescending { it.dateDiscovered }
        }

        override fun getAllImprovements() = improvementsFlow
        override fun getImprovementsForFinding(findingId: String) = flowOf(improvements.values.filter { it.findingId == findingId })
        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }

        override fun getAllRegressionAlerts() = alertsFlow
        override suspend fun insertRegressionAlert(alert: RegressionAlertEntity) {
            alerts[alert.id] = alert
            alertsFlow.value = alerts.values.toList()
        }

        override suspend fun insertCheckpoint(cp: UserCheckpointEntity) { checkpoint.value = cp }
        override fun observeCheckpoint(id: String) = checkpoint

        override fun getLifecycleHistory(targetId: String) = flowOf(events.filter { it.targetId == targetId })
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { events.add(event) }
        
        override fun getAllActions() = flowOf(actions.values.toList())
        override fun getActionsForImprovement(improvementId: String) = flowOf(actions.values.filter { it.improvementId == improvementId })
        override suspend fun getActionById(id: String) = actions[id]
        override suspend fun insertAction(action: IntelligenceActionEntity) { actions[action.id] = action }
        override suspend fun updateAction(action: IntelligenceActionEntity) { actions[action.id] = action }
        
        override fun getArtifactsForBlueprint(blueprintId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) {}
    }
}
