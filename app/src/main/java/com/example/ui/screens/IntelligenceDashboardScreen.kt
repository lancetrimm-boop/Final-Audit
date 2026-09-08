package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.intelligence.CalibrationStatus
import com.example.ui.components.TasteRadarChart
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.DiscoveryViolet
import com.example.ui.theme.DiscoveryMagenta

/**
 * Main dashboard for user-facing local intelligence insights (Phase 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceDashboardScreen(
    viewModel: IntelligenceDashboardViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val report = state.report

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        "Intelligence Dashboard", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black 
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Info, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DiscoveryViolet)
            }
        } else if (report != null) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.S),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.M)
            ) {
                // 1. Maturity & Learning Stats
                MaturityCard(report.maturity)

                // 2. Taste DNA Radar
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(AuraSpacing.M),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "TASTE SPECTRUM",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = DiscoveryViolet,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.M))
                        TasteRadarChart(
                            dimensions = report.tasteProfile.dimensions,
                            modifier = Modifier.size(260.dp)
                        )
                        
                        if (report.maturity.calibrationStatus == CalibrationStatus.INITIALIZING) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(top = AuraSpacing.M)
                            ) {
                                Text(
                                    "Continue comparing items in 'Compare' to refine your Taste DNA profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(AuraSpacing.S),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // 3. Top Traits
                if (report.tasteProfile.topTraits.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.XS)) {
                        Text(
                            "CORE STYLE IDENTIFIERS", 
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = AuraMutedSlate,
                            letterSpacing = 1.sp
                        )
                        FlowRow(
                            mainAxisSpacing = AuraSpacing.XS,
                            crossAxisSpacing = AuraSpacing.XS
                        ) {
                            report.tasteProfile.topTraits.forEach { trait ->
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text(trait, style = MaterialTheme.typography.labelMedium) },
                                    shape = CircleShape,
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        labelColor = AuraMidnight
                                    )
                                )
                            }
                        }
                    }
                }

                // 4. Interaction Quality
                EngagementGrid(report.engagement)
                
                Spacer(modifier = Modifier.height(AuraSpacing.L))
            }
        }
    }
}

@Composable
private fun MaturityCard(maturity: com.example.data.intelligence.AuraMaturitySnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.M)) {
            // 1. Personalization Confidence (Signal Quality)
            Text(
                "Personalization Confidence",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AuraMidnight
            )
            Spacer(modifier = Modifier.height(AuraSpacing.XXS))
            LinearProgressIndicator(
                progress = { maturity.personalizationConfidence.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = DiscoveryViolet,
                trackColor = DiscoveryViolet.copy(alpha = 0.1f)
            )
            Text(
                "How well Aura understands your aesthetic preferences.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                color = AuraMutedSlate,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(AuraSpacing.M))

            // 2. Data Coverage (Signal Quantity)
            Text(
                "Library Learning Coverage",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AuraMidnight
            )
            Spacer(modifier = Modifier.height(AuraSpacing.XXS))
            LinearProgressIndicator(
                progress = { maturity.dataCoverage.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = DiscoveryMagenta,
                trackColor = DiscoveryMagenta.copy(alpha = 0.1f)
            )
            Text(
                "Proportion of your library that Aura has evaluated.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 10.sp,
                color = AuraMutedSlate,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(AuraSpacing.M))

            // 3. Metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text("STATUS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = AuraMutedSlate)
                    Text(maturity.calibrationStatus.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Black, color = DiscoveryViolet)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("LEARNING DATA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = AuraMutedSlate)
                    Text("${maturity.totalInteractionsAnalyzed} signals", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AuraMidnight)
                }
            }
        }
    }
}

@Composable
private fun EngagementGrid(engagement: com.example.data.intelligence.EngagementSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.XS)) {
        Text(
            "ENGAGEMENT INSIGHT", 
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = AuraMutedSlate,
            letterSpacing = 1.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)) {
            MetricBox("Comp. Rate", "${(engagement.completionRate * 100).toInt()}%", Modifier.weight(1f))
            MetricBox("Fav. Density", "${(engagement.favoriteDensity * 100).toInt()}%", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)) {
            MetricBox("Skip Velocity", "${engagement.averageSkipVelocity}/min", Modifier.weight(1f))
            MetricBox("Peak Hour", "${engagement.mostActiveHour}:00", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.M)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = AuraMutedSlate, fontWeight = FontWeight.Bold)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = AuraMidnight)
        }
    }
}

@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    // Basic implementation of a FlowRow as a simple placeholder for layout
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        var layoutHeight = 0
        var layoutWidth = 0
        
        // Simple layout logic for mock-up
        layoutWidth = constraints.maxWidth
        layoutHeight = (placeables.size / 2 + 1) * 48 // Simplified height
        
        layout(layoutWidth, layoutHeight) {
            var x = 0
            var y = 0
            placeables.forEach { placeable ->
                if (x + placeable.width > constraints.maxWidth) {
                    x = 0
                    y += placeable.height + crossAxisSpacing.roundToPx()
                }
                placeable.placeRelative(x, y)
                x += placeable.width + mainAxisSpacing.roundToPx()
            }
        }
    }
}
