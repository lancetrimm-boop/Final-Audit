package com.example.data

import com.example.data.db.*
import com.example.data.blueprint.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class IntelligenceWorkflowTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_FindingToImplementation_Success() = runTest {
        // 1. Create finding with high actionability
        val report = ClosedLoopEngine.evaluate(50.0, 65.0, 75.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 100, score = 65.0)
        ))
        val finding = repository.createFindingFromReport(report, "Personalization Boost")
        
        // 2. Propose Improvement
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        assertEquals(IntelligenceLifecycleState.NEEDS_REVIEW, improvement.status)

        // 3. Approve Improvement
        repository.approveImprovement(improvement.id, BlueprintArtifact(strategyBlueprint = finding.technicalDetails))
        val approvedImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.APPROVED, approvedImp?.status)
        assertNotNull(approvedImp?.blueprintArtifactId)

        // 4. Plan Implementation
        repository.planImplementation(improvement.id)
        val plannedImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_PLANNED, plannedImp?.status)

        // 5. Start Implementation
        val runs = repository.getImplementationRuns(improvement.id).first()
        assertTrue(runs.isNotEmpty())
        val runId = runs[0].id
        repository.startImplementation(runId)
        
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_IN_PROGRESS, fakeDao.getImprovementById(improvement.id)?.status)

        // 6. Complete Implementation
        repository.completeImplementation(runId, "Standard implementation", listOf("AISkipEngine.kt"))
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_COMPLETE, fakeDao.getImprovementById(improvement.id)?.status)
    }

    @Test
    fun test02_ImplementationFailure_TransitionsState() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 100, score = 60.0)
        ))
        val finding = repository.createFindingFromReport(report, "Failure Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        repository.approveImprovement(improvement.id, BlueprintArtifact(strategyBlueprint = finding.technicalDetails))
        repository.planImplementation(improvement.id)
        
        val run = repository.getImplementationRuns(improvement.id).first()[0]
        repository.startImplementation(run.id)
        
        // Fail
        repository.failImplementation(run.id, "Build failed")
        
        val failedImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_FAILED, failedImp?.status)
        
        // Retry
        repository.retryImplementation(improvement.id)
        val retriedImp = fakeDao.getImprovementById(improvement.id)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_PLANNED, retriedImp?.status)
        
        val runsAfterRetry = repository.getImplementationRuns(improvement.id).first()
        assertEquals(2, runsAfterRetry.size)
    }

    @Test
    fun test03_ScopeDeviation_TransitionsToDeviationState() = runTest {
        val report = ClosedLoopEngine.evaluate(50.0, 60.0, 70.0, listOf(
            EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 100, score = 60.0)
        ))
        val finding = repository.createFindingFromReport(report, "Deviation Test")
        val improvement = repository.proposeImprovement(finding.id, finding.technicalDetails)
        repository.approveImprovement(improvement.id, BlueprintArtifact(strategyBlueprint = finding.technicalDetails))
        repository.planImplementation(improvement.id)
        val run = repository.getImplementationRuns(improvement.id).first()[0]
        repository.startImplementation(run.id)

        // Complete with unauthorized file
        repository.completeImplementation(run.id, "Changed unauthorized file", listOf("UnauthorizedFile.kt"))
        
        val updatedRun = fakeDao.getImplementationRunById(run.id)
        assertTrue(updatedRun?.deviationDetected == true)
        assertNotNull(updatedRun?.deviationDetails)

        // Improvement state should have transitioned to DEVIATION_DETECTED
        assertEquals(IntelligenceLifecycleState.DEVIATION_DETECTED, fakeDao.getImprovementById(improvement.id)?.status)
        
        // Resolve deviation (reject changes)
        repository.resolveDeviation(improvement.id, false)
        assertEquals(IntelligenceLifecycleState.IMPLEMENTATION_PLANNED, fakeDao.getImprovementById(improvement.id)?.status)
    }

    /**
     * Re-using and extending FakeIntelligenceDao for workflow testing.
     */
    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val artifacts = mutableMapOf<String, BlueprintArtifactEntity>()
        val implementationRuns = mutableMapOf<String, ImplementationRunEntity>()
        val events = mutableListOf<LifecycleEventEntity>()
        val attentionItems = mutableMapOf<String, AttentionItemEntity>()

        override fun getAllFindings() = flowOf(findings.values.toList())
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { findings[finding.id] = finding }
        override suspend fun updateFinding(finding: FindingEntity) { findings[finding.id] = finding }

        override fun getAllImprovements() = flowOf(improvements.values.toList())
        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { improvements[improvement.id] = improvement }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { improvements[improvement.id] = improvement }

        override suspend fun getArtifactById(id: String) = artifacts[id]
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) { artifacts[artifact.id] = artifact }

        override fun getAllImplementationRuns() = flowOf(implementationRuns.values.toList())
        override fun getImplementationRunsForImprovement(improvementId: String) = 
            flowOf(implementationRuns.values.filter { it.improvementId == improvementId })
        override suspend fun getImplementationRunById(id: String) = implementationRuns[id]
        override suspend fun insertImplementationRun(run: ImplementationRunEntity) { implementationRuns[run.id] = run }
        override suspend fun updateImplementationRun(run: ImplementationRunEntity) { implementationRuns[run.id] = run }

        override fun getLifecycleHistory(targetId: String) = flowOf(events.filter { it.targetId == targetId })
        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { events.add(event) }

        override fun getAllAttentionItems() = flowOf(attentionItems.values.toList())
        override suspend fun insertAttentionItem(item: AttentionItemEntity) { attentionItems[item.id] = item }
        override suspend fun deleteActiveAttentionItemByDeduplicationKey(key: String) { 
            attentionItems.entries.removeIf { it.value.deduplicationKey == key } 
        }

        override fun getArtifactsForImprovement(improvementId: String) = 
            flowOf(artifacts.values.filter { it.improvementId == improvementId })

        override fun getPendingEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override fun observeCheckpoint(id: String) = flowOf(null)
    }
}
