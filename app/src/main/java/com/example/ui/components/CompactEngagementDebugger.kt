package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.MediaRepository
import com.example.data.TasteDNA
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraOnSurface
import com.example.ui.theme.AuraOnSurfaceVariant
import com.example.ui.theme.AuraPurple
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraSubtleSurface
import com.example.ui.theme.AuraSurface
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet

@Composable
fun CompactEngagementDebugger(
    repository: MediaRepository,
    modifier: Modifier = Modifier
) {
    val tasteDNA by repository.tasteDNA.collectAsStateWithLifecycle()
    val profile by repository.preferenceProfile.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = AuraSpacing.CornerRadiusMedium, topEnd = AuraSpacing.CornerRadiusMedium))
            .background(AuraSubtleSurface)
            .padding(AuraSpacing.M)
    ) {
        // Compact Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = DiscoveryViolet,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AuraSpacing.XS))
                Text(
                    text = "AURA ENGAGEMENT TUNER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = AuraMidnight,
                        letterSpacing = 0.5.sp
                    )
                )
            }
            
            Surface(
                color = DiscoveryViolet.copy(alpha = 0.1f),
                shape = RoundedCornerShape(AuraSpacing.XXS)
            ) {
                Text(
                    text = "LIVE STATE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = DiscoveryViolet,
                        fontSize = 8.sp
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.M))

        // Tabs for different control groups
        var selectedTab by remember { mutableIntStateOf(0) }
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = DiscoveryViolet,
            divider = {},
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = DiscoveryViolet
                )
            }
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text(
                    "Engine Signals", 
                    modifier = Modifier.padding(vertical = AuraSpacing.S), 
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.M))

        Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            when (selectedTab) {
                0 -> EngineSignalInfo(repository)
            }
        }
    }
}

// Removed duplicate TasteDnaControls and PreferenceProfileControls - migrated to TasteDnaControlPanel.kt

@Composable
private fun EngineSignalInfo(repository: MediaRepository) {
    val stats by repository.intelligenceStats.collectAsStateWithLifecycle()
    val diag by repository.pairwiseDiagnostics.collectAsStateWithLifecycle()
    val dna by repository.tasteDNA.collectAsStateWithLifecycle()
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalRow("Fine-Tuning Active", if (dna.isFineTuningEnabled) "YES" else "NO (Locked to Baseline)")
        SignalRow("Personalization Accuracy", "${stats.personalizationScore}%")
        SignalRow("Pairwise Confidence", "${(diag.comparedCandidateCount.toFloat() / diag.top100CandidatePoolSize.coerceAtLeast(1) * 100).toInt()}%")
        SignalRow("Pool Freshness", "${(System.currentTimeMillis() - diag.poolRefreshTimestamp) / 1000}s ago")
        SignalRow("Last Pair Selection", diag.lastSelectionReason)
    }
}

@Composable
private fun SignalRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AuraSpacing.XXXS),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = AuraOnSurfaceVariant)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AuraOnSurface)
    }
}
