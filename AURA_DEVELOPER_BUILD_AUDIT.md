# Aura Media Player — Developer Build Redesign Audit

## 1. Current Developer Build Architecture

The current developer build is primarily centered around the `Aura Intelligence` dashboard, implemented in `AuraIntelligenceScreen.kt`. It uses a tabbed navigation structure based on the `IntelligenceSection` enum.

### **Navigation Graph (Developer-Only)**
- **OVERVIEW**: Executive metrics and "Decision Center" summary.
- **INBOX**: Incoming actionable findings.
- **FINDINGS**: Database of behavioral/content patterns (`FindingEntity`).
- **RECOMMENDATIONS**: User-authorized optimizations (`SuggestedImprovementEntity`).
- **DECISIONS**: Central hub for approving/rejecting modifications.
- **EXECUTION**: Status of active system changes (`ImplementationRunEntity`).
- **REPORTS**: High-level briefings on domains like Engagement and Technical Health.
- **SOCIAL_DISCOVERY**: Experimental Social POC (Developer-only).
- **DEBUGGER**: Technical signal and cleanup diagnostics.
- **WORKSPACE**: Blueprint strategy testing environment.
- **HISTORY**: Audit log of intelligence lifecycle events (`LifecycleEventEntity`).

### **Major Components**
- **Repository**: `IntelligenceRepository.kt` handles the state machine for all intelligence entities.
- **Logic**: `AuraIntelligenceCore.kt` orchestrates ranking and retrieval.
- **Signals**: `InteractionRepository.kt` captures raw user behavior.

---

## 2. Release UI Reuse Feasibility

A core goal of the **Consumer Mirror** is to reuse release-build components without duplication.

| Production Component | File Path | Reuse Status | Requirement |
| :--- | :--- | :--- | :--- |
| **AuraMediaTile** | `AuraMediaCard.kt` | **READY TO REUSE** | "Pure" component; accepts `MediaItem` data objects. |
| **ImmersiveMediaCard** | `DiscoverScreen.kt` | **READY TO REUSE** | "Pure" component; used in Discovery feed. |
| **ObsessionCard** | `DiscoverScreen.kt` | **READY TO REUSE** | Reusable for Mirror discovery sections. |
| **LibraryScreen** | `LibraryScreen.kt` | **REUSE WITH ADAPTER** | Tightly coupled to `MediaRepository` flows; needs injection of static list/state. |
| **DiscoverScreen** | `DiscoverScreen.kt` | **REUSE WITH ADAPTER** | Tightly coupled to `DiscoverViewModel`; requires state injection. |
| **MediaDetailScreen** | `MediaDetailScreen.kt` | **REUSE WITH ADAPTER** | Depends on `MediaRepository` for playback controls. |

**Production Code Changes Required**:
- Refactor `LibraryScreen` to accept an optional `List<MediaItem>` override parameter to bypass live repository state for historical Mirror views.

---

## 3. Existing Data/Event Infrastructure

Aura has high-fidelity data but it is currently siloed across specialized tables.

| Data Type | Persistence Source | Current Pipeline Role |
| :--- | :--- | :--- |
| **Interactions** | `micro_moments`, `ai_skip_events`, `pairwise_outcomes` | Disconnected signal stores. |
| **Inferences** | `intelligence_findings` | Patterns identified from signals. |
| **Decisions** | `suggested_improvements`, `attention_items` | Human-authorized system changes. |
| **Actions** | `implementation_runs`, `intelligence_actions` | Execution status of authorized changes. |
| **Outcomes** | `validation_results_history`, `evidence_records` | Performance impact measurement. |

**Missing Provenance Foundation**:
- There is no unified `ProvenanceEvent` table that links a `signal` → `finding` → `action` in a single queryable chain. Relationships are currently maintained via `improvementId` or `findingId` foreign keys across multiple tables.

---

## 4. Replay Feasibility

**Status**: `REPRODUCIBLE REPLAY INCOMPLETE`.

- **Preserved Inputs**: `IntelligenceRequest` captures the `seed`, `TasteDNA` snapshot, and `policy`.
- **Missing Inputs**: The specific "Candidate Set" (the list of media items available at the time) is not currently persisted for non-authorized events (e.g., a standard scroll). 
- **Recommendation**: To support full Replay, `Pipeline` must optionally log the `requestId` and input parameters for critical consumer-facing decisions.

---

## 5. Performance Instrumentation

**Existing**:
- `AuraIntelligenceCore` tracks `latencyMs` for every retrieval/ranking request.
- `TechnicalHealthIntelligence` tracks `crashRate` and `startupTimeMs`.

**Missing**:
- End-to-end Compose rendering duration.
- Memory/Battery impact of `MobileCLIP` embedding generation in real-world workflows.
- Contextual instrumentation (e.g., showing a latency badge on an `ObsessionCard` in Mirror).

