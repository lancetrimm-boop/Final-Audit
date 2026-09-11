package com.example.data

import android.util.Log
import com.example.data.db.*
import com.example.data.blueprint.*
import com.example.ui.screens.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class IntelligenceExplanationTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_GenerateFindingExplanation() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 65.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Test Finding")
        
        val viewModel = IntelligenceViewModel(repository)
        viewModel.askAuraToExplainFinding(finding.id)
        
        val explanation = viewModel.explanation.first { it != null }
        assertNotNull(explanation)
        assertEquals("Finding", explanation?.sourceType)
        assertTrue(explanation?.reasoning?.any { it.contains("65.00") } ?: false)
    }

    @Test
    fun test02_GenerateImprovementExplanation() = runTest {
        val evidence = listOf(
            EvidenceRecord(
                tier = EvidenceTier.EXPERIMENTAL,
                sampleCount = 10,
                score = 65.0,
                quality = 0.8
            )
        )
        val report = ClosedLoopEngine.evaluate(50.0, 65.0, 70.0, evidence)
        val finding = repository.createFindingFromReport(report, "Test Finding")
        val imp = repository.getAllImprovements().first().first()
        
        val viewModel = IntelligenceViewModel(repository)
        viewModel.askAuraToExplain(imp)
        
        val explanation = viewModel.explanation.first { it != null }
        assertNotNull(explanation)
        assertEquals("Improvement", explanation?.sourceType)
        assertTrue(explanation?.whatWillChange?.isNotEmpty() ?: false)
    }

    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val checkpoints = mutableMapOf<String, UserCheckpointEntity>()
        val events = mutableListOf<LifecycleEventEntity>()

        private val findingsFlow = MutableStateFlow<List<FindingEntity>>(emptyList())
        private val improvementsFlow = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList())
        private val historyFlow = MutableStateFlow<List<LifecycleEventEntity>>(emptyList())

        override fun getAllFindings(): Flow<List<FindingEntity>> = findingsFlow
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }

        override fun getAllImprovements(): Flow<List<SuggestedImprovementEntity>> = improvementsFlow
        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }

        override suspend fun getCheckpoint(id: String) = checkpoints[id]
        override fun observeCheckpoint(id: String): Flow<UserCheckpointEntity?> = flowOf(checkpoints[id])
        override suspend fun insertCheckpoint(checkpoint: UserCheckpointEntity) { checkpoints[checkpoint.checkpointId] = checkpoint }

        override fun getLifecycleHistory(targetId: String) = historyFlow.map { list -> list.filter { it.targetId == targetId } }
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { 
            events.add(event) 
            historyFlow.value = events.toList()
        }

        // Minimal implementation for other required methods
        override fun getImprovementsForFinding(findingId: String) = flowOf(emptyList<SuggestedImprovementEntity>())
        override fun getAllActions() = flowOf(emptyList<IntelligenceActionEntity>())
        override fun getActionsForImprovement(improvementId: String) = flowOf(emptyList<IntelligenceActionEntity>())
        override suspend fun getActionById(id: String) = null
        override suspend fun insertAction(action: IntelligenceActionEntity) {}
        override suspend fun updateAction(action: IntelligenceActionEntity) {}
        override fun getAllImplementationRuns() = flowOf(emptyList<ImplementationRunEntity>())
        override fun getAllMonitoringSessions() = flowOf(emptyList<MonitoringSessionEntity>())
        override fun getAllRegressionAlerts() = flowOf(emptyList<RegressionAlertEntity>())
        override fun getImplementationRunsForImprovement(improvementId: String) = flowOf(emptyList<ImplementationRunEntity>())
        override suspend fun getImplementationRunById(id: String) = null
        override suspend fun insertImplementationRun(run: ImplementationRunEntity) {}
        override suspend fun updateImplementationRun(run: ImplementationRunEntity) {}
        override fun getVerificationResultsForImprovement(improvementId: String) = flowOf(emptyList<VerificationResultEntity>())
        override fun getVerificationResultsForRun(runId: String) = flowOf(emptyList<VerificationResultEntity>())
        override suspend fun insertVerificationResult(result: VerificationResultEntity) {}
        override fun getMonitoringSessionsForImprovement(improvementId: String) = flowOf(emptyList<MonitoringSessionEntity>())
        override suspend fun getMonitoringSessionById(id: String) = null
        override suspend fun insertMonitoringSession(session: MonitoringSessionEntity) {}
        override suspend fun updateMonitoringSession(session: MonitoringSessionEntity) {}
        override fun getValidationResultsForImprovement(improvementId: String) = flowOf(emptyList<ValidationResultEntity>())
        override suspend fun insertValidationResult(result: ValidationResultEntity) {}
        override fun getRegressionAlertsForImprovement(improvementId: String) = flowOf(emptyList<RegressionAlertEntity>())
        override suspend fun getRegressionAlertById(id: String) = null
        override suspend fun insertRegressionAlert(alert: RegressionAlertEntity) {}
        override suspend fun updateRegressionAlert(alert: RegressionAlertEntity) {}
        override fun getRollbackRunsForImprovement(improvementId: String) = flowOf(emptyList<RollbackRunEntity>())
        override suspend fun getRollbackRunById(id: String) = null
        override suspend fun insertRollbackRun(run: RollbackRunEntity) {}
        override suspend fun updateRollbackRun(run: RollbackRunEntity) {}
        override suspend fun getReviewMetadata(targetId: String) = null
        override suspend fun insertReviewMetadata(metadata: ReviewMetadataEntity) {}
        override fun getPendingEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override suspend fun insertEvent(event: IntelligenceEventEntity) {}
        override suspend fun updateEvent(event: IntelligenceEventEntity) {}
        override fun getAllStoredEvidence() = flowOf(emptyList<EvidenceEntity>())
        override fun getAllIntelligenceEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override fun getAuditHistory(targetId: String) = flowOf(emptyList<IntegrityAuditEntity>())
        override suspend fun insertAudit(audit: IntegrityAuditEntity) {}
        override suspend fun getEvidenceSnapshot(improvementId: String) = null
        override suspend fun insertEvidenceSnapshot(snapshot: EvidenceSnapshotEntity) {}
        override fun getArtifactsForBlueprint(blueprintId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override fun getArtifactsForImprovement(improvementId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override suspend fun getArtifactById(id: String) = null
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) {}
    }
}
