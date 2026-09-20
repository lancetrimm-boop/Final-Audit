package com.example.ui.models

import com.example.data.*
import com.example.data.intelligence.IntelligenceRequest
import com.example.data.semantic.SearchRequest

/**
 * Developer-only validation assertion for Mirror scenarios.
 */
data class MirrorAssertion(
    val type: MirrorAssertionType,
    val targetId: String? = null,
    val expectedValue: Any? = null,
    val description: String = ""
)

enum class MirrorAssertionType {
    ITEM_PRESENT,
    ITEM_ABSENT,
    TOP_RANKED,
    SCORE_ABOVE,
    PROVENANCE_MATCH,
    EMPTY_STATE_TRIGGERED
}

/**
 * Result of a validation check.
 */
data class MirrorValidationResult(
    val assertion: MirrorAssertion,
    val isPassed: Boolean,
    val observedValue: Any? = null,
    val failureCategory: String? = null
)

/**
 * A controlled scenario for developer testing.
 */
data class MirrorScenario(
    val id: String,
    val name: String,
    val description: String,
    val libraryState: LibraryPresentationState? = null,
    val discoverState: DiscoverPresentationState? = null,
    val assertions: List<MirrorAssertion> = emptyList(),
    val goldenExpectation: GoldenExpectation? = null,
    val replayRequest: IntelligenceRequest? = null
)

/**
 * Developer-only stable golden expectation for a scenario.
 */
data class GoldenExpectation(
    val expectedItemIds: List<String> = emptyList(),
    val expectedTopId: String? = null,
    val expectedTraceSequence: List<TraceEventType> = emptyList(),
    val expectedProvenanceFragments: List<String> = emptyList()
)

/**
 * Result of a golden comparison check.
 */
data class GoldenComparisonResult(
    val isMatch: Boolean,
    val mismatches: List<String> = emptyList(),
    val observedTrace: List<TraceEventType> = emptyList()
)

/**
 * Normalized snapshot of observable production behavior.
 */
data class BehavioralSnapshot(
    val scenarioId: String,
    val surface: String,
    val itemIds: List<String>,
    val topRankedId: String?,
    val traceSequence: List<NormalizedTraceEvent>,
    val provenanceSummary: String,
    val isEmpty: Boolean
)

/**
 * Normalized evidence from a production trace event.
 */
data class NormalizedTraceEvent(
    val type: TraceEventType,
    val itemId: String? = null,
    val channel: String? = null,
    val count: Int? = null,
    val score: Double? = null,
    val rank: Int? = null,
    val detail: String = ""
)

/**
 * Entry in the regression matrix.
 */
data class RegressionMatrixEntry(
    val scenarioId: String,
    val scenarioName: String,
    val surface: String,
    val status: RegressionStatus,
    val mismatches: List<String> = emptyList(),
    val failureCategory: String? = null,
    val diff: BehavioralDiff? = null,
    val baselineStatus: BaselineComparisonStatus = BaselineStatus.NOT_VERIFIED,
    val currentSnapshot: BehavioralSnapshot? = null,
    val baselineSnapshot: BehavioralSnapshot? = null,
    val replayStatus: ReproductionStatus = ReproductionStatus.NOT_VERIFIED,
    val replaySnapshot: BehavioralSnapshot? = null,
    val timeline: RegressionTimeline? = null
)

enum class ReproductionStatus {
    REPRODUCED,
    NOT_REPRODUCED,
    NOT_COMPARABLE,
    INSUFFICIENT_EVIDENCE,
    NOT_VERIFIED
}

enum class BaselineStatus {
    UNCHANGED,
    CHANGED,
    NOT_COMPARABLE,
    NOT_VERIFIED
}

typealias BaselineComparisonStatus = BaselineStatus

/**
 * An explicitly approved behavioral baseline.
 */
data class RegressionBaseline(
    val id: String,
    val createdAt: Long,
    val snapshots: Map<String, BehavioralSnapshot> = emptyMap(),
    val approvedBy: String = "DEVELOPER"
)

/**
 * Structural diff of behavioral changes.
 */
data class BehavioralDiff(
    val additions: List<String> = emptyList(),
    val deletions: List<String> = emptyList(),
    val moves: List<String> = emptyList(),
    val otherChanges: List<String> = emptyList(),
    val baselineDiff: Boolean = false
)

enum class RegressionStatus {
    MATCH,
    MISMATCH,
    NOT_COMPARABLE,
    NOT_VERIFIED
}

/**
 * Full results for the regression matrix.
 */
data class RegressionMatrixSummary(
    val entries: List<RegressionMatrixEntry> = emptyList(),
    val matchCount: Int = 0,
    val mismatchCount: Int = 0,
    val totalCount: Int = 0,
    val isRunning: Boolean = false,
    val gateSummary: RegressionGateSummary? = null
)

