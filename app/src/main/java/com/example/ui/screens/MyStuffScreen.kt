package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.ui.theme.*

enum class MyStuffTab(val title: String) {
    PROFILE("Profile"),
    COLLECTIONS("Collections")
}

@Composable
fun MyStuffScreen(
    repository: MediaRepository,
    discoverViewModel: DiscoverViewModel? = null,
    mediaItems: List<MediaItem>,
    onNavigateToFavorites: () -> Unit,
    onNavigateToCleanup: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onLaunchAuraMoments: () -> Unit,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit,
    onScanAndImport: () -> Unit,
    onCollectionSelect: (MediaItem, List<MediaItem>) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    initialTab: MyStuffTab = MyStuffTab.PROFILE,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuraBackground)
    ) {
        // My Stuff Top Bar Selector
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            color = AuraCrispWhite,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.S)
            ) {
                Text(
                    text = "My Stuff",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = AuraMidnight
                )

                Spacer(modifier = Modifier.height(AuraSpacing.S))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)
                ) {
                    MyStuffTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            onClick = { selectedTab = tab },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) DiscoveryViolet else Color.Transparent,
                            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, AuraSubtleBorder),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Text(
                                    text = tab.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else AuraMutedSlate
                                )
                            }
                        }
                    }
                }
            }
        }

        // Selected Tab Content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                MyStuffTab.PROFILE -> {
                    ProfileScreen(
                        repository = repository,
                        onNavigateToFavorites = onNavigateToFavorites,
                        onNavigateToCleanup = onNavigateToCleanup,
                        onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                        onNavigateToDiagnostics = onNavigateToDiagnostics,
                        onLaunchAuraMoments = onLaunchAuraMoments,
                        onMediaSelect = onMediaSelect
                    )
                }
                MyStuffTab.COLLECTIONS -> {
                    CollectionsScreen(
                        mediaItems = mediaItems,
                        repository = repository,
                        onCollectionSelect = onCollectionSelect,
                        onFavoriteToggle = onFavoriteToggle,
                        onLaunchAuraMoments = onLaunchAuraMoments,
                        onSearch = onSearch
                    )
                }
            }
        }
    }
}
