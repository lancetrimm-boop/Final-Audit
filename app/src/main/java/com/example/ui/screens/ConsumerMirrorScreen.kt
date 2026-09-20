package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.data.MediaRepository
import com.example.data.SortCategory
import com.example.data.intelligence.OperationPerformance
import com.example.ui.models.DiscoverPresentationState
import com.example.ui.models.LibraryPresentationState
import com.example.ui.models.MirrorFixtures
import com.example.ui.models.toLibraryItemUi
import com.example.ui.models.MirrorScenario
import com.example.ui.models.MirrorValidationResult
import com.example.ui.models.MirrorAssertion
import com.example.ui.models.MirrorAssertionType
import com.example.ui.models.GoldenExpectation
import com.example.ui.models.GoldenComparisonResult
import com.example.ui.models.DecisionTrace
import com.example.ui.models.TraceEvent
import com.example.ui.models.TraceEventType
import com.example.ui.models.RegressionMatrix
import com.example.ui.models.RegressionMatrixSummary
import com.example.ui.models.RegressionStatus
import com.example.ui.models.BaselineManager
import com.example.ui.models.BaselineStatus
import com.example.ui.models.RegressionBaseline
import com.example.ui.models.ReproductionStatus
import com.example.ui.models.RegressionArtifactManager
import com.example.ui.models.RegressionGateResult
import com.example.ui.models.RegressionReport
import com.example.ui.models.HandoffSeal
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraPurple
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.DiscoveryViolet
import com.example.data.IntelligenceRepository
import com.example.ui.screens.ObsessionDetailState

enum class MirrorSource { LIVE, SCENARIO }
enum class MirrorSurface { LIBRARY, DISCOVER, MATRIX }

