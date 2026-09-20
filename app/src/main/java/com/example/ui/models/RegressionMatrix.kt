package com.example.ui.models

import com.example.data.intelligence.AuraIntelligenceCore
import com.example.data.intelligence.IntelligenceResponse
import com.example.data.intelligence.DecisionTraceCollector

/**
 * Manager for the developer-only Regression Matrix.
 */
object RegressionMatrix {

    fun execute(scenarios: List<MirrorScenario>, baseline: RegressionBaseline? = null): RegressionMatrixSummary {
        val entries = scenarios.map { scenario ->
            val libState = scenario.libraryState
            val discoverState = scenario.discoverState
            
            val result = compareGoldenInternal(scenario, libState, discoverState)
            val diff = if (!result.isMatch) calculateDiff(scenario, libState, discoverState) else null
            val currentSnapshot = takeSnapshot(scenario.id, libState, discoverState)

            val entry = RegressionMatrixEntry(
                scenarioId = scenario.id,
                scenarioName = scenario.name,
                surface = if (libState != null) "LIBRARY" else "DISCOVER",
                status = if (result.isMatch) RegressionStatus.MATCH else RegressionStatus.MISMATCH,
                mismatches = result.mismatches,
                failureCategory = if (!result.isMatch) diagnoseFailure(scenario, result) else null,
                diff = diff,
                currentSnapshot = currentSnapshot,
                baselineSnapshot = baseline?.snapshots?.get(scenario.id)
            )
            
            entry.copy(baselineStatus = BaselineManager.compare(currentSnapshot, baseline))
        }

        return RegressionMatrixSummary(
            entries = entries,
            matchCount = entries.count { it.status == RegressionStatus.MATCH },
            mismatchCount = entries.count { it.status == RegressionStatus.MISMATCH },
            totalCount = entries.size,
            isRunning = false
        )
    }

    private fun compareGoldenInternal(
        scenario: MirrorScenario,
        libState: LibraryPresentationState?,
        discoverState: DiscoverPresentationState?
    ): GoldenComparisonResult {
        val expectation = scenario.goldenExpectation ?: return GoldenComparisonResult(true)
        val mismatches = mutableListOf<String>()
        
        val actualIds = libState?.mediaItems?.map { it.id } ?: discoverState?.obsessions?.map { it.id } ?: emptyList()
        if (expectation.expectedItemIds.isNotEmpty() && actualIds != expectation.expectedItemIds) {
            mismatches.add("Items count/list mismatch: Expected ${expectation.expectedItemIds.size}, Observed ${actualIds.size}")
        }

        val actualTop = actualIds.firstOrNull()
        if (expectation.expectedTopId != null && actualTop != expectation.expectedTopId) {
            mismatches.add("Top Rank mismatch: Expected ${expectation.expectedTopId}, Observed $actualTop")
        }

        val topProv = if (libState != null) libState.provenanceMap[actualTop ?: ""] else discoverState?.provenanceMap?.get(actualTop ?: "")
        val actualTrace = topProv?.trace?.events?.map { it.type } ?: emptyList()
        
        if (expectation.expectedTraceSequence.isNotEmpty() && actualTrace != expectation.expectedTraceSequence) {
            mismatches.add("Trace sequence divergence")
        }

        val provSummary = topProv?.provenanceSummary ?: ""
        expectation.expectedProvenanceFragments.forEach { fragment ->
            if (!provSummary.contains(fragment, ignoreCase = true)) {
                mismatches.add("Provenance: Missing '$fragment'")
            }
        }

        return GoldenComparisonResult(mismatches.isEmpty(), mismatches, actualTrace)
    }

