package com.example.ui.models

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages durable regression reports and handoff seals.
 * Phase 15: Durable Regression Artifacts + Handoff Seal Automation.
 */
object RegressionArtifactManager {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val reportAdapter = moshi.adapter(RegressionReport::class.java)
    private val sealAdapter = moshi.adapter(HandoffSeal::class.java)

    private val _history = MutableStateFlow<List<RegressionReport>>(emptyList())
    val history: StateFlow<List<RegressionReport>> = _history.asStateFlow()

    private val _latestSeal = MutableStateFlow<HandoffSeal?>(null)
    val latestSeal: StateFlow<HandoffSeal?> = _latestSeal.asStateFlow()

    private val reportCounter = AtomicInteger(0)

    private var storageDir: File? = null
    private var sealsDir: File? = null

    fun initialize(context: Context) {
        val baseDir = File(context.filesDir, "developer_tooling")
        val reports = File(baseDir, "reports")
        val seals = File(baseDir, "seals")
        
        if (!reports.exists()) reports.mkdirs()
        if (!seals.exists()) seals.mkdirs()
        
        storageDir = reports
        sealsDir = seals
        
        loadArtifacts()
    }

    fun resetForTesting() {
        storageDir = null
        sealsDir = null
        _history.value = emptyList()
        _latestSeal.value = null
    }