@Composable
fun ConsumerMirrorScreen(
    repository: MediaRepository,
    modifier: Modifier = Modifier
) {
    var source by remember { mutableStateOf(MirrorSource.LIVE) }
    var surface by remember { mutableStateOf(MirrorSurface.LIBRARY) }
    var selectedScenario by remember { mutableStateOf<MirrorScenario?>(null) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }
    var investigationEntry by remember { mutableStateOf<com.example.ui.models.RegressionMatrixEntry?>(null) }
    var selectedReportId by remember { mutableStateOf<String?>(null) }
    var gateResult by remember { mutableStateOf<com.example.ui.models.RegressionGateSummary?>(null) }
    var isGateRunning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Replay state tracking
    val replayResults = remember { mutableStateMapOf<String, com.example.ui.models.RegressionMatrixEntry>() }

    val reportHistory by RegressionArtifactManager.history.collectAsStateWithLifecycle()
    val latestSeal by RegressionArtifactManager.latestSeal.collectAsStateWithLifecycle()

    val displayedReport = remember(selectedReportId, gateResult, reportHistory) {
        if (selectedReportId != null) {
            reportHistory.find { it.id == selectedReportId }?.gateSummary
        } else {
            gateResult
        }
    }

    val liveLibraryState = repository.latestLibraryPresentation.collectAsStateWithLifecycle().value
    val liveDiscoverState = repository.latestDiscoverPresentation.collectAsStateWithLifecycle().value
    
    val latestPerformance by repository.latestPerformance.collectAsStateWithLifecycle()

    val currentLibraryState = if (source == MirrorSource.LIVE) {
        liveLibraryState
    } else {
        selectedScenario?.libraryState ?: MirrorFixtures.populatedLibrary
    }

    val currentDiscoverState = if (source == MirrorSource.LIVE) {
        liveDiscoverState
    } else {
        selectedScenario?.discoverState ?: MirrorFixtures.populatedDiscover
    }

    val currentProvenanceMap = if (surface == MirrorSurface.LIBRARY) currentLibraryState.provenanceMap else currentDiscoverState.provenanceMap
    val selectedProvenance = selectedItemId?.let { currentProvenanceMap[it] }

    // Phase 5: Assertion Validation
    val validationResults = remember(selectedScenario, currentLibraryState, currentDiscoverState) {
        selectedScenario?.let { scenario ->
            validateScenario(scenario, if (surface == MirrorSurface.LIBRARY) currentLibraryState else null, if (surface == MirrorSurface.DISCOVER) currentDiscoverState else null)
        } ?: emptyList()
    }

    // Phase 7: Golden Comparison
    val goldenResult = remember(selectedScenario, currentLibraryState, currentDiscoverState) {
        selectedScenario?.let { scenario ->
            compareGolden(scenario, if (surface == MirrorSurface.LIBRARY) currentLibraryState else null, if (surface == MirrorSurface.DISCOVER) currentDiscoverState else null)
        }
    }

    // Phase 8: Matrix Execution
    val baseline by BaselineManager.currentBaseline.collectAsStateWithLifecycle()
    val matrixSummary = remember(MirrorFixtures.scenarios, baseline) {
        RegressionMatrix.execute(MirrorFixtures.scenarios, baseline)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. PRODUCTION RENDERER (The Mirror)
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (investigationEntry != null) {
                    val entryToShow = replayResults[investigationEntry!!.scenarioId] ?: investigationEntry!!
                    RegressionInvestigationView(
                        entry = entryToShow,
                        onBack = { investigationEntry = null },
                        onReplay = {
                            scope.launch {
                                val result = RegressionMatrix.replay(investigationEntry!!, repository.intelligenceCore)
                                replayResults[investigationEntry!!.scenarioId] = result
                            }
                        }
                    )
                } else if (surface == MirrorSurface.MATRIX) {
                    val currentSummary = displayedReport?.let { matrixSummary.copy(gateSummary = it, entries = it.entries) } ?: matrixSummary
                    RegressionMatrixContent(
                        summary = currentSummary,
                        baseline = baseline,
                        onApprove = { BaselineManager.approve(currentSummary) },
                        onSelectEntry = { investigationEntry = it },
                        repository = repository,
                        isGateRunning = isGateRunning,
                        onRunGate = {
                            scope.launch {
                                isGateRunning = true
                                gateResult = RegressionMatrix.runGate(MirrorFixtures.scenarios, repository.intelligenceCore!!, baseline)
                                selectedReportId = null // Switch to current
                                isGateRunning = false
                            }
                        },
                        selectedReportId = selectedReportId,
                        onSelectReport = { selectedReportId = it }
                    )
                } else if (surface == MirrorSurface.LIBRARY) {
                    LibraryContent(
                        state = currentLibraryState,
                        latestSortedItems = currentLibraryState.mediaItems.map { it.toLibraryItemUi() },
                        mediaItemsMap = currentLibraryState.mediaItems.associateBy { it.id },
                        onFilterChange = {},
                        onCategoryChange = {},
                        onStandardSortChange = {},
                        onIntelligentSortChange = {},
                        onSearchQueryChange = {},
                        onSyncClick = {},
                        onAutoScrollSpeedChange = {},
                        onGridDensityChange = {},
                        onMediaSelect = { item, _ -> selectedItemId = item.id },
                        onCompareLaunch = {},
                        onFavoriteToggle = {},
                        onImportUris = {},
                        onTriggerScan = {},
                        onRefresh = {},
                        onSafeDeleteRequest = { _, _ -> },
                        onLike = {},
                        onAddVisualReference = {},
                        onRemoveVisualReference = {},
                        onClearSearch = {},
                        onSearchByImage = { _, _ -> },
                        onSearchByMultipleImages = {},
                        deletionStateFlow = repository.safeDeleteManager.deletionState,
                        libraryScrollIndex = 0,
                        libraryScrollOffset = 0,
                        onScrollPositionChange = { _, _ -> }
                    )
                } else {
                    DiscoverContent(
                        state = currentDiscoverState,
                        detailState = ObsessionDetailState.Idle,
                        onRefresh = {},
                        onObsessionSelect = { selectedItemId = it.id },
                        onMediaSelect = { item, _, _ -> selectedItemId = item.id },
                        onBackFromDetail = { selectedItemId = null },
                        onFavoriteToggle = {},
                        onExpandObsession = {},
                        onTrySomethingNew = {},
                        onMarkTasteRevealSeen = {},
                        onUpdateDiscoveryPolicy = {},
                        onUpdateTasteDNA = {},
                        onUpdatePreferenceProfile = {},
                        onRecordExposures = {},
                        onFlushExposures = {},
                        onScanAndImport = {},
                        discoverScrollIndex = 0,
                        discoverScrollOffset = 0,
                        onScrollPositionChange = { _, _ -> },
                        intelligenceRepository = repository.intelligenceRepository
                    )
                }
            }

            // 2. MIRROR CONTROLS
            Surface(
                color = AuraCrispWhite,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MirrorControlGroup("Source", listOf("LIVE", "SCENARIO"), source.name) {
                                source = MirrorSource.valueOf(it)
                                selectedItemId = null
                                if (source == MirrorSource.LIVE) selectedScenario = null
                            }
                            MirrorControlGroup("Surface", listOf("LIBRARY", "DISCOVER", "MATRIX"), surface.name) {
                                surface = MirrorSurface.valueOf(it)
                                selectedItemId = null
                            }
                            if (source == MirrorSource.SCENARIO) {
                                MirrorControlGroup("Scenario", MirrorFixtures.scenarios.map { it.name }, selectedScenario?.name ?: "None") { name ->
                                    val scenario = MirrorFixtures.scenarios.find { it.name == name }
                                    selectedScenario = scenario
                                    selectedItemId = null
                                    if (scenario?.libraryState != null) surface = MirrorSurface.LIBRARY
                                    if (scenario?.discoverState != null) surface = MirrorSurface.DISCOVER
                                }
                            }
                        }
                        
                        if (selectedItemId != null) {
                            TextButton(onClick = { selectedItemId = null }) {
                                Text("Clear Selection", color = DiscoveryViolet, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // 3. DEVELOPER DIAGNOSTIC OVERLAY
        DiagnosticOverlay(
            source = source,
            surface = surface,
            perf = latestPerformance,
            itemCount = if (surface == MirrorSurface.LIBRARY) currentLibraryState.mediaItems.size else currentDiscoverState.obsessions.size,
            selectedId = selectedItemId,
            provenance = selectedProvenance,
            validationResults = validationResults,
            scenarioDescription = selectedScenario?.description,
            goldenResult = goldenResult
        )
    }
}

private fun compareGolden(
    scenario: MirrorScenario,
    libState: LibraryPresentationState?,
    discoverState: DiscoverPresentationState?
): GoldenComparisonResult {
    val expectation = scenario.goldenExpectation ?: return GoldenComparisonResult(true)
    val mismatches = mutableListOf<String>()
    
    // 1. Item ID Check
    val actualIds = libState?.mediaItems?.map { it.id } ?: discoverState?.obsessions?.map { it.id } ?: emptyList()
    if (expectation.expectedItemIds.isNotEmpty() && actualIds != expectation.expectedItemIds) {
        mismatches.add("Items: Expected ${expectation.expectedItemIds.size}, Observed ${actualIds.size}")
    }

    // 2. Ranking Check
    val actualTop = actualIds.firstOrNull()
    if (expectation.expectedTopId != null && actualTop != expectation.expectedTopId) {
        mismatches.add("Top Rank: Expected ${expectation.expectedTopId}, Observed $actualTop")
    }

    // 3. Trace Sequence Check
    // We check the trace of the top item if available
    val topProv = if (libState != null) libState.provenanceMap[actualTop ?: ""] else discoverState?.provenanceMap?.get(actualTop ?: "")
    val actualTrace = topProv?.trace?.events?.map { it.type } ?: emptyList()
    
    if (expectation.expectedTraceSequence.isNotEmpty()) {
        if (actualTrace != expectation.expectedTraceSequence) {
            mismatches.add("Trace: Sequence divergence detected")
        }
    }

    // 4. Provenance Check
    val provSummary = topProv?.provenanceSummary ?: ""
    expectation.expectedProvenanceFragments.forEach { fragment ->
        if (!provSummary.contains(fragment, ignoreCase = true)) {
            mismatches.add("Provenance: Missing '$fragment'")
        }
    }

    return GoldenComparisonResult(mismatches.isEmpty(), mismatches, actualTrace)
}

private fun validateScenario(
    scenario: MirrorScenario,
    libState: LibraryPresentationState?,
    discoverState: DiscoverPresentationState?
): List<MirrorValidationResult> {
    return scenario.assertions.map { assertion ->
        val prov = if (libState != null) libState.provenanceMap[assertion.targetId] else discoverState?.provenanceMap?.get(assertion.targetId)
        val trace = prov?.trace

        val result = when (assertion.type) {
            MirrorAssertionType.ITEM_PRESENT -> {
                val present = libState?.mediaItems?.any { it.id == assertion.targetId } == true ||
                               discoverState?.obsessions?.any { it.id == assertion.targetId } == true
                val failCat = if (!present) {
                    if (trace == null) "INPUT MISMATCH" 
                    else if (trace.events.none { it.type == TraceEventType.CANDIDATES_RETRIEVED }) "CANDIDATE GENERATION MISMATCH"
                    else "PRESENTATION MAPPING MISMATCH"
                } else null
                MirrorValidationResult(assertion, present, present, failCat)
            }
            MirrorAssertionType.ITEM_ABSENT -> {
                val present = libState?.mediaItems?.any { it.id == assertion.targetId } == true ||
                               discoverState?.obsessions?.any { it.id == assertion.targetId } == true
                val failCat = if (present) {
                    if (trace?.events?.any { it.type == TraceEventType.POOL_FILTERED } == true) "ELIGIBILITY MISMATCH"
                    else "INTELLIGENCE OUTPUT MISMATCH"
                } else null
                MirrorValidationResult(assertion, !present, present, failCat)
            }
            MirrorAssertionType.TOP_RANKED -> {
                val firstId = if (libState != null) libState.mediaItems.firstOrNull()?.id else discoverState?.obsessions?.firstOrNull()?.id
                val passed = firstId == assertion.targetId
                val failCat = if (!passed) {
                    if (trace?.events?.any { it.type == TraceEventType.RERANKING_COMPLETED } == true) "RANKING OUTPUT MISMATCH"
                    else "PROVENANCE MISMATCH"
                } else null
                MirrorValidationResult(assertion, passed, firstId, failCat)
            }
            MirrorAssertionType.SCORE_ABOVE -> {
                val score = prov?.rankScore
                val threshold = assertion.expectedValue as? Double ?: 0.0
                val passed = (score ?: 0.0) >= threshold
                val failCat = if (!passed) {
                    if (trace?.events?.any { it.type == TraceEventType.SCORING_COMPLETED } == true) "SCORING OUTPUT MISMATCH"
                    else "INTELLIGENCE OUTPUT MISMATCH"
                } else null
                MirrorValidationResult(assertion, passed, score, failCat)
            }
            MirrorAssertionType.PROVENANCE_MATCH -> {
                val provSum = prov?.provenanceSummary ?: ""
                val target = assertion.expectedValue as? String ?: ""
                val passed = provSum.contains(target, ignoreCase = true)
                val failCat = if (!passed) "PROVENANCE MISMATCH" else null
                MirrorValidationResult(assertion, passed, provSum, failCat)
            }
            MirrorAssertionType.EMPTY_STATE_TRIGGERED -> {
                val isEmpty = if (libState != null) libState.mediaItems.isEmpty() else discoverState?.obsessions?.isEmpty() == true
                MirrorValidationResult(assertion, isEmpty, isEmpty, if (!isEmpty) "EMPTY/ELIGIBILITY MISMATCH" else null)
            }
        }
        result
    }
}

@Composable
private fun MirrorControlGroup(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = AuraMutedSlate)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    color = if (isSelected) DiscoveryViolet else AuraMutedSlate.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = option,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else AuraMidnight
                    )
                }
            }
        }
    }
}

