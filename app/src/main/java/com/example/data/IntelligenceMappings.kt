package com.example.data

import com.example.data.db.*
import com.example.data.blueprint.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

private val strategyBlueprintAdapter = moshi.adapter(StrategyBlueprint::class.java)
private val proposedChangesAdapter = moshi.adapter(ProposedChanges::class.java)
private val implementationPlanAdapter = moshi.adapter(ImplementationPlan::class.java)
private val verificationPlanAdapter = moshi.adapter(VerificationPlan::class.java)
private val rollbackPlanAdapter = moshi.adapter(RollbackPlan::class.java)

private val stringListAdapter = moshi.adapter<List<String>>(
    Types.newParameterizedType(List::class.java, String::class.java)
)
private val stringMapAdapter = moshi.adapter<Map<String, String>>(
    Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
)
private val doubleMapAdapter = moshi.adapter<Map<String, Double>>(
    Types.newParameterizedType(Map::class.java, String::class.java, java.lang.Double::class.java)
)
private val integrityIssueAdapter = moshi.adapter<List<IntegrityIssue>>(
    Types.newParameterizedType(List::class.java, IntegrityIssue::class.java)
)

fun MediaEntity.toMediaItem(): MediaItem {
    val isVideo = mediaType.equals("VIDEO", ignoreCase = true) || mediaType.equals("Movie", ignoreCase = true)
    val normalizedType = if (isVideo) "VIDEO" else "PHOTO"
    val gradients = if (isVideo) {
        listOf(0xFF1E1B4BL, 0xFF4338CAL, 0xFF7C3AEDL)
    } else {
        listOf(0xFF311B92L, 0xFF6A1B9AL, 0xFFD946EFL)
    }
    val compStatus = try {
        CompatibilityStatus.valueOf(compatibilityStatus)
    } catch (e: Exception) {
        CompatibilityStatus.PLAYABLE
    }
    val convStatus = try {
        ConversionStatus.valueOf(conversionStatus)
    } catch (e: Exception) {
        ConversionStatus.NONE
    }
    return MediaItem(
        id = id,
        title = title,
        mediaType = normalizedType,
        year = year,
        duration = duration,
        genre = genre,
        imageUrl = imageUrl,
        gradientColors = if (gradientColorsJson.isBlank()) gradients else gradientColorsJson.split(",").mapNotNull { it.toLongOrNull() },
        rating = rating,
        isFavorite = isFavorite,
        progress = progress,
        progressText = progressText,
        category = category,
        aiSummary = aiSummary,
        moodTags = if (moodTagsJson.isEmpty()) emptyList() else moodTagsJson.split(","),
        uriPath = uriPath,
        itemCount = itemCount,
        sizeBytes = sizeBytes,
        dateAdded = dateAdded,
        dateModified = dateModified,
        durationMs = durationMs,
        width = width,
        height = height,
        lastViewedTimestamp = lastViewedTimestamp,
        viewCount = playCount,
        exposureCount = exposureCount,
        lastExposedTimestamp = lastExposedTimestamp,
        contentHash = contentHash,
        parentContentId = parentContentId,
        eloRating = eloRating,
        isDeleted = isDeleted,
        compatibilityStatus = compStatus,
        containerFormat = containerFormat,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        compatibilityReason = compatibilityReason,
        conversionStatus = convStatus,
        convertedUri = convertedUri.ifBlank { null },
        lastCompatibilityCheckTimestamp = lastCompatibilityCheckTimestamp,
        selectionReason = if (compStatus == CompatibilityStatus.ANALYSIS_FAILED) "Retry Analysis" else selectionReason,
        creatorId = creatorId,
        creatorName = creatorName,
        sourcePlatform = sourcePlatform,
        replacedByMediaId = replacedByMediaId
    )
}

fun FindingEntity.toDomainFinding() = Finding(
    id = id,
    title = title,
    summary = summary,
    classification = classification,
    confidence = confidence,
    dateDiscovered = dateDiscovered,
    technicalDetails = strategyBlueprintAdapter.fromJson(technicalDetailsJson)!!,
    lifecycleState = lifecycleState
)

