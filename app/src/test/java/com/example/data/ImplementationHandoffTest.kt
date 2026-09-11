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

class ImplementationHandoffTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_FullExecutionLifecycle() = runTest {
        // 1. Setup Finding
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Growth Strategy")
        
        // 2. Propose Improvement
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        assertEquals(IntelligenceLifecycleState.SUGGESTED_IMPROVEMENT, improvement.status)
        
        // 3. Approve Improvement (Creates Contract/Artifact)
        val artifact = BlueprintArtifact(
            blueprintId = finding.technicalDetails.id,
            strategyBlueprint = finding.technicalDetails,
            lifecycleState = BlueprintLifecycleState.PROPOSED
        )
        repository.approveImprovement(improvement.id, artifact, "Approved for expansion")
        
        val approvedImp = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.APPROVED, approvedImp.status)
        assertNotNull(approvedImp.blueprintArtifactId)

        // 4. Plan Implementation (Creates Run)
        repository.planImplementation(improvement.id)
        val implementationReadyImp = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_PLANNED, implementationReadyImp.status)
        
        val runs = fakeDao.getAllImplementationRuns().first()
        assertEquals(1, runs.size)
        val runId = runs[0].id

        // 5. Start Implementation
        repository.startImplementation(runId)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_IN_PROGRESS, fakeDao.getImprovementById(improvement.id)!!.status)

        // 6. Complete Implementation with Scope Verification (Success)
        // Mock manifest for scope verification
        val approvedFiles = listOf("com/example/data/TasteDna.kt")
        repository.completeImplementation(runId, "Done", approvedFiles)
        
        val implementedImp = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_COMPLETE, implementedImp.status)
        assertFalse(fakeDao.implementationRuns[runId]!!.deviationDetected)

        // 7. Verify (Verification Passed)
        repository.recordVerificationResult(
            improvement.id, runId, 
            buildPassed = true, testsPassed = true, regressionPassed = true, 
            dbIntegrityPassed = true, scopeCompliant = true, 
            acceptanceCriteriaResults = mapOf("Metric" to "Passed")
        )
        
        val verifiedImp = fakeDao.getImprovementById(improvement.id)!!
        // recordVerificationResult automatically starts monitoring if overallPassed
        assertEquals(IntelligenceLifecycleState.MONITORING, verifiedImp.status)
        
        // 8. Monitor & Validate
        val sessionId = fakeDao.getAllMonitoringSessions().first()[0].id
        repository.updateMonitoringProgress(sessionId, 1000, 65.0, emptyList())
        
        val validatedImp = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.VALIDATED, validatedImp.status)
    }

    @Test
    fun test02_ScopeDeviationDetection() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Scope Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        
        val artifact = BlueprintArtifact(
            blueprintId = finding.technicalDetails.id,
            strategyBlueprint = finding.technicalDetails,
            lifecycleState = BlueprintLifecycleState.PROPOSED
        )
        repository.approveImprovement(improvement.id, artifact, "Approved")
        repository.planImplementation(improvement.id)
        
        val runId = fakeDao.getAllImplementationRuns().first()[0].id
        repository.startImplementation(runId)
        
        // Actual changes include an unapproved file
        val actualFiles = listOf("com/example/data/TasteDna.kt", "com/example/data/Unauthorized.kt")
        repository.completeImplementation(runId, "Modified extra stuff", actualFiles)
        
        val impAfterDev = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.DEVIATION_DETECTED, impAfterDev.status)
        assertTrue(fakeDao.implementationRuns[runId]!!.deviationDetected)
        
        // Resolve deviation (Approve)
        repository.resolveDeviation(improvement.id, true)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_COMPLETE, fakeDao.getImprovementById(improvement.id)!!.status)
    }

    @Test
    fun test03_ImplementationFailureAndRetry() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, emptyList())
        val finding = repository.createFindingFromReport(report, "Fail Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        
        val artifact = BlueprintArtifact(
            blueprintId = finding.technicalDetails.id,
            strategyBlueprint = finding.technicalDetails,
            lifecycleState = BlueprintLifecycleState.PROPOSED
        )
        repository.approveImprovement(improvement.id, artifact, "Approved")
        repository.planImplementation(improvement.id)
        
        val runId = fakeDao.getAllImplementationRuns().first()[0].id
        repository.startImplementation(runId)
        
        repository.failImplementation(runId, "Build Error: Unresolved reference")
        
        val failedImp = fakeDao.getImprovementById(improvement.id)!!
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_FAILED, failedImp.status)
        
        // Retry
        repository.retryImplementation(improvement.id)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_PLANNED, fakeDao.getImprovementById(improvement.id)!!.status)
        assertEquals(2, fakeDao.getAllImplementationRuns().first().size)
    }

    class FakeIntelligenceDao : IntelligenceDao {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val artifacts = mutableMapOf<String, BlueprintArtifactEntity>()
        val implementationRuns = mutableMapOf<String, ImplementationRunEntity>()
        val verificationResults = mutableMapOf<String, VerificationResultEntity>()
        val monitoringSessions = mutableMapOf<String, MonitoringSessionEntity>()
        val validationResults = mutableMapOf<String, ValidationResultEntity>()
        val events = mutableMapOf<String, IntelligenceEventEntity>()
        val history = mutableListOf<LifecycleEventEntity>()
        val checkpoints = mutableMapOf<String, UserCheckpointEntity>()
        val attentionItems = mutableMapOf<String, AttentionItemEntity>()
        val evidenceSnapshots = mutableMapOf<String, EvidenceSnapshotEntity>()

        private val findingsFlow = MutableStateFlow<List<FindingEntity>>(emptyList())
        private val improvementsFlow = MutableStateFlow<List<SuggestedImprovementEntity>>(emptyList())
        private val artifactsFlow = MutableStateFlow<List<BlueprintArtifactEntity>>(emptyList())
        private val runsFlow = MutableStateFlow<List<ImplementationRunEntity>>(emptyList())
        private val sessionsFlow = MutableStateFlow<List<MonitoringSessionEntity>>(emptyList())
        private val valResultsFlow = MutableStateFlow<List<ValidationResultEntity>>(emptyList())
        private val historyFlow = MutableStateFlow<List<LifecycleEventEntity>>(emptyList())
        private val checkpointFlow = MutableStateFlow<UserCheckpointEntity?>(null)

        override fun getAllFindings() = findingsFlow
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
            findingsFlow.value = findings.values.toList()
        }

        override fun getAllImprovements() = improvementsFlow
        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
            improvementsFlow.value = improvements.values.toList()
        }

        override fun getArtifactsForImprovement(improvementId: String) = 
            artifactsFlow.map { list -> list.filter { it.improvementId == improvementId } }
        override suspend fun getArtifactById(id: String) = artifacts[id]
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) { 
            artifacts[artifact.id] = artifact 
            artifactsFlow.value = artifacts.values.toList()
        }

        override fun getAllImplementationRuns() = runsFlow
        override fun getImplementationRunsForImprovement(improvementId: String) = 
            runsFlow.map { list -> list.filter { it.improvementId == improvementId } }
        override suspend fun getImplementationRunById(id: String) = implementationRuns[id]
        override suspend fun insertImplementationRun(run: ImplementationRunEntity) { 
            implementationRuns[run.id] = run 
            runsFlow.value = implementationRuns.values.toList()
        }
        override suspend fun updateImplementationRun(run: ImplementationRunEntity) { 
            implementationRuns[run.id] = run 
            runsFlow.value = implementationRuns.values.toList()
        }

        override fun getVerificationResultsForImprovement(improvementId: String) = MutableStateFlow(emptyList<VerificationResultEntity>())
        override suspend fun insertVerificationResult(result: VerificationResultEntity) { verificationResults[result.id] = result }

        override fun getAllMonitoringSessions() = sessionsFlow
        override fun getMonitoringSessionsForImprovement(improvementId: String) = 
            sessionsFlow.map { list -> list.filter { it.improvementId == improvementId } }
        override suspend fun getMonitoringSessionById(id: String) = monitoringSessions[id]
        override suspend fun insertMonitoringSession(session: MonitoringSessionEntity) { 
            monitoringSessions[session.id] = session 
            sessionsFlow.value = monitoringSessions.values.toList()
        }
        override suspend fun updateMonitoringSession(session: MonitoringSessionEntity) { 
            monitoringSessions[session.id] = session 
            sessionsFlow.value = monitoringSessions.values.toList()
        }

        override fun getValidationResultsForImprovement(improvementId: String) = valResultsFlow
        override suspend fun insertValidationResult(result: ValidationResultEntity) { 
            validationResults[result.id] = result 
            valResultsFlow.value = validationResults.values.toList()
        }

        override fun getLifecycleHistory(targetId: String) = historyFlow.map { list -> list.filter { it.targetId == targetId } }
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { 
            history.add(event) 
            historyFlow.value = history.toList()
        }

        override suspend fun insertEvidenceSnapshot(snapshot: EvidenceSnapshotEntity) { evidenceSnapshots[snapshot.improvementId] = snapshot }
        override suspend fun getEvidenceSnapshot(improvementId: String) = evidenceSnapshots[improvementId]

        override fun getAllAttentionItems() = MutableStateFlow(attentionItems.values.toList())
        override fun getActionableAttentionItems() = MutableStateFlow(attentionItems.values.filter { it.requiresAction })
        override suspend fun insertAttentionItem(item: AttentionItemEntity) { attentionItems[item.id] = item }
        override suspend fun updateAttentionItem(item: AttentionItemEntity) { attentionItems[item.id] = item }
        override suspend fun deleteActiveAttentionItemByDeduplicationKey(key: String) { attentionItems.entries.removeIf { it.value.deduplicationKey == key && it.value.status == AttentionStatus.NEW } }

        override suspend fun insertCheckpoint(checkpoint: UserCheckpointEntity) { checkpoints[checkpoint.checkpointId] = checkpoint; checkpointFlow.value = checkpoint }
        override suspend fun getCheckpoint(id: String) = checkpoints[id]
        override fun observeCheckpoint(id: String) = checkpointFlow

        override fun getPendingEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override suspend fun insertEvent(event: IntelligenceEventEntity) {}
        override suspend fun updateEvent(event: IntelligenceEventEntity) {}
        override fun getAllIntelligenceEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override fun getAllStoredEvidence() = flowOf(emptyList<EvidenceEntity>())
        override fun getAllSavedReports() = flowOf(emptyList<SavedIntelligenceReportEntity>())
        override suspend fun getSavedReportById(id: String) = null
        override suspend fun insertSavedReport(report: SavedIntelligenceReportEntity) {}
        override fun getAuditHistory(targetId: String) = flowOf(emptyList<IntegrityAuditEntity>())
        override suspend fun insertAudit(audit: IntegrityAuditEntity) {}
        override fun getArtifactsForBlueprint(blueprintId: String) = flowOf(emptyList<BlueprintArtifactEntity>())
        override fun getImprovementsForFinding(findingId: String) = flowOf(emptyList<SuggestedImprovementEntity>())
        override fun getAllActions() = flowOf(emptyList<IntelligenceActionEntity>())
        override fun getActionsForImprovement(improvementId: String) = flowOf(emptyList<IntelligenceActionEntity>())
        override suspend fun getActionById(id: String) = null
        override suspend fun insertAction(action: IntelligenceActionEntity) {}
        override suspend fun updateAction(action: IntelligenceActionEntity) {}
        override fun getVerificationResultsForRun(runId: String) = flowOf(emptyList<VerificationResultEntity>())
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
        override fun getAllRegressionAlerts() = flowOf(emptyList<RegressionAlertEntity>())
    }
}