    private fun calculateDiff(
        scenario: MirrorScenario,
        libState: LibraryPresentationState?,
        discoverState: DiscoverPresentationState?
    ): BehavioralDiff {
        val expectation = scenario.goldenExpectation ?: return BehavioralDiff()
        val actualIds = libState?.mediaItems?.map { it.id } ?: discoverState?.obsessions?.map { it.id } ?: emptyList()
        
        val expectedSet = expectation.expectedItemIds.toSet()
        val actualSet = actualIds.toSet()
        
        val additions = actualIds.filter { it !in expectedSet }
        val deletions = expectation.expectedItemIds.filter { it !in actualSet }
        val moves = mutableListOf<String>()
        
        if (expectation.expectedItemIds.isNotEmpty() && actualIds.isNotEmpty()) {
            val common = actualIds.filter { it in expectedSet }
            val expectedCommon = expectation.expectedItemIds.filter { it in actualSet }
            if (common != expectedCommon) {
                moves.add("Item sequence changed")
            }
        }
        
        val other = mutableListOf<String>()
        if (expectation.expectedTopId != null && actualIds.firstOrNull() != expectation.expectedTopId) {
            other.add("Rank 1: ${expectation.expectedTopId} -> ${actualIds.firstOrNull() ?: "None"}")
        }
        
        return BehavioralDiff(additions, deletions, moves, other)
    }

    suspend fun runGate(
        scenarios: List<MirrorScenario>,
        core: AuraIntelligenceCore,
        baseline: RegressionBaseline?
    ): RegressionGateSummary {
        val entries = mutableListOf<RegressionMatrixEntry>()
        
        for (scenario in scenarios) {
            val replayRequest = scenario.replayRequest ?: continue
            
            try {
                // Pass 1: EVALUATION (Live Context)
                val evalRequest = replayRequest.copy(skipPersistence = true, poolOverride = null)
                val evalResponse = core.processRequest(evalRequest)
                val evalTrace = DecisionTraceCollector.getTrace(evalRequest.requestId)
                val liveSnapshot = takeSnapshot(scenario.id, evalResponse, evalTrace)
                
                val baselineSnapshot = baseline?.snapshots?.get(scenario.id)
                val baselineStatus = if (baselineSnapshot != null) {
                    if (liveSnapshot == baselineSnapshot) BaselineStatus.UNCHANGED else BaselineStatus.CHANGED
                } else BaselineStatus.NOT_VERIFIED

                var entry = RegressionMatrixEntry(
                    scenarioId = scenario.id,
                    scenarioName = scenario.name,
                    surface = liveSnapshot.surface,
                    status = if (baselineStatus == BaselineStatus.UNCHANGED) RegressionStatus.MATCH else RegressionStatus.MISMATCH,
                    baselineStatus = baselineStatus,
                    currentSnapshot = liveSnapshot,
                    baselineSnapshot = baselineSnapshot
                )

                // Pass 2: REPLAY (Deterministic Context) - Only if deviation detected
                if (baselineStatus == BaselineStatus.CHANGED) {
                    entry = try {
                        replay(entry, core)
                    } catch (e: Exception) {
                        entry.copy(
                            replayStatus = ReproductionStatus.INSUFFICIENT_EVIDENCE,
                            failureCategory = "REPLAY_FAILURE"
                        )
                    }
                }
                
                entries.add(entry)
            } catch (e: Exception) {
                entries.add(RegressionMatrixEntry(
                    scenarioId = scenario.id,
                    scenarioName = scenario.name,
                    surface = "UNKNOWN",
                    status = RegressionStatus.NOT_VERIFIED,
                    replayStatus = ReproductionStatus.INSUFFICIENT_EVIDENCE,
                    failureCategory = "EVALUATION_FAILURE"
                ))
            }
        }

        val summary = summarizeGate(entries)
        
        // Phase 15: Persist Report and capture ID
        val reportId = RegressionArtifactManager.createReport(summary, baseline?.id)
        
        return summary.copy(reportId = reportId)
    }

    private fun summarizeGate(entries: List<RegressionMatrixEntry>): RegressionGateSummary {
        val reproducedCount = entries.count { it.status == RegressionStatus.MISMATCH && it.replayStatus == ReproductionStatus.REPRODUCED }
        val insufficientCount = entries.count { 
            it.status == RegressionStatus.NOT_VERIFIED ||
            (it.status == RegressionStatus.MISMATCH && (it.replayStatus == ReproductionStatus.NOT_VERIFIED || it.replayStatus == ReproductionStatus.INSUFFICIENT_EVIDENCE))
        }
        
        val result = when {
            reproducedCount > 0 -> RegressionGateResult.FAIL
            insufficientCount > 0 -> RegressionGateResult.INSUFFICIENT_EVIDENCE
            entries.isEmpty() && MirrorFixtures.scenarios.isNotEmpty() -> RegressionGateResult.INSUFFICIENT_EVIDENCE
            else -> RegressionGateResult.PASS
        }

        return RegressionGateSummary(
            result = result,
            entries = entries,
            totalScenarios = entries.size,
            unchangedCount = entries.count { it.baselineStatus == BaselineStatus.UNCHANGED },
            reproducedCount = reproducedCount,
            notReproducedCount = entries.count { it.replayStatus == ReproductionStatus.NOT_REPRODUCED },
            insufficientEvidenceCount = insufficientCount
        )
    }