fun SuggestedImprovementEntity.toDomainImprovement() = SuggestedImprovement(
    id = id,
    findingId = findingId,
    title = title,
    summary = summary,
    priority = priority,
    expectedImpact = expectedImpact,
    risk = risk,
    confidence = confidence,
    evidenceCount = evidenceCount,
    source = source,
    classification = classification,
    rationale = rationale,
    whatWillChange = whatWillChange,
    whatWillNotChange = whatWillNotChange,
    proposedChanges = proposedChangesAdapter.fromJson(proposedChangesJson)!!,
    implementationPlan = implementationPlanAdapter.fromJson(implementationPlanJson)!!,
    verificationPlan = verificationPlanAdapter.fromJson(verificationPlanJson)!!,
    rollbackPlan = rollbackPlanAdapter.fromJson(rollbackPlanJson)!!,
    technicalDetails = technicalDetailsJson?.let { strategyBlueprintAdapter.fromJson(it) },
    blueprintArtifactId = blueprintArtifactId,
    status = status,
    createdAt = createdAt
)

fun SuggestedImprovementEntity.toDomain() = toDomainImprovement()


fun Finding.toEntityFinding() = FindingEntity(
    id = id,
    title = title,
    summary = summary,
    classification = classification,
    confidence = confidence,
    dateDiscovered = dateDiscovered,
    technicalDetailsJson = strategyBlueprintAdapter.toJson(technicalDetails),
    lifecycleState = lifecycleState
)

fun SuggestedImprovement.toEntityImprovement() = SuggestedImprovementEntity(
    id = id,
    findingId = findingId,
    title = title,
    summary = summary,
    priority = priority,
    expectedImpact = expectedImpact,
    risk = risk,
    confidence = confidence,
    evidenceCount = evidenceCount,
    source = source,
    classification = classification,
    rationale = rationale,
    whatWillChange = whatWillChange,
    whatWillNotChange = whatWillNotChange,
    proposedChangesJson = proposedChangesAdapter.toJson(proposedChanges),
    implementationPlanJson = implementationPlanAdapter.toJson(implementationPlan),
    verificationPlanJson = verificationPlanAdapter.toJson(verificationPlan),
    rollbackPlanJson = rollbackPlanAdapter.toJson(rollbackPlan),
    technicalDetailsJson = technicalDetails?.let { strategyBlueprintAdapter.toJson(it) },
    blueprintArtifactId = blueprintArtifactId,
    status = status,
    createdAt = createdAt
)

fun ImplementationRunEntity.toDomainRun() = ImplementationRun(
    id = id,
    improvementId = improvementId,
    artifactId = artifactId,
    proposalVersion = proposalVersion,
    startTime = startTime,
    endTime = endTime,
    status = status,
    notes = notes,
    changedFiles = changedFilesJson?.let { stringListAdapter.fromJson(it) } ?: emptyList(),
    deviationDetected = deviationDetected,
    deviationDetails = deviationDetails,
    resultSummary = resultSummary
)

fun VerificationResultEntity.toDomainVerification() = VerificationResult(
    id = id,
    improvementId = improvementId,
    runId = runId,
    artifactId = artifactId,
    timestamp = timestamp,
    buildPassed = buildPassed,
    testsPassed = testsPassed,
    regressionPassed = regressionPassed,
    dbIntegrityPassed = dbIntegrityPassed,
    scopeCompliant = scopeCompliant,
    acceptanceCriteriaResults = stringMapAdapter.fromJson(acceptanceCriteriaResultsJson) ?: emptyMap(),
    technicalDetails = technicalDetailsJson,
    overallPassed = overallPassed
)

