package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.AuraBorder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.activity.compose.BackHandler
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MediaItem
import com.example.ui.components.AuraMediaTile
import com.example.ui.components.AuraTopBar
import com.example.ui.components.AuraLogoIcon
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet

import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.example.data.intelligence.IntelligentSection
import com.example.ui.components.AuraSectionHeader

@Composable
fun FavoritesScreen(
    sections: List<IntelligentSection>,
    isLoading: Boolean,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLike: ((String) -> Unit)? = null,
    onRefresh: () -> Unit = {}
) {
    BackHandler(onBack = onBack)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuraCrispWhite)
    ) {
        AuraTopBar(
            title = "Personal Favorites",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AuraMidnight
                    )
                }
            },
            actions = {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Default.Sync, contentDescription = "Refresh", tint = DiscoveryViolet)
                }
            }
        )

        if (isLoading && sections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = DiscoveryViolet)
            }
        } else if (sections.isEmpty()) {
            EmptyFavoritesView()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(sections) { section ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        AuraSectionHeader(
                            title = section.title,
                            subtitle = section.subtitle
                        )
                        
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.height(180.dp)
                        ) {
                            items(section.items, key = { it.id }) { item ->
                                Box(modifier = Modifier.width(140.dp)) {
                                    AuraMediaTile(
                                        item = item,
                                        onClick = { onMediaSelect(item, section.items) },
                                        onLike = { onLike?.invoke(item.id) },
                                        onLongClick = { onFavoriteToggle(item.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFavoritesView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuraLogoIcon(size = 64.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Favorites Yet",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AuraMidnight
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tap the heart icon on any media item to add it to your personal favorites collection.",
                fontSize = 13.sp,
                color = AuraMutedSlate,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
