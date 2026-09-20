package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.data.MediaRepository
import com.example.data.intelligence.OperationPerformance

/**
 * Developer-only performance diagnostic overlay.
 * Displays the cost of the latest intelligence workflow.
 */
@Composable
fun PerformanceOverlay(repository: MediaRepository) {
    if (!BuildConfig.ENABLE_DEVELOPER_TOOLS) return

    val performance by repository.latestPerformance.collectAsStateWithLifecycle()
    val perf = performance ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.7f),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PERF: ${perf.operationName}",
                    color = Color.Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                MetricRow("Total", perf.totalDurationMs)
                MetricRow("Intel", perf.intelligenceDurationMs)
                MetricRow("Rank", perf.rankingDurationMs)
                MetricRow("DB", perf.databaseDurationMs)
                MetricRow("Inf", perf.inferenceDurationMs)
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Cands: ${if (perf.candidateCount >= 0) perf.candidateCount else "N/A"}",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Res: ${if (perf.resultCount >= 0) perf.resultCount else "N/A"}",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                if (perf.cacheHit != null) {
                    Text(
                        text = "Cache: ${if (perf.cacheHit) "HIT" else "MISS"}",
                        color = if (perf.cacheHit) Color.Cyan else Color.Yellow,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: Long) {
    if (value < 0) return
    Row(
        modifier = Modifier.fillMaxWidth(0.3f),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            color = Color.LightGray,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "${value}ms",
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