enum class RegressionGateResult {
    PASS,
    FAIL,
    INSUFFICIENT_EVIDENCE
}

data class RegressionGateSummary(
    val result: RegressionGateResult,
    val entries: List<RegressionMatrixEntry>,
    val totalScenarios: Int,
    val unchangedCount: Int,
    val reproducedCount: Int,
    val notReproducedCount: Int,
    val insufficientEvidenceCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val reportId: String? = null
)

/**
 * Durable record of a completed regression gate.
 */
data class RegressionReport(
    val id: String,
    val timestamp: Long,
    val gateSummary: RegressionGateSummary,
    val buildIdentity: String,
    val baselineId: String?,
    val schemaVersion: Int = 1
)

/**
 * Explicit developer authorization for an implementation state.
 */
data class HandoffSeal(
    val id: String,
    val reportId: String,
    val timestamp: Long,
    val baselineId: String?,
    val buildIdentity: String,
    val scenarioIds: List<String>,
    val schemaVersion: Int = 1
)



/**
 * Developer-only decision trace event.
 * Records an observed fact in the production pipeline.
 */
data class TraceEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val type: TraceEventType,
    val detail: String = "",
    val metadata: Map<String, String> = emptyMap()
)

enum class TraceEventType {
    REQUEST_RECEIVED,
    POOL_FILTERED,
    CHANNEL_RETRIEVAL_START,
    CHANNEL_RETRIEVAL_COMPLETE,
    CANDIDATES_RETRIEVED,
    FUSION_COMPLETED,
    RERANKING_STARTED,
    RERANKING_COMPLETED,
    SCORING_COMPLETED,
    ELIGIBILITY_CHECK,
    RANK_ASSIGNED,
    PROVENANCE_GENERATED,
    PRESENTATION_MAPPED,
    RENDERED
}

/**
 * Zipped chronological evidence comparing Baseline, Evaluation, and Replay.
 */
data class RegressionTimeline(
    val scenarioId: String,
    val events: List<TimelineEvent>,
    val firstDivergenceIndex: Int = -1
)

data class TimelineEvent(
    val type: TraceEventType,
    val detail: String,
    val baselineEvidence: NormalizedTraceEvent? = null,
    val evalEvidence: NormalizedTraceEvent? = null,
    val replayEvidence: NormalizedTraceEvent? = null,
    val isDivergent: Boolean = false
)

/**
 * A complete trace of a production decision.
 */
data class DecisionTrace(
    val requestId: String,
    val surface: String, // LIBRARY, DISCOVER
    val events: List<TraceEvent> = emptyList()
)

/**
 * Developer-only provenance data for a specific decision.
 * Observes facts produced by the production pipeline.
 */
data class DecisionProvenance(
    val rankScore: Double = 0.0,
    val primaryRelevance: Float = 0f,
    val secondaryEvidence: Float = 0f,
    val provenanceSummary: String = "",
    val evidence: List<com.example.data.intelligence.EvidenceItem> = emptyList(),
    val source: String = "PRODUCTION",
    val trace: DecisionTrace? = null
)

/**
 * Minimal presentation state for LibraryScreen.
 * Decouples the UI from direct repository flow collection.
 */
data class LibraryPresentationState(
    val mediaItems: List<MediaItem>,
    val selectedFilter: String = "ALL",
    val activeCategory: SortCategory = SortCategory.STANDARD,
    val standardSort: StandardSortOption = StandardSortOption.NEWEST_FIRST,
    val intelligentSort: IntelligentSortOption = IntelligentSortOption.PERSONALIZED,
    val searchRequest: SearchRequest = SearchRequest.Text(""),
    val searchError: String? = null,
    val activeVisualReferences: List<MediaItem> = emptyList(),
    val aiState: AIState = AIState.READY,
    val dbState: DatabaseState = DatabaseState.READY,
    val isAutoScrollActive: Boolean = false,
    val autoScrollSpeed: AutoScrollSpeed = AutoScrollSpeed.MEDIUM,
    val gridDensity: Float = 160f,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val importProgress: ImportProgressState = ImportProgressState(),
    val scanProgress: ScanProgressState = ScanProgressState(),
    val provenanceMap: Map<String, DecisionProvenance> = emptyMap()
)

/**
 * Minimal presentation state for DiscoverScreen.
 */
data class DiscoverPresentationState(
    val obsessions: List<ObsessionRecommendation> = emptyList(),
    val systemState: SystemDiscoveryState = SystemDiscoveryState(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tasteReveal: TasteReveal? = null,
    val tasteDNA: TasteDNA = TasteDNA(),
    val preferenceProfile: TasteDNA.PreferenceProfile = TasteDNA.PreferenceProfile(),
    val discoveryPolicy: DiscoveryPolicy = DiscoveryPolicy(),
    val provenanceMap: Map<String, DecisionProvenance> = emptyMap()
)
