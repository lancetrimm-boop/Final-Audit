package com.example.data

import android.util.Log
import com.example.data.db.*
import com.example.data.blueprint.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Integration tests for the Aura Intelligence Monitoring and Validation Workflow.
 */
class IntelligenceMonitoringTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_MonitoringCycle_SuccessfulValidation() = runTest {
        // 1. Setup Approved, Implemented, Verified Improvement
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Monitoring Success Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        repository.approveImprovement(improvement.id, BlueprintArtifact(strategyBlueprint = finding.technicalDetails))
        repository.planImplementation(improvement.id)
        val run = repository.getImplementationRuns(improvement.id).first()[0]
        repository.startImplementation(run.id)
        repository.completeImplementation(run.id)
        
        // 2. Pass Verification -> Should trigger Monitoring
        repository.recordVerificationResult(
            improvementId = improvement.id,
            runId = run.id,
            buildPassed = true,
            testsPassed = true,
            regressionPassed = true,
            dbIntegrityPassed = true,
            scopeCompliant = true,
            acceptanceCriteriaResults = emptyMap()
        )

        assertEquals(IntelligenceLifecycleState.MONITORING, fakeDao.getImprovementById(improvement.id)?.status)
        val sessions = repository.getMonitoringSessions(improvement.id).first()
        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals(MonitoringStatus.ACTIVE, session.status)
        assertEquals(0, session.currentSampleCount)

        // 3. Update Progress (Under Threshold)
        repository.updateMonitoringProgress(session.id, 50, 55.0, listOf("EV-1"))
        val updatedSession = fakeDao.getMonitoringSessionById(session.id)
        assertEquals(50, updatedSession?.currentSampleCount)
        assertEquals(IntelligenceLifecycleState.MONITORING, fakeDao.getImprovementById(improvement.id)?.status)

        // 4. Update Progress (Reach Threshold)
        repository.updateMonitoringProgress(session.id, 100, 62.0, listOf("EV-1", "EV-2"))
        