    private fun diagnoseFailure(scenario: MirrorScenario, result: GoldenComparisonResult): String {
        val trace = result.observedTrace
        if (result.mismatches.any { it.contains("Top Rank") }) {
            return if (trace.contains(TraceEventType.RANK_ASSIGNED)) "RANKING OUTPUT REGRESSION" else "INSUFFICIENT EVIDENCE"
        }
        if (result.mismatches.any { it.contains("Items") }) {
            return if (trace.contains(TraceEventType.CANDIDATES_RETRIEVED)) "PRESENTATION MAPPING REGRESSION" else "CANDIDATE GENERATION REGRESSION"
        }
        if (result.mismatches.any { it.contains("Provenance") }) return "PROVENANCE REGRESSION"
        if (result.mismatches.any { it.contains("Trace") }) return "TECHNICAL PATH REGRESSION"
        
        return "INSUFFICIENT EVIDENCE"
    }

    fun takeSnapshot(scenarioId: String, libState: LibraryPresentationState?, discoverState: DiscoverPresentationState?): BehavioralSnapshot {
        val rawIds = libState?.mediaItems?.map { it.id } ?: discoverState?.obsessions?.map { it.id } ?: emptyList()
        val actualIds = rawIds.map { hashId(it) }
        val actualTop = actualIds.firstOrNull()
        
        val topProv = if (libState != null) {
            val topId = libState.mediaItems.firstOrNull()?.id
            libState.provenanceMap[topId ?: ""]
        } else {
            val topId = discoverState?.obsessions?.firstOrNull()?.id
            discoverState?.provenanceMap?.get(topId ?: "")
        }
        
        return BehavioralSnapshot(
            scenarioId = scenarioId,
            surface = if (libState != null) "LIBRARY" else if (discoverState != null) "DISCOVER" else "UNKNOWN",
            itemIds = actualIds,
            topRankedId = actualTop,
            traceSequence = topProv?.trace?.events?.map { normalizeEvent(it) } ?: emptyList(),
            provenanceSummary = topProv?.provenanceSummary ?: "",
            isEmpty = actualIds.isEmpty()
        )
    }

    fun takeSnapshot(scenarioId: String, response: IntelligenceResponse, trace: DecisionTrace?): BehavioralSnapshot {
        val actualIds = response.candidates.map { hashId(it.item.id) }
        val actualTop = actualIds.firstOrNull()
        val topCandidate = response.candidates.firstOrNull()
        
        // Normalize surface name to match Presentation state
        val surface = when (response.mode.name) {
            "SORT", "SEARCH", "SIMILAR" -> "LIBRARY"
            "DISCOVER" -> "DISCOVER"
            else -> response.mode.name
        }

        return BehavioralSnapshot(
            scenarioId = scenarioId,
            surface = surface,
            itemIds = actualIds,
            topRankedId = actualTop,
            traceSequence = trace?.events?.map { normalizeEvent(it) } ?: emptyList(),
            provenanceSummary = topCandidate?.provenance ?: "",
            isEmpty = actualIds.isEmpty()
        )
    }

    internal fun hashId(id: String): String {
        return "h_" + id.hashCode().toString()
    }

    internal fun normalizeEvent(event: TraceEvent): NormalizedTraceEvent {
        val metadata = event.metadata
        val type = event.type
        // Normalize detail for REQUEST_RECEIVED to avoid surface name mismatches
        val detail = if (type == TraceEventType.REQUEST_RECEIVED) "NORMALIZED" else event.detail
        
        return NormalizedTraceEvent(
            type = type,
            itemId = metadata["itemId_hash"] ?: metadata["itemId"],
            channel = metadata["channel"],
            count = metadata["count"]?.toIntOrNull(),
            score = metadata["score"]?.toDoubleOrNull()?.let { Math.round(it * 10000.0) / 10000.0 },
            rank = metadata["rank"]?.toIntOrNull(),
            detail = detail
        )
    }

