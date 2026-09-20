package com.example.data.intelligence

import com.example.ui.models.DecisionTrace
import com.example.ui.models.TraceEvent
import com.example.ui.models.TraceEventType
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory collector for production decision traces.
 * Developer-only observational tool.
 */
object DecisionTraceCollector {

    private val traces = ConcurrentHashMap<String, MutableList<TraceEvent>>()
    private val traceMetadata = ConcurrentHashMap<String, String>() // requestId -> surface

    fun startTrace(requestId: String, surface: String) {
        if (!com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) return
        traces[requestId] = mutableListOf(TraceEvent(type = TraceEventType.REQUEST_RECEIVED, detail = "Surface: $surface"))
        traceMetadata[requestId] = surface
    }

    fun logEvent(requestId: String, type: TraceEventType, detail: String = "", metadata: Map<String, String> = emptyMap()) {
        if (!com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) return
        traces[requestId]?.add(TraceEvent(type = type, detail = detail, metadata = metadata))
    }

    fun getTrace(requestId: String): DecisionTrace? {
        val events = traces[requestId] ?: return null
        val surface = traceMetadata[requestId] ?: "UNKNOWN"
        return DecisionTrace(requestId, surface, events.toList())
    }

    fun clearTrace(requestId: String) {
        traces.remove(requestId)
        traceMetadata.remove(requestId)
    }
}