        // 5. Verify Validation
        val finalImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.VALIDATED, finalImp?.status)
        
        val results = repository.getValidationResults(improvement.id).first()
        assertEquals(1, results.size)
        assertEquals(IntelligenceLifecycleState.VALIDATED, results[0].outcome)
        assertEquals(62.0, results[0].finalValue, 0.1)
        assertEquals(12.0, results[0].change, 0.1)
        
        // Verify session is completed
        assertEquals(MonitoringStatus.COMPLETED, fakeDao.getMonitoringSessionById(session.id)?.status)
    }

    @Test
    fun test02_RegressionDetection_TriggersAlert() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Regression Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        repository.approveImprovement(improvement.id, BlueprintArtifact(strategyBlueprint = finding.technicalDetails))
        repository.planImplementation(improvement.id)
        val run = repository.getImplementationRuns(improvement.id).first()[0]
        repository.startImplementation(run.id)
        repository.completeImplementation(run.id)
        repository.recordVerificationResult(improvement.id, run.id, true, true, true, true, true, emptyMap())

        val session = repository.getMonitoringSessions(improvement.id).first()[0]
        
        // Simulate sharp regression
        repository.updateMonitoringProgress(session.id, 20, 40.0, listOf("EV-BAD"))

        val finalImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.REGRESSION_DETECTED, finalImp?.status)
        
        val results = repository.getValidationResults(improvement.id).first()
        assertEquals(IntelligenceLifecycleState.REGRESSION_DETECTED, results[0].outcome)
        assertTrue(fakeDao.getMonitoringSessionById(session.id)?.regressionDetected == true)
    }

    /**
     * Fake DAO for Monitoring tests.
     */
    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val events = mutableListOf<LifecycleEventEntity>()
        val actions = mutableMapOf<String, IntelligenceActionEntity>()
        val artifacts = mutableMapOf<String, BlueprintArtifactEntity>()
        val runs = mutableMapOf<String, ImplementationRunEntity>()
        val results = mutableMapOf<String, VerificationResultEntity>()
        val sessions = mutableMapOf<String, MonitoringSessionEntity>()
        val validationResults = mutableMapOf<String, ValidationResultEntity>()

        private val findingsFlow = MutableStateFlow<List<FindingEntity>>(emptyList())
        private val improvementsFlow = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList())
        private val runsFlow = MutableStateFlow<List<ImplementationRunEntity>>(emptyList())
        private val sessionsFlow = MutableStateFlow<List<MonitoringSessionEntity>>(emptyList())
        private val valResultsFlow = MutableStateFlow<List<ValidationResultEntity>>(emptyList())

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
        override fun getImprovementsForFinding(findingId: String): Flow<List<SuggestedImprovementEntity>> = 
            improvementsFlow.map { list -> list.filter { it.findingId == findingId } }
        override suspend fun getImprovementById(id: String): SuggestedImprovementEntity? = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }

        override fun getLifecycleHistory(targetId: String): Flow<List<LifecycleEventEntity>> = 
            flowOf(events.filter { it.targetId == targetId })
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { events.add(event) }

        override fun getAllActions(): Flow<List<IntelligenceActionEntity>> = flowOf(emptyList())
        override fun getActionsForImprovement(improvementId: String): Flow<List<IntelligenceActionEntity>> = flowOf(emptyList())
        override suspend fun getActionById(id: String): IntelligenceActionEntity? = null
        override suspend fun insertAction(action: IntelligenceActionEntity) {}
        override suspend fun updateAction(action: IntelligenceActionEntity) {}

        override fun getArtifactsForBlueprint(blueprintId: String): Flow<List<BlueprintArtifactEntity>> = flowOf(emptyList())
        override fun getArtifactsForImprovement(improvementId: String): Flow<List<BlueprintArtifactEntity>> = flowOf(emptyList())
        override suspend fun getArtifactById(id: String): BlueprintArtifactEntity? = artifacts[id]
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) { artifacts[artifact.id] = artifact }

        override fun getImplementationRunsForImprovement(improvementId: String): Flow<List<ImplementationRunEntity>> = 
            runsFlow.map { list -> list.filter { it.improvementId == improvementId }.sortedByDescending { it.startTime } }
        override suspend fun getImplementationRunById(id: String): ImplementationRunEntity? = runs[id]
        override suspend fun insertImplementationRun(run: ImplementationRunEntity) { 
            runs[run.id] = run 
            runsFlow.value = runs.values.toList()
        }
        override suspend fun updateImplementationRun(run: ImplementationRunEntity) { 
            runs[run.id] = run 
            runsFlow.value = runs.values.toList()
        }

        override fun getVerificationResultsForImprovement(improvementId: String): Flow<List<VerificationResultEntity>> = flowOf(emptyList())
        override fun getVerificationResultsForRun(runId: String): Flow<List<VerificationResultEntity>> = flowOf(emptyList())
        override suspend fun insertVerificationResult(result: VerificationResultEntity) { results[result.id] = result }

        override fun getMonitoringSessionsForImprovement(improvementId: String): Flow<List<MonitoringSessionEntity>> = 
            sessionsFlow.map { list -> list.filter { it.improvementId == improvementId }.sortedByDescending { it.startTime } }
        override fun getAllMonitoringSessions(): Flow<List<MonitoringSessionEntity>> = sessionsFlow
        override suspend fun getMonitoringSessionById(id: String): MonitoringSessionEntity? = sessions[id]
        override suspend fun insertMonitoringSession(session: MonitoringSessionEntity) { 
            sessions[session.id] = session 
            sessionsFlow.value = sessions.values.toList()
        }
        override suspend fun updateMonitoringSession(session: MonitoringSessionEntity) { 
            sessions[session.id] = session 
            sessionsFlow.value = sessions.values.toList()
        }

        override fun getValidationResultsForImprovement(improvementId: String): Flow<List<ValidationResultEntity>> = 
            valResultsFlow.map { list -> list.filter { it.improvementId == improvementId } }
        override suspend fun insertValidationResult(result: ValidationResultEntity) { 
            validationResults[result.id] = result 
            valResultsFlow.value = validationResults.values.toList()
        }
    }
}