    suspend fun replay(entry: RegressionMatrixEntry, core: AuraIntelligenceCore?): RegressionMatrixEntry {
        val scenario = MirrorFixtures.scenarios.find { it.id == entry.scenarioId } ?: return entry
        val baseRequest = scenario.replayRequest ?: return replayFixturePath(entry, scenario)

        if (core == null) return entry.copy(replayStatus = ReproductionStatus.NOT_VERIFIED)

        // 1. PREPARE DETERMINISTIC INPUTS
        val deterministicPool = scenario.libraryState?.mediaItems 
            ?: scenario.discoverState?.obsessions?.flatMap { it.previewItems }?.distinctBy { it.id }

        val replayRequest = baseRequest.copy(
            skipPersistence = true,
            poolOverride = deterministicPool
        )

        // 2. PRODUCTION PATH EXECUTION
        val response = core.processRequest(replayRequest)
        val trace = DecisionTraceCollector.getTrace(replayRequest.requestId)

        // 3. CAPTURE FRESH SNAPSHOT
        val freshSnapshot = takeSnapshot(scenario.id, response, trace)
        
        // 4. BUILD EVIDENCE TIMELINE
        val timeline = buildTimeline(scenario.id, entry.baselineSnapshot, entry.currentSnapshot, freshSnapshot)

        // 5. DETERMINE REPRODUCTION STATUS
        val replayStatus = if (freshSnapshot == entry.currentSnapshot) {
            ReproductionStatus.REPRODUCED
        } else {
            ReproductionStatus.NOT_REPRODUCED
        }

        return entry.copy(
            replayStatus = replayStatus,
            replaySnapshot = freshSnapshot,
            timeline = timeline,
            failureCategory = if (replayStatus == ReproductionStatus.REPRODUCED) {
                diagnoseDivergence(entry.baselineSnapshot, freshSnapshot)
            } else if (replayStatus == ReproductionStatus.NOT_REPRODUCED) {
                diagnoseDivergence(entry.currentSnapshot, freshSnapshot)
            } else entry.failureCategory
        )
    }

    internal fun buildTimeline(
        scenarioId: String,
        baseline: BehavioralSnapshot?,
        eval: BehavioralSnapshot?,
        replay: BehavioralSnapshot?
    ): RegressionTimeline {
        val events = mutableListOf<TimelineEvent>()
        
        val baselineTrace = baseline?.traceSequence ?: emptyList()
        val evalTrace = eval?.traceSequence ?: emptyList()
        val currentReplayTrace = replay?.traceSequence ?: emptyList()

        val maxLen = maxOf(baselineTrace.size, evalTrace.size, currentReplayTrace.size)

        for (i in 0 until maxLen) {
            val baseEv = baselineTrace.getOrNull(i)
            val evalEv = evalTrace.getOrNull(i)
            val repEv = currentReplayTrace.getOrNull(i)

            // Primary comparison is between Baseline and Eval/Replay
            val isDivergent = when {
                baseline != null && baseEv == null && (evalEv != null || repEv != null) -> true // Extra event
                baseline != null && baseEv != null && (evalEv == null && repEv == null) -> true // Missing event
                baseEv != null && evalEv != null && baseEv != evalEv -> true // Value or type divergence
                baseEv != null && repEv != null && baseEv != repEv -> true // Value or type divergence
                else -> false
            }

            val type = baseEv?.type ?: evalEv?.type ?: repEv?.type ?: TraceEventType.PRESENTATION_MAPPED
            val detail = baseEv?.detail ?: evalEv?.detail ?: repEv?.detail ?: ""

            events.add(TimelineEvent(
                type = type,
                detail = detail,
                baselineEvidence = baseEv,
                evalEvidence = evalEv,
                replayEvidence = repEv,
                isDivergent = isDivergent
            ))
        }

        return RegressionTimeline(
            scenarioId = scenarioId,
            events = events,
            firstDivergenceIndex = events.indexOfFirst { it.isDivergent }
        )
    }

