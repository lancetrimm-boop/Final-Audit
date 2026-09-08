package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.example.data.MediaItem
import com.example.data.cleanup.CleanupCategory
import com.example.ui.components.AuraButton
import com.example.ui.components.AuraTopBar
import com.example.ui.components.CleanupMediaCard
import com.example.ui.theme.AuraBackground
import com.example.ui.theme.AuraBorder
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraOnSurface
import com.example.ui.theme.AuraOnSurfaceVariant
import com.example.ui.theme.AuraPurple
import com.example.ui.theme.AuraSlate
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.AuraSubtleSurface
import com.example.ui.theme.AuraSurface
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupReviewScreen(
    viewModel: CleanupReviewViewModel,
    onBack: () -> Unit,
    onMediaDetail: (MediaItem) -> Unit,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            AuraTopBar(
                title = "Smart Cleanup",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AuraMidnight)
                    }
                }
            )
        },
        containerColor = AuraCrispWhite
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DiscoveryViolet)
            }
        } else if (uiState.recommendations.isEmpty()) {
            EmptyCleanupView(padding)
        } else {
            Column(modifier = Modifier.padding(padding)) {
                HeaderSection(uiState)
                
                CategoryTabs(
                    selectedCategory = uiState.selectedCategory,
                    stats = uiState.categoryStats,
                    onCategorySelect = { viewModel.selectCategory(it) }
                )

                Box(modifier = Modifier.weight(1f)) {
                    RecommendationsList(
                        uiState = uiState,
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        onSelectAll = { viewModel.selectAllInCategory() },
                        onClearSelection = { viewModel.clearSelection() },
                        onUpdateSort = { viewModel.updateSort(it) },
                        onKeepItem = { viewModel.keepItem(it) }
                    )
                    
                    // Bulk Action Overlay
                    if (uiState.selectedIds.isNotEmpty()) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        BulkActionSurface(
                            selectedCount = uiState.selectedIds.size,
                            recoveryBytes = uiState.recommendations
                                .filter { uiState.selectedIds.contains(it.mediaId) }
                                .sumOf { it.storageSize },
                            onDelete = { viewModel.requestDeleteSelected(context, deleteLauncher) },
                            onKeepAll = { viewModel.keepSelected() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }

        if (uiState.isDeleting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = AuraCrispWhite)) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = DiscoveryViolet)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Reconciling Library...", fontWeight = FontWeight.Bold, color = AuraMidnight)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(uiState: CleanupReviewUiState) {
    Column(modifier = Modifier.padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.S)) {
        Text(
            "Aura found media that may no longer add value to your library.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraMutedSlate
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XXS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatSize(uiState.storageRecoveryEstimate),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = DiscoveryViolet
            )
            Text(
                " CAN BE REVIEWED",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Black,
                color = AuraMidnight,
                modifier = Modifier.padding(start = AuraSpacing.XXS)
            )
        }
    }
}

@Composable
private fun CategoryTabs(
    selectedCategory: CleanupCategory,
    stats: Map<CleanupCategory, CategoryStat>,
    onCategorySelect: (CleanupCategory) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val categories = CleanupCategory.entries.filter { it != CleanupCategory.NONE }
        items(categories) { cat ->
            val stat = stats[cat] ?: CategoryStat(0, 0L, 0f)
            CategoryChip(
                label = cat.name.replace("_", " "),
                count = stat.count,
                isSelected = selectedCategory == cat,
                onClick = { onCategorySelect(cat) }
            )
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (isSelected) DiscoveryViolet.copy(alpha = 0.12f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            brush = if (isSelected) DiscoveryGradient else androidx.compose.ui.graphics.SolidColor(AuraSubtleBorder)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XS),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label.uppercase(),
                fontSize = 9.sp,
                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                color = if (isSelected) DiscoveryViolet else AuraMutedSlate,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "$count items",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (isSelected) DiscoveryViolet else AuraMidnight
            )
        }
    }
}

@Composable
private fun RecommendationsList(
    uiState: CleanupReviewUiState,
    onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onUpdateSort: (ReviewSort) -> Unit,
    onKeepItem: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = if (uiState.selectedIds.isEmpty()) onSelectAll else onClearSelection
            ) {
                Text(
                    if (uiState.selectedIds.isEmpty()) "SELECT ALL" else "CLEAR SELECTION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = DiscoveryViolet,
                    letterSpacing = 1.sp
                )
            }
            
            var showSortMenu by remember { mutableStateOf(false) }
            
            Box {
                IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = AuraMutedSlate, modifier = Modifier.size(20.dp))
                }
                
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    ReviewSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { 
                                Text(sort.name.replace("_", " ").lowercase(Locale.US).replaceFirstChar { 
                                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() 
                                }) 
                            },
                            onClick = {
                                onUpdateSort(sort)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = AuraSpacing.M, end = AuraSpacing.M, bottom = AuraSpacing.XXXL),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.GridGap)
        ) {
            items(uiState.filteredRecommendations, key = { it.mediaId }) { rec ->
                val item = uiState.mediaItems[rec.mediaId] ?: MediaItem(rec.mediaId, "Unknown", "PHOTO")
                CleanupMediaCard(
                    item = item,
                    recommendation = rec,
                    isSelected = uiState.selectedIds.contains(rec.mediaId),
                    onToggleSelection = { onToggleSelection(rec.mediaId) },
                    onKeep = { onKeepItem(rec.mediaId) }
                )
            }
        }
    }
}

@Composable
private fun BulkActionSurface(
    selectedCount: Int,
    recoveryBytes: Long,
    onDelete: () -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(AuraSpacing.M),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(AuraSpacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Selected: $selectedCount items", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Recover: ${formatSize(recoveryBytes)}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.XS)) {
                TextButton(onClick = onKeepAll, contentPadding = PaddingValues(horizontal = AuraSpacing.XS)) {
                    Text("KEEP ALL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = DiscoveryViolet),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = AuraSpacing.M)
                ) {
                    Text("DELETE ($selectedCount)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun EmptyCleanupView(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your library looks healthy", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuraMidnight)
            Text("Aura hasn't found any cleanup recommendations yet.", color = AuraMutedSlate)
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