fun MonitoringSessionEntity.toDomainMonitoring() = MonitoringSession(
    id = id,
    improvementId = improvementId,
    runId = runId,
    artifactId = artifactId,
    startTime = startTime,
    status = status,
    baselineMetrics = doubleMapAdapter.fromJson(baselineMetricsJson) ?: emptyMap(),
    currentMetrics = doubleMapAdapter.fromJson(currentMetricsJson) ?: emptyMap(),
    requiredSampleCount = requiredSampleCount,
    currentSampleCount = currentSampleCount,
    durationDays = durationDays,
    regressionDetected = regressionDetected,
    confidence = confidence,
    validationOutcome = validationOutcome,
    evidenceIds = stringListAdapter.fromJson(evidenceIdsJson) ?: emptyList()
)

fun ValidationResultEntity.toDomainValidation() = ValidationResult(
    id = id,
    improvementId = improvementId,
    sessionId = sessionId,
    timestamp = timestamp,
    outcome = outcome,
    evidenceSummary = evidenceSummary,
    baselineValue = baselineValue,
    finalValue = finalValue,
    change = change,
    sampleCount = sampleCount,
    confidence = confidence,
    regressionSeverity = regressionSeverity,
    metadata = emptyMap() // Simplified for prototype
)

fun RegressionAlertEntity.toDomainRegression() = RegressionAlert(
    id = id,
    improvementId = improvementId,
    runId = runId,
    artifactId = artifactId,
    sessionId = sessionId,
    timestamp = timestamp,
    severity = severity,
    affectedMetric = affectedMetric,
    baselineValue = baselineValue,
    preRegressionValue = preRegressionValue,
    currentResult = currentResult,
    change = change,
    evidenceIds = stringListAdapter.fromJson(evidenceIdsJson) ?: emptyList(),
    confidence = confidence,
    status = status,
    recommendation = recommendation
)

fun AttentionItemEntity.toDomainAttention() = AttentionItem(
    id = id,
    sourceType = sourceType,
    sourceId = sourceId,
    attentionType = attentionType,
    priority = priority,
    title = title,
    summary = summary,
    whyItMatters = whyItMatters,
    status = status,
    createdAt = createdAt,
    resolvedAt = resolvedAt,
    requiresAction = requiresAction,
    deepLink = deepLink,
    deduplicationKey = deduplicationKey
)

fun EvidenceSnapshotEntity.toDomainSnapshot() = EvidenceSnapshot(
    improvementId = improvementId,
    blueprintId = blueprintId,
    timestamp = timestamp,
    report = moshi.adapter(ClosedLoopReport::class.java).fromJson(reportJson)!!
)

fun IntegrityAuditEntity.toDomainAudit() = IntegrityAuditResult(
    id = id,
    targetId = targetId,
    timestamp = timestamp,
    status = status,
    scope = scope,
    issues = integrityIssueAdapter.fromJson(issuesJson) ?: emptyList(),
    recommendedAction = recommendedAction
)

fun IntegrityAuditResult.toEntityAudit() = IntegrityAuditEntity(
    id = id,
    targetId = targetId,
    timestamp = timestamp,
    status = status,
    scope = scope,
    issuesJson = integrityIssueAdapter.toJson(issues),
    recommendedAction = recommendedAction
)

fun RollbackRunEntity.toDomainRollback() = RollbackRun(
    id = id,
    improvementId = improvementId,
    regressionId = regressionId,
    originalRunId = originalRunId,
    artifactId = artifactId,
    startTime = startTime,
    endTime = endTime,
    status = status,
    notes = notes,
    changedFiles = changedFilesJson?.let { stringListAdapter.fromJson(it) } ?: emptyList(),
    deviationDetected = false,
    resultSummary = resultSummary
)

fun LifecycleEventEntity.toDomainLifecycleEvent() = LifecycleEvent(
    id = id,
    targetId = targetId,
    fromState = fromState,
    toState = toState,
    timestamp = timestamp,
    actor = actor,
    reason = reason,
    metadata = metadataJson?.let { stringMapAdapter.fromJson(it) }
)