    private fun loadArtifacts() {
        val rDir = storageDir ?: return
        val reportFiles = rDir.listFiles { f -> f.name.startsWith("report_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val loadedReports = reportFiles.mapNotNull { file ->
            try {
                reportAdapter.fromJson(file.readText())
            } catch (e: Exception) {
                Log.e("RegressionArtifactManager", "Failed to load report: ${file.name}", e)
                null
            }
        }
        _history.value = loadedReports

        val sDir = sealsDir ?: return
        val sealFiles = sDir.listFiles { f -> f.name.startsWith("seal_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val loadedSeal = sealFiles.firstOrNull()?.let { file ->
            try {
                sealAdapter.fromJson(file.readText())
            } catch (e: Exception) {
                Log.e("RegressionArtifactManager", "Failed to load seal: ${file.name}", e)
                null
            }
        }
        _latestSeal.value = loadedSeal
    }

    fun createReport(gateSummary: RegressionGateSummary, baselineId: String?): String? {
        val timestamp = System.currentTimeMillis()
        val reportId = "report_${timestamp}_${reportCounter.incrementAndGet()}"
        
        val buildId = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}"
        
        val report = RegressionReport(
            id = reportId,
            timestamp = timestamp,
            gateSummary = gateSummary.copy(reportId = reportId),
            buildIdentity = buildId,
            baselineId = baselineId
        )

        val success = saveReport(report)
        if (success) {
            _history.value = (listOf(report) + _history.value).take(10)
            enforceRetention()
        }
        return if (success) reportId else null
    }

    private fun saveReport(report: RegressionReport): Boolean {
        val dir = storageDir ?: return false
        val tempFile = File(dir, "${report.id}.tmp")
        val finalFile = File(dir, "${report.id}.json")
        
        return try {
            tempFile.writeText(reportAdapter.toJson(report))
            tempFile.renameTo(finalFile)
            Log.i("RegressionArtifactManager", "Report persisted: ${finalFile.name}")
            true
        } catch (e: Exception) {
            Log.e("RegressionArtifactManager", "Failed to persist report", e)
            false
        }
    }

    fun sealHandoff(reportId: String): Boolean {
        val report = _history.value.find { it.id == reportId } ?: return false
        if (report.gateSummary.result != RegressionGateResult.PASS) {
            Log.e("RegressionArtifactManager", "Cannot seal a non-PASS report: $reportId")
            return false
        }

        val timestamp = System.currentTimeMillis()
        val seal = HandoffSeal(
            id = "seal_$timestamp",
            reportId = reportId,
            timestamp = timestamp,
            baselineId = report.baselineId,
            buildIdentity = report.buildIdentity,
            scenarioIds = report.gateSummary.entries.map { it.scenarioId }
        )

        val dir = sealsDir ?: return false
        val tempFile = File(dir, "${seal.id}.tmp")
        val finalFile = File(dir, "${seal.id}.json")
        
        return try {
            tempFile.writeText(sealAdapter.toJson(seal))
            tempFile.renameTo(finalFile)
            _latestSeal.value = seal
            Log.i("RegressionArtifactManager", "Handoff Seal created: ${seal.id}")
            true
        } catch (e: Exception) {
            Log.e("RegressionArtifactManager", "Failed to persist seal", e)
            false
        }
    }

    private fun enforceRetention() {
        val dir = storageDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("report_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        val sealedReportIds = getAllSealedReportIds()
        val eligibleForDeletion = files.filter { it.nameWithoutExtension !in sealedReportIds }
        
        if (eligibleForDeletion.size > 5) {
            val totalToDelete = eligibleForDeletion.size - 5
            eligibleForDeletion.takeLast(totalToDelete).forEach { file ->
                Log.i("RegressionArtifactManager", "Retention: Deleting report ${file.name}")
                file.delete()
            }
        }
    }

    private fun getAllSealedReportIds(): Set<String> {
        val sDir = sealsDir ?: return emptySet()
        return sDir.listFiles { f -> f.name.startsWith("seal_") && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    sealAdapter.fromJson(file.readText())?.reportId
                } catch (e: Exception) { null }
            }?.toSet() ?: emptySet()
    }

    fun generateMarkdown(reportId: String): String {
        val report = _history.value.find { it.id == reportId } ?: return "Report not found: $reportId"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val date = df.format(Date(report.timestamp))
        val summary = report.gateSummary
        val seal = if (_latestSeal.value?.reportId == reportId) _latestSeal.value else null

        return buildString {
            append("# AURA REGRESSION HANDOFF REPORT\n\n")
            append("## GATE STATUS: **${summary.result.name}**\n\n")
            append("| Property | Value |\n")
            append("| :--- | :--- |\n")
            append("| Report ID | `${report.id}` |\n")
            append("| Timestamp | $date |\n")
            append("| Build | `${report.buildIdentity}` |\n")
            append("| Baseline | `${report.baselineId ?: "None"}` |\n")
            if (seal != null) {
                append("| **HANDOFF SEAL** | **VALIDATED: `${seal.id}`** |\n")
            }
            append("\n")

            append("## EXECUTION SUMMARY\n\n")
            append("- Scenarios: ${summary.totalScenarios}\n")
            append("- Unchanged: ${summary.unchangedCount}\n")
            append("- Not Reproduced (Volatile): ${summary.notReproducedCount}\n")
            append("- Reproduced (Regressions): ${summary.reproducedCount}\n")
            append("- Insufficient Evidence: ${summary.insufficientEvidenceCount}\n\n")

            append("## BEHAVIORAL EVIDENCE\n\n")
            summary.entries.forEach { entry ->
                append("### ${entry.scenarioName} (${entry.status.name})\n")
                append("- Surface: ${entry.surface}\n")
                append("- Baseline Status: ${entry.baselineStatus.name}\n")
                append("- Replay Status: ${entry.replayStatus.name}\n")
                if (entry.failureCategory != null) {
                    append("- Failure Category: **${entry.failureCategory}**\n")
                }
                if (entry.mismatches.isNotEmpty()) {
                    append("- Mismatches:\n")
                    entry.mismatches.forEach { append("  - $it\n") }
                }
                append("\n")
            }

            append("## PROOF OF SUCCESS\n\n")
            if (summary.result == RegressionGateResult.PASS) {
                append("Gate passed. All behaviors either matched the approved baseline or were confirmed as non-reproducible data shifts.\n")
            } else if (summary.result == RegressionGateResult.FAIL) {
                append("Gate failed. Concrete behavioral regressions were reproduced in a deterministic environment.\n")
            } else {
                append("Gate inconclusive. Technical failures prevented a safe behavioral classification.\n")
            }
        }
    }
}