@Composable
private fun RegressionMatrixContent(
    summary: RegressionMatrixSummary,
    baseline: RegressionBaseline?,
    onApprove: () -> Unit,
    onSelectEntry: (com.example.ui.models.RegressionMatrixEntry) -> Unit,
    repository: MediaRepository,
    isGateRunning: Boolean,
    onRunGate: () -> Unit,
    selectedReportId: String?,
    onSelectReport: (String?) -> Unit
) {
    val baselineHistory by BaselineManager.history.collectAsStateWithLifecycle()
    val reportHistory by RegressionArtifactManager.history.collectAsStateWithLifecycle()
    val latestSeal by RegressionArtifactManager.latestSeal.collectAsStateWithLifecycle()
    val gateResult = summary.gateSummary

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("REGRESSION MATRIX", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunGate,
                    enabled = !isGateRunning && repository.intelligenceCore != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AuraPurple),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(if (isGateRunning) "RUNNING..." else "RUN GATE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscoveryViolet),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("APPROVE BASELINE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        if (gateResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = when (gateResult.result) {
                    RegressionGateResult.PASS -> Color.Green.copy(alpha = 0.1f)
                    RegressionGateResult.FAIL -> Color.Red.copy(alpha = 0.1f)
                    RegressionGateResult.INSUFFICIENT_EVIDENCE -> Color.Yellow.copy(alpha = 0.1f)
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("GATE RESULT: ${gateResult.result.name}", fontWeight = FontWeight.Black, fontSize = 18.sp, 
                        color = when (gateResult.result) {
                            RegressionGateResult.PASS -> Color(0xFF006400)
                            RegressionGateResult.FAIL -> Color.Red
                            RegressionGateResult.INSUFFICIENT_EVIDENCE -> Color(0xFF8B8000)
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Classification: ${gateResult.unchangedCount} Unchanged, ${gateResult.notReproducedCount} Not Reproduced, ${gateResult.reproducedCount} Reproduced", fontSize = 10.sp)
                    
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        if (gateResult.reportId != null) {
                            TextButton(onClick = { 
                                Log.d("Export", "JSON: NOT_IMPLEMENTED_UI_BRIDGE")
                            }) {
                                Text("JSON", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { 
                                Log.d("Export", RegressionArtifactManager.generateMarkdown(gateResult.reportId!!))
                            }) {
                                Text("MD", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (gateResult.result == RegressionGateResult.PASS && selectedReportId == null) {
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { RegressionArtifactManager.sealHandoff(gateResult.reportId ?: "") },
                                enabled = latestSeal?.reportId != gateResult.reportId,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00008B)),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(if (latestSeal?.reportId == gateResult.reportId) "SEALED" else "SEAL HANDOFF", fontSize = 9.sp)
                            }
                        }
                    }

                    if (gateResult.reportId != null) {
                        Text("Report ID: ${gateResult.reportId}", fontSize = 8.sp, color = AuraMutedSlate, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (latestSeal != null) {
            Surface(
                color = Color.Blue.copy(alpha = 0.05f),
                border = BorderStroke(1.dp, Color.Blue.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null, tint = Color.Blue, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ACTIVE HANDOFF SEAL: ${latestSeal!!.id}", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.Blue)
                }
            }
        }

        if (reportHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("REPORT HISTORY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = AuraMutedSlate)
                if (selectedReportId != null) {
                    TextButton(onClick = { onSelectReport(null) }) {
                        Text("Switch to Current", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Surface(color = AuraMutedSlate.copy(alpha = 0.05f), shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    reportHistory.take(5).forEach { report ->
                        val isSelected = selectedReportId == report.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color.Blue.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable { onSelectReport(report.id) }
                                .padding(vertical = 4.dp, horizontal = 4.dp), 
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(report.id, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                Text("${report.gateSummary.result.name} • ${report.gateSummary.totalScenarios} scenarios", fontSize = 8.sp, color = Color.Gray)
                            }
                            if (latestSeal?.reportId == report.id) {
                                Text("SEALED", color = Color.Blue, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                            }
                        }
                    }
                }
            }
        }

        if (baseline != null) {
            Text(
                text = "CURRENT BASELINE: ${baseline.id}",
                fontSize = 8.sp,
                color = AuraMutedSlate,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (baselineHistory.size > 1) {
            Text(
                text = "HISTORY: ${baselineHistory.size} approved states",
                fontSize = 8.sp,
                color = DiscoveryViolet,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Surface(color = AuraMutedSlate.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryStat("Total", summary.totalCount.toString())
                SummaryStat("Match", summary.matchCount.toString(), Color.Green)
                SummaryStat("Mismatch", summary.mismatchCount.toString(), Color.Red)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        summary.entries.forEach { entry ->
            RegressionEntryRow(entry, onClick = { onSelectEntry(entry) })
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color = AuraMidnight) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AuraMutedSlate)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = color)
    }
}

@Composable
private fun RegressionEntryRow(
    entry: com.example.ui.models.RegressionMatrixEntry,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(entry.scenarioName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.surface, fontSize = 10.sp, color = AuraMutedSlate)
                    if (entry.baselineStatus != BaselineStatus.NOT_VERIFIED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• ${entry.baselineStatus.name}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (entry.baselineStatus == BaselineStatus.UNCHANGED) Color(0xFF006400) else Color.Red
                        )
                    }
                }
            }
            Surface(
                color = if (entry.status == RegressionStatus.MATCH) Color.Green.copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = entry.status.name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = if (entry.status == RegressionStatus.MATCH) Color(0xFF006400) else Color.Red
                )
            }
        }
        
        if (entry.status == RegressionStatus.MISMATCH) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(color = Color.Red.copy(alpha = 0.05f), shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("CATEGORY: ${entry.failureCategory}", fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color.Red)
                    
                    if (entry.mismatches.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        entry.mismatches.forEach { m ->
                            Text("• $m", fontSize = 9.sp, color = Color.DarkGray)
                        }
                    }
                    
                    if (entry.diff != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("BEHAVIORAL DIFF", fontWeight = FontWeight.Black, fontSize = 8.sp, color = AuraMidnight)
                        
                        if (entry.diff.additions.isNotEmpty()) {
                            Text("ADD: ${entry.diff.additions.joinToString()}", fontSize = 8.sp, color = Color(0xFF006400))
                        }
                        if (entry.diff.deletions.isNotEmpty()) {
                            Text("DEL: ${entry.diff.deletions.joinToString()}", fontSize = 8.sp, color = Color.Red)
                        }
                        if (entry.diff.moves.isNotEmpty()) {
                            Text("MOV: ${entry.diff.moves.joinToString()}", fontSize = 8.sp, color = Color.Blue)
                        }
                        if (entry.diff.otherChanges.isNotEmpty()) {
                            Text("OTH: ${entry.diff.otherChanges.joinToString()}", fontSize = 8.sp, color = Color.Magenta)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegressionInvestigationView(
    entry: com.example.ui.models.RegressionMatrixEntry,
    onBack: () -> Unit,
    onReplay: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← Back", color = DiscoveryViolet, fontWeight = FontWeight.Bold)
            }
            
            Button(
                onClick = onReplay,
                colors = ButtonDefaults.buttonColors(containerColor = AuraPurple),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("REPLAY SCENARIO", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Text("INVESTIGATION: ${entry.scenarioName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(16.dp))

        if (entry.replayStatus != ReproductionStatus.NOT_VERIFIED) {
            Surface(
                color = if (entry.replayStatus == ReproductionStatus.REPRODUCED) Color.Red.copy(alpha = 0.1f) else Color.Green.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("REPRODUCTION STATUS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (entry.replayStatus == ReproductionStatus.REPRODUCED) Color.Red else Color(0xFF006400))
                    Text(entry.replayStatus.name, fontSize = 16.sp, fontWeight = FontWeight.Black, color = if (entry.replayStatus == ReproductionStatus.REPRODUCED) Color.Red else Color(0xFF006400))
                }
            }
        }
        
        if (entry.status == RegressionStatus.MISMATCH) {
            Surface(color = Color.Red.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("FAILURE CATEGORY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                    Text(entry.failureCategory ?: "UNKNOWN", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.Red)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        SnapshotComparisonTriple(
            label = "BEHAVIORAL SNAPSHOTS",
            baseline = entry.baselineSnapshot,
            original = entry.currentSnapshot,
            replay = entry.replaySnapshot
        )

        if (entry.timeline != null) {
            Spacer(modifier = Modifier.height(24.dp))
            RegressionTimelineView(entry.timeline)
        }

        if (entry.diff != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("CONCRETE CHANGES (ORIGINAL)", fontSize = 12.sp, fontWeight = FontWeight.Black)
            BehavioralDiffContent(entry.diff)
        }
    }
}

@Composable
private fun RegressionTimelineView(timeline: com.example.ui.models.RegressionTimeline) {
    Column {
        Text("ROOT-CAUSE TIMELINE", fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(8.dp))
        
        timeline.events.forEachIndexed { index, event ->
            val isFirstDivergence = index == timeline.firstDivergenceIndex
            Surface(
                color = if (event.isDivergent) Color.Red.copy(alpha = 0.05f) else Color.Transparent,
                border = if (isFirstDivergence) BorderStroke(1.dp, Color.Red) else null,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}.", fontSize = 8.sp, color = AuraMutedSlate, modifier = Modifier.width(16.dp))
                        Text(event.type.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (event.isDivergent) Color.Red else AuraMidnight)
                        if (isFirstDivergence) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(color = Color.Red, shape = RoundedCornerShape(2.dp)) {
                                Text("FIRST DIVERGENCE", color = Color.White, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                    Text(event.detail, fontSize = 8.sp, color = Color.Gray, lineHeight = 10.sp)
                    
                    if (event.isDivergent) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TimelineEvidenceBox("BASE", event.baselineEvidence, Modifier.weight(1f))
                            TimelineEvidenceBox("EVAL", event.evalEvidence, Modifier.weight(1f))
                            TimelineEvidenceBox("REPLAY", event.replayEvidence, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEvidenceBox(label: String, evidence: com.example.ui.models.NormalizedTraceEvent?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = AuraMutedSlate)
        if (evidence != null) {
            val text = buildString {
                if (evidence.itemId != null) append("ID: ${evidence.itemId.take(8)}\n")
                if (evidence.count != null) append("Count: ${evidence.count}\n")
                if (evidence.score != null) append("Score: ${"%.3f".format(evidence.score)}\n")
                if (evidence.rank != null) append("Rank: ${evidence.rank}\n")
                if (evidence.channel != null) append("Chan: ${evidence.channel}\n")
            }
            if (text.isNotEmpty()) {
                Text(text.trim(), fontSize = 7.sp, color = AuraMidnight, fontFamily = FontFamily.Monospace, lineHeight = 9.sp)
            } else {
                Text("Type Match", fontSize = 7.sp, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        } else {
            Text("N/A", fontSize = 7.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun SnapshotComparisonTriple(
    label: String,
    baseline: com.example.ui.models.BehavioralSnapshot?,
    original: com.example.ui.models.BehavioralSnapshot?,
    replay: com.example.ui.models.BehavioralSnapshot?
) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("BASELINE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AuraMutedSlate)
                if (baseline != null) SnapshotSummary(baseline) else Text("N/A", fontSize = 8.sp, color = Color.Gray)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("ORIGINAL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AuraMutedSlate)
                if (original != null) SnapshotSummary(original) else Text("N/A", fontSize = 8.sp, color = Color.Gray)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("REPLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AuraMutedSlate)
                if (replay != null) SnapshotSummary(replay) else Text("N/A", fontSize = 8.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun SnapshotSummary(snapshot: com.example.ui.models.BehavioralSnapshot) {
    Column(modifier = Modifier.background(AuraMutedSlate.copy(alpha = 0.05f), RoundedCornerShape(4.dp)).padding(8.dp)) {
        Text("Items: ${snapshot.itemIds.size}", fontSize = 9.sp)
        Text("Top: ${snapshot.topRankedId ?: "None"}", fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("Empty: ${snapshot.isEmpty}", fontSize = 9.sp)
        Text("Trace Length: ${snapshot.traceSequence.size}", fontSize = 9.sp)
    }
}

@Composable
private fun BehavioralDiffContent(diff: com.example.ui.models.BehavioralDiff) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (diff.additions.isNotEmpty()) {
            Text("ADDITIONS (${diff.additions.size}): ${diff.additions.take(3).joinToString()}", color = Color(0xFF006400), fontSize = 10.sp)
        }
        if (diff.deletions.isNotEmpty()) {
            Text("DELETIONS (${diff.deletions.size}): ${diff.deletions.take(3).joinToString()}", color = Color.Red, fontSize = 10.sp)
        }
        if (diff.moves.isNotEmpty()) {
            Text("ORDERING: ${diff.moves.joinToString()}", color = Color.Blue, fontSize = 10.sp)
        }
        if (diff.otherChanges.isNotEmpty()) {
            Text("OTHER: ${diff.otherChanges.joinToString()}", color = Color.Magenta, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DiagnosticOverlay(
    source: MirrorSource,
    surface: MirrorSurface,
    perf: OperationPerformance?,
    itemCount: Int,
    selectedId: String?,
    provenance: com.example.ui.models.DecisionProvenance?,
    validationResults: List<MirrorValidationResult>,
    scenarioDescription: String?,
    goldenResult: GoldenComparisonResult?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp, end = 16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.width(220.dp).verticalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, tint = Color.Green, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("MIRROR CONTEXT", color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                
                DiagnosticRow("Source", source.name)
                DiagnosticRow("Surface", surface.name)
                DiagnosticRow("Items", itemCount.toString())
                
                if (scenarioDescription != null) {
                    Text(text = scenarioDescription, color = Color.Yellow, fontSize = 8.sp, lineHeight = 10.sp)
                }

                // Phase 7: Golden Comparison Section
                if (goldenResult != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (goldenResult.isMatch) Color.Green else Color.Red))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GOLDEN STATE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                    if (goldenResult.isMatch) {
                        Text("ALL BEHAVIORS MATCH", color = Color.Green, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        goldenResult.mismatches.forEach { mismatch ->
                            Text("• $mismatch", color = Color.Red, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                if (perf != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                    DiagnosticRow("Latency", "${perf.totalDurationMs}ms")
                    DiagnosticRow("Intel", "${perf.intelligenceDurationMs}ms")
                    DiagnosticRow("Ranking", "${perf.rankingDurationMs}ms")
                    DiagnosticRow("Cache", if (perf.cacheHit == true) "HIT" else "MISS")
                }

                if (validationResults.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.4f))
                    Text("VALIDATION", color = Color.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    validationResults.forEach { res ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(6.dp).clip(CircleShape).background(if (res.isPassed) Color.Green else Color.Red)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(res.assertion.description, color = Color.White, fontSize = 8.sp)
                            }
                            if (!res.isPassed) {
                                Text(
                                    text = "[FAIL] ${res.failureCategory}: Observed=${res.observedValue}",
                                    color = Color.Red,
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }

                if (selectedId != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.4f))
                    Text("SELECTED ITEM", color = DiscoveryViolet, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    DiagnosticRow("ID", selectedId.take(12))
                    
                    if (provenance != null) {
                        DiagnosticRow("Score", "%.3f".format(provenance.rankScore))
                        DiagnosticRow("Rel", "%.2f".format(provenance.primaryRelevance))
                        DiagnosticRow("Prov", provenance.source)
                        
                        Text(
                            text = provenance.provenanceSummary,
                            color = Color.LightGray,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 10.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        if (provenance.evidence.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            provenance.evidence.forEach { ev ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "• ${ev.type.name}: ${"%.2f".format(ev.score)}",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // Phase 6: Decision Trace
                        if (provenance.trace != null) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
                            Text("DECISION TRACE", color = Color.Yellow, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            TraceVisualization(provenance.trace)
                        } else {
                            Text("Trace: NOT AVAILABLE", color = Color.Gray, fontSize = 7.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    } else {
                        Text("No provenance available", color = Color.Gray, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceVisualization(trace: DecisionTrace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        trace.events.forEachIndexed { index, event ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}.",
                    color = Color.Gray,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(12.dp)
                )
                Column {
                    Text(
                        text = event.type.name,
                        color = Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (event.detail.isNotBlank()) {
                        Text(
                            text = event.detail,
                            color = Color.LightGray.copy(alpha = 0.8f),
                            fontSize = 6.sp,
                            lineHeight = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}
