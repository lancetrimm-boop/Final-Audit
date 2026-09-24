package com.example.data

import com.example.data.db.*
import com.example.data.blueprint.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MasterReportIntegrationTest {

    private lateinit var repository: IntelligenceRepository
    private lateinit var fakeDao: FakeIntelligenceDao

    @Before
    fun setup() {
        fakeDao = FakeIntelligenceDao()
        repository = IntelligenceRepository(fakeDao)
    }

    @Test
    fun test01_GenerateAndSnapshotReport() = runTest {
        // 1. Create finding (auto-actioned if quality is high)
        val evidence = listOf(EvidenceRecord(tier = EvidenceTier.PRODUCTION, sampleCount = 10, score = 65.0, quality = 0.9))
        val report = ClosedLoopEngine.evaluate(50.0, 65.0, 70.0, evidence)
        val finding = repository.createFindingFromReport(report, "Personalization Optimization")
        
        // Snapshot the current system state
        val snapshot = repository.generateMasterReport(0L, isSnapshot = true)
        assertTrue(snapshot.isSnapshot)
        
        // Actionable findings are automatically moved to improvement pipeline in repository implementation.
        // So we expect 1 improvement if evaluationActionability returns true.
        assertEquals(1, snapshot.improvementPipeline.size) 
        
        // New live report should have the same improvement
        val liveReport = repository.generateMasterReport(0L)
        assertFalse(liveReport.isSnapshot)
        assertEquals(1, liveReport.improvementPipeline.size)
        
        // Verify cross-lineage consistency
        assertEquals(snapshot.improvementPipeline[0].id, liveReport.improvementPipeline[0].id)
    }

    class FakeIntelligenceDao : StubIntelligenceDao() {
        val findings = mutableMapOf<String, FindingEntity>()
        val improvements = mutableMapOf<String, SuggestedImprovementEntity>()
        val events = mutableListOf<LifecycleEventEntity>()
        val artifacts = mutableMapOf<String, BlueprintArtifactEntity>()
        val checkpointFlow = MutableStateFlow<UserCheckpointEntity?>(null)

        override fun getAllFindings() = MutableStateFlow(findings.values.toList())
        override suspend fun getFindingById(id: String) = findings[id]
        override suspend fun insertFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
        }
        override suspend fun updateFinding(finding: FindingEntity) { 
            findings[finding.id] = finding 
        }

        override fun getAllImprovements() = MutableStateFlow(improvements.values.toList())
        override suspend fun getImprovementById(id: String) = improvements[id]
        override suspend fun insertImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
        }
        override suspend fun updateImprovement(improvement: SuggestedImprovementEntity) { 
            improvements[improvement.id] = improvement 
        }
        override fun getImprovementsForFinding(findingId: String) = MutableStateFlow(improvements.values.filter { it.findingId == findingId })

        override suspend fun insertLifecycleEvent(event: LifecycleEventEntity) { events.add(event) }
        override fun getLifecycleHistory(targetId: String) = MutableStateFlow(events.filter { it.targetId == targetId })

        override fun getArtifactsForImprovement(improvementId: String) = MutableStateFlow(artifacts.values.filter { it.improvementId == improvementId })
        override suspend fun insertArtifact(artifact: BlueprintArtifactEntity) { artifacts[artifact.id] = artifact }

        override suspend fun insertCheckpoint(checkpoint: UserCheckpointEntity) { checkpointFlow.value = checkpoint }
        override fun observeCheckpoint(id: String) = checkpointFlow
        
        override fun getPendingEvents() = flowOf(emptyList<IntelligenceEventEntity>())
        override fun getAllStoredEvidence() = flowOf(emptyList<EvidenceEntity>())
        override fun getValidationResultsForImprovement(improvementId: String) = flowOf(emptyList<ValidationResultEntity>())
    }
}