---

## 6. Trust Gates

**Inventory**:
- `BlueprintImplementationValidator`: Confirms code-level compliance with AI strategies.
- `AuraCompatibilityEngine`: Gates non-playable media.
- `ConfidenceEngine`: Evaluates global system state reliability.

**Gaps**:
- No automated "Trust Violation" event surfaced to the user/developer when a ranking significantly deviates from Taste DNA bounds.

---

## 7. Taste DNA Observability

**Confirmed by Source**: `TuningAuditEntity` in `Entities.kt`.
- Records `previousValue` vs `newValue`.
- Includes `evidenceCategory` (e.g., "Pairwise Vote", "Manual Tuning").
- **Audit Result**: Historical snapshots exist, but they are not currently visualized as a "Delta Timeline" in the developer build.

---

## 8. Agency / Approval Infrastructure

**Status**: `AGENCY LEDGER IMPLEMENTED`.
- `SuggestedImprovement` has a strict lifecycle: `SUGGESTED` → `APPROVED` → `IMPLEMENTING` → `VALIDATED`.
- `AuraDecisionCenterScreen` serves as the Agency hub.
- **Gap**: Automated "Outcome Correlation" (linking an approval to a specific metric improvement) is currently calculated in the `IntelligenceReportingEngine` but not linked as a first-class provenance event.

---

## 9. Labs Inventory

| Experiment | Component | Recommended Status |
| :--- | :--- | :--- |
| **Social Discovery** | `UniversalSocialDiscoveryScreen` | **KEEP IN LABS** (POC stage). |
| **Blueprint strategy** | `BlueprintWorkspaceScreen` | **KEEP IN LABS** (Tool for strategy creation). |
| **Engagement Debugger** | `EngagementDebuggerScreen` | **REPLACE** (Move instrumentation to Mirror). |

---

## 10. Configuration Inventory

- `BuildConfig.ENABLE_DEVELOPER_TOOLS`: Root gate for developer UI.
- `UserPreferenceEntity`: Key-value store for features flags and current sort modes.
- **Audit Result**: Configuration is fragmented; no unified "Dev Settings" screen exists for resetting state safely.

---

## 11. Navigation Consolidation Map

| Current Screen | Proposed Destination | Rationale |
| :--- | :--- | :--- |
| Overview / Inbox | **Pipeline** | Collapsed into a "Briefing" header in the timeline. |
| Findings / Recommendations | **Pipeline** | Stages of the event chain. |
| Execution / History | **Pipeline** | Post-decision lifecycle events. |
| (New Mode) | **Consumer Mirror** | Reuses production screens with diagnostic overlays. |
| Social POC / Workspace | **Labs** | Isolated experimental areas. |
| Debugger / Configuration | **Shared Infrastructure** | Global dev tools accessible from all modes. |

---

## 12. Recommended Implementation Order

1. **Phase 1 — Shared Instrumentation (Performance)**: Surface existing Core latency in production components.
2. **Phase 2 — UI Adapter Work**: Modify `LibraryScreen` and `DiscoverScreen` to allow state injection.
3. **Phase 3 — Consumer Mirror**: Implement the Mirror mode using production renderers.
4. **Phase 4 — Pipeline Engine**: Create the unified Provenance event stream from existing entities.
5. **Phase 5 — Pipeline UI**: Replace fragmented tabs with the chronological timeline.
6. **Phase 6 — Labs Quarantine**: Explicitly isolate POCs from production DNA updates.

---

## 13. Highest-Risk Items
1. **Mirror Coupling**: Tightly coupled ViewModels in `LibraryScreen` may resist injection, requiring significant production refactoring.
2. **Provenance Performance**: Querying across 11+ tables for a single chronological timeline may cause stutter in the Pipeline UI.
3. **Replay Accuracy**: Variations in on-device model state (embeddings) over time may make exact historical reconstruction impossible without full vector snapshots.

---

## 14. Required Production-Code Changes
1. **Composables**: Convert `LibraryScreen` and `DiscoverScreen` to accept `List<MediaItem>` or `State` as parameters rather than collecting from global repositories internally.
2. **DAOs**: Implement a `UnifiedProvenanceDao` to join interactions, findings, and actions.
3. **Logging**: Add `requestId` persistence to standard library sort requests to enable future Replay.

---

## 15. Recommended First Implementation Task
**Smallest Safe Task**: "Contextual Latency Overlay".
- Add a hidden diagnostic layer to `AuraMediaTile` that displays `AuraIntelligenceCore` ranking latency when `ENABLE_DEVELOPER_TOOLS` is on. This validates performance instrumentation in a production renderer without altering UI structure.

---
*Audit performed on 2026-09-16. Final Status: AUDIT COMPLETE — READY FOR PHASED IMPLEMENTATION*
