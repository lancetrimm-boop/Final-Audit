package com.example.ui.models

import com.example.data.*
import com.example.data.intelligence.IntelligenceMode
import com.example.data.intelligence.IntelligenceRequest

/**
 * Deterministic fixtures for Consumer Mirror.
 */
object MirrorFixtures {

    val mockMediaItems = listOf(
        MediaItem(
            id = "fixture_1",
            title = "Golden Sunset",
            mediaType = "PHOTO",
            genre = "Landscape",
            rating = 5f,
            isFavorite = true,
            aiSummary = "High-contrast cinematic lighting with warm golden tones."
        ),
        MediaItem(
            id = "fixture_2",
            title = "Ocean Waves",
            mediaType = "VIDEO",
            duration = "0:15",
            durationMs = 15000,
            genre = "Nature",
            rating = 4.5f,
            aiSummary = "Dynamic motion with rhythmic blue textures."
        ),
        MediaItem(
            id = "fixture_3",
            title = "Minimalist Architecture",
            mediaType = "PHOTO",
            genre = "Urban",
            rating = 4f,
            aiSummary = "Clean geometric symmetry and high clarity."
        )
    )

    val populatedLibrary = LibraryPresentationState(
        mediaItems = mockMediaItems,
        selectedFilter = "ALL",
        dbState = DatabaseState.READY,
        aiState = AIState.READY,
        provenanceMap = mapOf(
            "fixture_1" to DecisionProvenance(
                rankScore = 0.95,
                primaryRelevance = 0.9f,
                secondaryEvidence = 0.8f,
                provenanceSummary = "Score 0.950 via TASTE_DNA(0.80) + RECENCY(0.15)",
                source = "FIXTURE",
                trace = DecisionTrace(
                    requestId = "trace_perfect_match",
                    surface = "LIBRARY",
                    events = listOf(
                        TraceEvent(type = TraceEventType.REQUEST_RECEIVED, detail = "Mode: PERSONALIZED"),
                        TraceEvent(type = TraceEventType.CANDIDATES_RETRIEVED, detail = "3 items found"),
                        TraceEvent(type = TraceEventType.SCORING_COMPLETED, detail = "TasteDNA alignment: 0.8"),
                        TraceEvent(type = TraceEventType.RANK_ASSIGNED, detail = "#1 position"),
                        TraceEvent(type = TraceEventType.PRESENTATION_MAPPED)
                    )
                )
            ),
            "fixture_2" to DecisionProvenance(
                rankScore = 0.88,
                primaryRelevance = 0.85f,
                secondaryEvidence = 0.7f,
                provenanceSummary = "Score 0.880 via VISUAL_SIMILARITY(0.85)",
                source = "FIXTURE"
            )
        )
    )

    val emptyLibrary = LibraryPresentationState(
        mediaItems = emptyList(),
        dbState = DatabaseState.READY,
        aiState = AIState.READY,
        provenanceMap = emptyMap()
    )

    val populatedDiscover = DiscoverPresentationState(
        obsessions = listOf(
            ObsessionRecommendation(
                id = "obsession_fixture_1",
                title = "Cinematic Escapes",
                subtitle = "Based on your interest in high-contrast landscapes",
                strategy = ObsessionStrategy.DeepDiscovery,
                previewItems = mockMediaItems.take(2),
                confidenceScore = 0.95f
            ),
            ObsessionRecommendation(
                id = "obsession_fixture_2",
                title = "Urban Geometry",
                subtitle = "Exploring patterns in architecture",
                strategy = ObsessionStrategy.NoveltyPulse,
                previewItems = listOf(mockMediaItems[2]),
                confidenceScore = 0.88f
            )
        ),
        isLoading = false,
        provenanceMap = mapOf(
            "fixture_1" to DecisionProvenance(rankScore = 0.99, provenanceSummary = "High match fixture", source = "FIXTURE"),
            "fixture_2" to DecisionProvenance(rankScore = 0.92, provenanceSummary = "Medium match fixture", source = "FIXTURE")
        )
    )

    val emptyDiscover = DiscoverPresentationState(
        obsessions = emptyList(),
        isLoading = false,
        provenanceMap = emptyMap()
    )

