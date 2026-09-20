package com.example.ui.models

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages approved behavioral baselines for the developer build.
 * Phase 10: Durable Baselines + Regression Investigation.
 */
object BaselineManager {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val baselineAdapter = moshi.adapter(RegressionBaseline::class.java)
    private val historyAdapter = moshi.adapter<List<RegressionBaseline>>(
        Types.newParameterizedType(List::class.java, RegressionBaseline::class.java)
    )

    private val _currentBaseline = MutableStateFlow<RegressionBaseline?>(null)
    val currentBaseline: StateFlow<RegressionBaseline?> = _currentBaseline.asStateFlow()

    private val _history = MutableStateFlow<List<RegressionBaseline>>(emptyList())
    val history: StateFlow<List<RegressionBaseline>> = _history.asStateFlow()

    private var storageDir: File? = null

    fun initialize(context: Context) {
        if (storageDir != null) return
        
        val dir = File(context.filesDir, "developer_tooling/baselines")
        if (!dir.exists()) dir.mkdirs()
        storageDir = dir
        
        loadHistory()
    }

    private fun loadHistory() {
        val dir = storageDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith("baseline_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
            
        val loaded = files.take(10).mapNotNull { file ->
            try {
                baselineAdapter.fromJson(file.readText())
            } catch (e: Exception) {
                Log.e("BaselineManager", "Failed to load baseline: ${file.name}", e)
                null
            }
        }
        
        _history.value = loaded
        _currentBaseline.value = loaded.firstOrNull()
    }

    fun approve(matrix: RegressionMatrixSummary) {
        val snapshots = matrix.entries.associate { entry ->
            val scenario = MirrorFixtures.scenarios.find { it.id == entry.scenarioId }
            entry.scenarioId to RegressionMatrix.takeSnapshot(
                entry.scenarioId, 
                scenario?.libraryState, 
                scenario?.discoverState
            )
        }

        val timestamp = System.currentTimeMillis()
        val newBaseline = RegressionBaseline(
            id = "baseline_$timestamp",
            createdAt = timestamp,
            snapshots = snapshots
        )

        saveBaseline(newBaseline)
        
        _currentBaseline.value = newBaseline
        _history.value = (listOf(newBaseline) + _history.value).take(10)
    }

    private fun saveBaseline(baseline: RegressionBaseline) {
        val dir = storageDir ?: return
        val file = File(dir, "${baseline.id}.json")
        try {
            file.writeText(baselineAdapter.toJson(baseline))
            Log.i("BaselineManager", "Baseline persisted: ${file.name}")
        } catch (e: Exception) {
            Log.e("BaselineManager", "Failed to persist baseline", e)
        }
    }

    fun selectBaseline(baseline: RegressionBaseline) {
        _currentBaseline.value = baseline
    }

    fun compare(currentSnapshot: BehavioralSnapshot, baseline: RegressionBaseline?): BaselineStatus {
        val baselineSnapshot = baseline?.snapshots?.get(currentSnapshot.scenarioId) ?: return BaselineStatus.NOT_VERIFIED
        
        return if (currentSnapshot == baselineSnapshot) {
            BaselineStatus.UNCHANGED
        } else {
            BaselineStatus.CHANGED
        }
    }
}
