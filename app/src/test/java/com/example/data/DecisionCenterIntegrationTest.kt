package com.example.data

import android.util.Log
import com.example.data.db.*
import com.example.data.blueprint.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class DecisionCenterIntegrationTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_NoActionRequired_State() = runTest {
        val state = repository.getDecisionCenterState()
        assertTrue(state.attentionItems.isEmpty())
        assertTrue(state.criticalIssues.isEmpty())
        assertEquals(0L, state.lastReviewedAt)
    }

    @Test
    fun test02_Regression_Prioritization() = runTest {
        // Setup regression alert
        val alert = RegressionAlertEntity(
            id = "REG-1", improvementId = "IMP-1", runId = "RUN-1", artifactId = "ART-1", sessionId = "MON-1",
            severity = RegressionSeverity.CRITICAL, affectedMetric = "Engagement", baselineValue = 50.0,
            preRegressionValue = 50.0, currentResult = 40.0, change = -10.0, evidenceIdsJson = "[]",
            confidence = 1.0, status = RegressionAlertStatus.ACTIVE, recommendation = "Rollback"
        )
        fakeDao.insertRegressionAlert(alert)
        
        // Also add a low priority finding with valid technical details
        val blueprint = StrategyBlueprintGenerator.generateBlueprint("Minor", "...", ClosedLoopEngine.evaluate(50.0, 50.0, 50.0, emptyList()))
        val moshi = com.squareup.moshi.Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        val blueprintJson = moshi.adapter(StrategyBlueprint::class.java).toJson(blueprint)

        val finding = FindingEntity(
            id = "FIND-1", title = "Minor Finding", summary = "...", classification = FindingClassification.INFORMATIONAL,
            confidence = ConfidenceLevel.LOW, dateDiscovered = System.currentTimeMillis(),
            technicalDetailsJson = blueprintJson, lifecycleState = IntelligenceLifecycleState.FINDING_DETECTED
        )
        fakeDao.insertFinding(finding)

        val state = repository.getDecisionCenterState()
        
        // Regression should be in Critical Issues
        assertEquals(1, state.criticalIssues.size)
        assertEquals("REG-1", state.criticalIssues[0].id)
        assertEquals(DecisionPriority.CRITICAL, state.criticalIssues[0].priority)
    }

    @Test
    fun test03_ReviewTracking() = runTest {
        val targetId = "IMP-1"
        
        // Mark as seen
        repository.markAsSeen(targetId)
        val metaSeen = fakeDao.getReviewMetadata(targetId)
        assertEquals(ReviewStatus.SEEN, metaSeen?.status)
        assertNotNull(metaSeen?.firstSeenTimestamp)

        // Mark as reviewed
        repository.markAsReviewed(targetId)
        val metaReviewed = fakeDao.getReviewMetadata(targetId)
        assertEquals(ReviewStatus.REVIEWED, metaReviewed?.status)
        assertNotNull(metaReviewed?.reviewedTimestamp)
    }

    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val alerts = mutableMapOf<String, RegressionAlertEntity>()
        val reviews = mutableMapOf<String, ReviewMetadataEntity>()
        val checkpoints = mutableMapOf<String, UserCheckpointEntity>()

        private val findingsFlow = MutableStateFlow<List<FindingEntity>>(emptyList())
        private val improvementsFlow = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList())
        private val alertsFlow = MutableStateFlow<List<RegressionAlertEntity>>(emptyList())

        override fun getAllFindings(): Flow<List<FindingEntity>> = findingsFlow
        override suspend fun getFindingById(id: String): FindingEntity? = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }

        override fun getAllImprovements(): Flow<List<SuggestedImprovementEntity>> = improvementsFlow
        override suspend fun getImprovementById(id: String): SuggestedImprovementEntity? = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }

        override fun getAllRegressionAlerts(): Flow<List<RegressionAlertEntity>> = alertsFlow
        override fun getRegressionAlertsForImprovement(improvementId: String): Flow<List<RegressionAlertEntity>> = 
            alertsFlow.map { list -> list.filter { it.improvementId == improvementId } }
        override suspend fun getRegressionAlertById(id: String): RegressionAlertEntity? = alerts[id]
        override suspend fun insertRegressionAlert(alert: RegressionAlertEntity) { 
            alerts[alert.id] = alert 
            alertsFlow.value = alerts.values.toList()
        }
        override suspend fun updateRegressionAlert(alert: RegressionAlertEntity) { 
            alerts[alert.id] = alert 
            alertsFlow.value = alerts.values.toList()
        }

        override suspend fun getReviewMetadata(targetId: String): ReviewMetadataEntity? = reviews[targetId]
        override suspend fun insertReviewMetadata(metadata: ReviewMetadataEntity) { reviews[metadata.targetId] = metadata }

        override suspend fun getCheckpoint(id: String): UserCheckpointEntity? = checkpoints[id]
        override suspend fun insertCheckpoint(checkpoint: UserCheckpointEntity) { checkpoints[checkpoint.checkpointId] = checkpoint }

        // Unimplemented for this test
        override fun getImprovementsForFinding(findingId: String) = flowOf(emptyList<SuggestedImprovementEntity>())
        override fun getLifecycleHistory(targetId: String) = flowOf(emptyList<LifecycleEventEntity>())
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) {}
        override fun getAllActions() = flowOf(emptyList<IntelligenceActionEntity>())
        override fun getActionsForImprovement(improvementId: String) = flowOf(emptyList<IntelligenceActionEntity>())
        override suspend fun getActionById(id: String) = null
        override suspend fun insertAction(action: IntelligenceActionEntity) {}
        override suspend fun updateAction(action: IntelligenceActionEntity) {}
        override fun getArtifactsForBlueprint(blueprintId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override fun getArtifactsForImprovement(improvementId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override suspend fun getArtifactById(id: String) = null
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) {}
        override fun getAllImplementationRuns() = flowOf(emptyList<ImplementationRunEntity>())
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
        override fun getRollbackRunsForImprovement(improvementId: String) = flowOf(emptyList<RollbackRunEntity>())
        override suspend fun getRollbackRunById(id: String) = null
        override suspend fun insertRollbackRun(run: RollbackRunEntity) {}
        override suspend fun updateRollbackRun(run: RollbackRunEntity) {}
    }
}