    val diagnosticLibrary = LibraryPresentationState(
        mediaItems = mockMediaItems,
        dbState = DatabaseState.READY,
        aiState = AIState.READY,
        provenanceMap = mapOf(
            "fixture_1" to DecisionProvenance(
                rankScore = 0.999,
                primaryRelevance = 1.0f,
                provenanceSummary = "Exact match: known diagnostic",
                source = "DIAGNOSTIC"
            ),
            "fixture_2" to DecisionProvenance(
                rankScore = 0.5,
                primaryRelevance = 0.5f,
                provenanceSummary = "Low confidence: check reranking",
                source = "DIAGNOSTIC"
            )
        )
    )

    /**
     * Phase 5: Controlled Scenarios
     * Phase 7: Golden Expectations
     */
    val scenarios = listOf(
        MirrorScenario(
            id = "scenario_perfect_match",
            name = "Perfect Match",
            description = "High confidence ranking scenario with valid provenance.",
            libraryState = populatedLibrary,
            assertions = listOf(
                MirrorAssertion(MirrorAssertionType.ITEM_PRESENT, "fixture_1", description = "Golden Sunset should exist"),
                MirrorAssertion(MirrorAssertionType.TOP_RANKED, "fixture_1", description = "Golden Sunset should be #1"),
                MirrorAssertion(MirrorAssertionType.SCORE_ABOVE, "fixture_1", 0.9, "Should exceed confidence threshold")
            ),
            goldenExpectation = GoldenExpectation(
                expectedItemIds = listOf("fixture_1", "fixture_2", "fixture_3"),
                expectedTopId = "fixture_1",
                expectedTraceSequence = listOf(
                    TraceEventType.REQUEST_RECEIVED,
                    TraceEventType.CANDIDATES_RETRIEVED,
                    TraceEventType.SCORING_COMPLETED,
                    TraceEventType.RANK_ASSIGNED,
                    TraceEventType.PRESENTATION_MAPPED
                )
            ),
            replayRequest = IntelligenceRequest(
                mode = IntelligenceMode.SORT,
                sortOption = "PERSONALIZED",
                requestId = "replay_perfect_match"
            )
        ),
        MirrorScenario(
            id = "scenario_empty_discovery",
            name = "Empty Discovery",
            description = "Validation of the intelligence empty state logic.",
            discoverState = emptyDiscover,
            assertions = listOf(
                MirrorAssertion(MirrorAssertionType.EMPTY_STATE_TRIGGERED, description = "Should show intelligence awaits message")
            ),
            goldenExpectation = GoldenExpectation(
                expectedItemIds = emptyList()
            ),
            replayRequest = IntelligenceRequest(
                mode = IntelligenceMode.DISCOVER,
                requestId = "replay_empty_discovery"
            )
        ),
        MirrorScenario(
            id = "scenario_low_confidence",
            name = "Low Confidence",
            description = "Scenario exercising reranking boundary.",
            libraryState = diagnosticLibrary,
            assertions = listOf(
                MirrorAssertion(MirrorAssertionType.PROVENANCE_MATCH, "fixture_2", "check reranking", "Verify rerank warning string")
            ),
            goldenExpectation = GoldenExpectation(
                expectedProvenanceFragments = listOf("check reranking")
            ),
            replayRequest = IntelligenceRequest(
                mode = IntelligenceMode.SORT,
                sortOption = "PERSONALIZED",
                requestId = "replay_low_confidence"
            )
        ),
        MirrorScenario(
            id = "scenario_populated_discovery",
            name = "Populated Discovery",
            description = "Verification of deep discovery strategy with items.",
            discoverState = populatedDiscover,
            assertions = listOf(
                MirrorAssertion(MirrorAssertionType.ITEM_PRESENT, "fixture_1", "Golden Sunset should be discovered")
            ),
            goldenExpectation = GoldenExpectation(
                expectedItemIds = listOf("fixture_1", "fixture_2", "fixture_3")
            ),
            replayRequest = IntelligenceRequest(
                mode = IntelligenceMode.DISCOVER,
                sortOption = "DEEP_DISCOVERY",
                requestId = "replay_populated_discovery"
            )
        )
    )
}