    private fun replayFixturePath(entry: RegressionMatrixEntry, scenario: MirrorScenario): RegressionMatrixEntry {
        // Fallback for scenarios without a replay request (Phase 11 legacy)
        val freshSnapshot = takeSnapshot(scenario.id, scenario.libraryState, scenario.discoverState)
        val replayStatus = if (freshSnapshot == entry.currentSnapshot) {
            ReproductionStatus.REPRODUCED
        } else {
            ReproductionStatus.NOT_REPRODUCED
        }
        return entry.copy(replayStatus = replayStatus, replaySnapshot = freshSnapshot)
    }

    private fun diagnoseDivergence(original: BehavioralSnapshot?, replay: BehavioralSnapshot?): String {
        if (original == null || replay == null) return "INSUFFICIENT_EVIDENCE"
        
        // Prioritize granular categories over generic ones by scanning for specific attribute differences
        var hasEligibilityDivergence = false
        var hasScoringDivergence = false
        var hasRankingDivergence = false
        var hasCandidateDivergence = false
        
        for (i in replay.traceSequence.indices) {
            val orig = original.traceSequence.getOrNull(i)
            val rep = replay.traceSequence[i]
            if (orig != rep) {
                if (orig?.score != rep.score && (orig?.score != null || rep.score != null)) {
                    hasScoringDivergence = true
                }
                if (orig?.count != rep.count && (orig?.count != null || rep.count != null)) {
                    hasCandidateDivergence = true
                }
                if (orig?.rank != rep.rank && (orig?.rank != null || rep.rank != null)) {
                    hasRankingDivergence = true
                }
                if (rep.type == TraceEventType.ELIGIBILITY_CHECK || orig?.type == TraceEventType.ELIGIBILITY_CHECK) {
                    hasEligibilityDivergence = true
                }
            }
        }
        
        if (hasScoringDivergence) return "SCORING_DIVERGENCE"
        if (hasCandidateDivergence) return "CANDIDATE_GENERATION_DIVERGENCE"
        if (hasRankingDivergence) return "RANKING_DIVERGENCE"
        if (hasEligibilityDivergence) return "ELIGIBILITY_DIVERGENCE"

        // 1. Trace-based Root Cause detection (Earliest meaningful divergence)
        val firstDivergenceIndex = replay.traceSequence.indices.firstOrNull { i ->
            val orig = original.traceSequence.getOrNull(i)
            val rep = replay.traceSequence[i]
            orig != rep
        }

        if (firstDivergenceIndex != null) {
            val event = replay.traceSequence[firstDivergenceIndex]
            return when (event.type) {
                TraceEventType.REQUEST_RECEIVED,
                TraceEventType.CHANNEL_RETRIEVAL_START,
                TraceEventType.CHANNEL_RETRIEVAL_COMPLETE, 
                TraceEventType.CANDIDATES_RETRIEVED -> "CANDIDATE_GENERATION_DIVERGENCE"
                TraceEventType.ELIGIBILITY_CHECK -> "ELIGIBILITY_DIVERGENCE"
                TraceEventType.SCORING_COMPLETED -> "SCORING_DIVERGENCE"
                TraceEventType.RANK_ASSIGNED -> "RANKING_DIVERGENCE"
                TraceEventType.PROVENANCE_GENERATED -> "PROVENANCE_DIVERGENCE"
                TraceEventType.PRESENTATION_MAPPED -> "PRESENTATION_DIVERGENCE"
                else -> "TRACE_DIVERGENCE"
            }
        }

        // 2. Fallback to top-level property checks if trace is identical or missing
        val originalIds = original.itemIds
        val replayIds = replay.itemIds
        
        if (originalIds.size != replayIds.size || originalIds.toSet() != replayIds.toSet()) {
            return "CANDIDATE_GENERATION_DIVERGENCE"
        }
        
        if (originalIds != replayIds || original.topRankedId != replay.topRankedId) {
            return "RANKING_DIVERGENCE"
        }
        
        if (original.provenanceSummary != replay.provenanceSummary) return "PROVENANCE_DIVERGENCE"
        
        return "UNKNOWN_DIVERGENCE"
    }
}
