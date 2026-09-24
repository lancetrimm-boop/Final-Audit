package com.example.data

import com.example.data.db.*
import com.example.data.blueprint.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class IntelligenceSynchronizationTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        // Provide a scope that uses the test dispatcher for deterministic execution
        repository = IntelligenceRepository(fakeDao, scope = CoroutineScope(testDispatcher + SupervisorJob()))
    }

    @Test
    fun test01_RealTimeFindingPropagation() = runTest(testDispatcher) {
        // 1. Observe findings
        val findingsFlow = repository.getAllFindings()
        val results = mutableListOf<List<Finding>>()
        
        val job = launch {
            findingsFlow.collect { results.add(it) }
        }

        // 2. Add evidence and trigger event
        val evidenceId = "EV-SYNC-01"
        fakeDao.insertEvidence(EvidenceEntity(id = evidenceId, tier = "PRODUCTION", sampleCount = 100, score = 70.0, quality = 0.9, source = "Test", timestamp = System.currentTimeMillis()))
        
        repository.onEvidenceAvailable(evidenceId)
        
        // Wait for all coroutines in the repository's scope to finish
        advanceUntilIdle()

        assertTrue("Finding should be generated and persisted in the DAO", fakeDao.findings.isNotEmpty())
        assertTrue("Collect should have received the new findings", results.any { it.isNotEmpty() })
        job.cancel()
    }

    @Test
    fun test02_SinceLastReviewReactivity() = runTest(testDispatcher) {
        // Setup initial review
        repository.markAllAsReviewed()
        advanceUntilIdle()
        
        // Observe Master Report
        val reportFlow = repository.getMasterReportFlow()
        val reports = mutableListOf<MasterIntelligenceReport>()
        
        val job = launch {
            reportFlow.collect { reports.add(it) }
        }
        
        advanceUntilIdle()
        assertTrue(reports.isNotEmpty())
        assertEquals(0, reports.last().sinceLastReview.newFindings)

        // Create new finding
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 10, score = 60.0)
        ))
        repository.createFindingFromReport(report, "New Activity")
        
        advanceUntilIdle()
        assertEquals(1, reports.last().sinceLastReview.newFindings)
        
        // Review again
        repository.markAllAsReviewed()
        advanceUntilIdle()
        assertEquals(0, reports.last().sinceLastReview.newFindings)
        
        job.cancel()
    }

    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val checkpoints = mutableMapOf<String, UserCheckpointEntity>()
        val intelEvents = MutableStateFlow<List<IntelligenceEventEntity>>(emptyList())
        val evidence = mutableMapOf<String, EvidenceEntity>()
        
        private val findingsState = MutableStateFlow<List<FindingEntity>>(emptyList())
        private val improvementsState = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList())
        private val checkpointState = MutableStateFlow<Map<String, UserCheckpointEntity>>(emptyMap())

        override fun getAllFindings(): Flow<List<FindingEntity>> = findingsState
        override fun getAllImprovements(): Flow<List<SuggestedImprovementEntity>> = improvementsState
        
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding
            findingsState.value = findings.values.toList()
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding
            findingsState.value = findings.values.toList()
        }

        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement
            improvementsState.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement
            improvementsState.value = improvements.values.toList()
        }

        override fun getPendingEvents(): Flow<List<IntelligenceEventEntity>> = 
            intelEvents.map { list -> list.filter { it.status == "PENDING" } }

        override suspend fun insertEvent(event: IntelligenceEventEntity) {
            intelEvents.value = intelEvents.value + event
        }
        override suspend fun updateEvent(event: IntelligenceEventEntity) {
            intelEvents.value = intelEvents.value.map { if (it.id == event.id) event else it }
        }

        override fun observeCheckpoint(id: String): Flow<UserCheckpointEntity?> = 
            checkpointState.map { it[id] }
        
        override suspend fun insertCheckpoint(checkpoint: UserCheckpointEntity) {
            checkpointState.value = checkpointState.value + (checkpoint.checkpointId to checkpoint)
            checkpoints[checkpoint.checkpointId] = checkpoint
        }

        override fun getAllStoredEvidence(): Flow<List<EvidenceEntity>> = MutableStateFlow(evidence.values.toList())
        fun insertEvidence(e: EvidenceEntity) { evidence[e.id] = e }

        override fun getImprovementsForFinding(findingId: String) = flowOf(emptyList<SuggestedImprovementEntity>())
        override fun getLifecycleHistory(targetId: String) = flowOf(emptyList<LifecycleEventEntity>())
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) {}
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
        override suspend fun getCheckpoint(id: String) = checkpoints[id]
        override fun getAllIntelligenceEvents() = intelEvents
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
