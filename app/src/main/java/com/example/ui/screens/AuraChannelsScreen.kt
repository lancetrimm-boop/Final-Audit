@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Channel
import com.example.data.ChannelKind
import com.example.data.MediaItem
import com.example.ui.components.AuraMediaThumbnail
import com.example.ui.components.AuraSectionHeader
import com.example.ui.theme.*
import com.example.util.MediaThumbnailFetcher
import kotlinx.coroutines.delay

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraChannelsScreen(
    viewModel: ChannelViewModel,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val selectedChannel by viewModel.selectedChannel.collectAsStateWithLifecycle()
    val selectedFilterType by viewModel.selectedFilterType.collectAsStateWithLifecycle()
    val previews by viewModel.channelPreviews.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val slideshowDelaySec by viewModel.slideshowDelaySeconds.collectAsStateWithLifecycle()
    var channelToDelete by remember { mutableStateOf<Channel?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AuraBackground)
            .statusBarsPadding()
            .padding(AuraSpacing.M)
    ) {
        AuraSectionHeader(
            title = "Aura Channels",
            subtitle = "Continuous personalized station stream"
        )

        Spacer(modifier = Modifier.height(AuraSpacing.S))

        // Media Type Selector Row (Videos vs Photos)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)
        ) {
            listOf("VIDEOS" to "Videos", "PHOTOS" to "Photos").forEach { (typeKey, label) ->
                val isTypeSelected = selectedFilterType == typeKey
                Surface(
                    onClick = { viewModel.setFilterType(typeKey) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isTypeSelected) AuraPurple else Color.White,
                    border = if (isTypeSelected) null else androidx.compose.foundation.BorderStroke(1.dp, AuraSubtleBorder),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isTypeSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isTypeSelected) Color.White else AuraMutedSlate
                        )
                    }
                }
            }
        }

        // Photo Slideshow Duration Slider Control (Shown when Photos mode is active)
        if (selectedFilterType == "PHOTOS") {
            Spacer(modifier = Modifier.height(AuraSpacing.S))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, AuraSubtleBorder)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Photo Duration / Slideshow Delay",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuraMidnight
                        )
                        Text(
                            text = "$slideshowDelaySec ${if (slideshowDelaySec == 1) "second" else "seconds"}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DiscoveryViolet
                        )
                    }

                    Slider(
                        value = slideshowDelaySec.toFloat(),
                        onValueChange = { viewModel.setSlideshowDelaySec(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = DiscoveryViolet,
                            activeTrackColor = DiscoveryViolet,
                            inactiveTrackColor = AuraSubtleBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("slideshow_delay_slider")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.M))

        // Vertical Lean-Back Live Channel Browser
        val listState = rememberLazyListState()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                viewModel.refreshChannels()
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (previews.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AuraCrispWhite, shape = RoundedCornerShape(AuraSpacing.CornerRadiusMedium))
                        .padding(AuraSpacing.L),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Loading Channels",
                            tint = DiscoveryViolet,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.M))
                        Text(
                            text = "Loading Channel Stream...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AuraMidnight
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.M),
                    contentPadding = PaddingValues(bottom = AuraSpacing.XL)
                ) {
                    itemsIndexed(previews, key = { _, preview -> preview.channel.id }) { index, preview ->
                        val isVisibleInViewport = remember(index) {
                            derivedStateOf {
                                val layoutInfo = listState.layoutInfo
                                val visibleItems = layoutInfo.visibleItemsInfo
                                visibleItems.any { it.index == index }
                            }
                        }

                        ChannelPreviewCard(
                            channelPreview = preview,
                            isSelected = selectedChannel.id == preview.channel.id,
                            isVisibleInViewport = isVisibleInViewport.value,
                            onClick = { channel, previewItem, fullList ->
                                viewModel.selectChannel(channel)
                                if (previewItem != null && fullList.isNotEmpty()) {
                                    onMediaSelect(previewItem, fullList)
                                }
                            },
                            onDelete = { channelToDelete = it }
                        )
                    }
                }
            }
        }

        if (channelToDelete != null) {
            AlertDialog(
                onDismissRequest = { channelToDelete = null },
                title = { Text("Delete Channel") },
                text = { Text("Delete this channel?\n\nThis will remove the channel from your channels.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            channelToDelete?.let { viewModel.deleteChannel(it) }
                            channelToDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { channelToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

private fun getEmptyStateMessage(channel: Channel): String {
    return when (channel.id) {
        "FAVORITES" -> "No Favorites Marked"
        "CONTINUE" -> "No Continuing Series"
        "REDISCOVER" -> "Nothing to Rediscover"
        "MOOD" -> "No Matching Mood Items"
        "ME_TV" -> "No Media in Channel"
        else -> "No Items for '${channel.title}'"
    }
}

@Composable
fun ChannelMotionThumbnail(
    channelId: String,
    item: MediaItem,
    isVisibleInViewport: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uri = item.uriPath.ifEmpty { item.imageUrl }
    val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true) ||
            item.mediaType.startsWith("VIDEO", ignoreCase = true)

    // Key must be stable and unique per card
    val rememberKey = "${channelId}_${item.id}_$uri"

    var keyframes by remember(rememberKey) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var currentFrameIndex by remember(rememberKey) { mutableIntStateOf(0) }

    // Extract frames ONLY when visible. Convert to ImageBitmap immediately
    // so we never hold a recyclable Android Bitmap.
    LaunchedEffect(rememberKey, isVideo, isVisibleInViewport) {
        if (!isVisibleInViewport || !isVideo || uri.isNullOrEmpty()) {
            keyframes = emptyList()
            return@LaunchedEffect
        }
        val frameTimes = listOf(1_000_000L, 4_000_000L, 7_000_000L)
        val extracted = frameTimes.mapNotNull { timeUs ->
            MediaThumbnailFetcher.getFrameAtTime(context, uri, timeUs)
                ?.takeIf { !it.isRecycled }
                ?.asImageBitmap()
        }
        keyframes = extracted
    }

    // Simple cycle – cancel automatically when leaving composition / viewport
    LaunchedEffect(isVisibleInViewport, keyframes) {
        if (!isVisibleInViewport || keyframes.size <= 1) return@LaunchedEffect
        while (true) {
            delay(900L)
            currentFrameIndex = (currentFrameIndex + 1) % keyframes.size
        }
    }

    val activeFrame = if (isVisibleInViewport && keyframes.isNotEmpty()) {
        keyframes.getOrNull(currentFrameIndex % keyframes.size)
    } else null

    // Always clip the drawing surface itself
    val clippedModifier = modifier.clip(RoundedCornerShape(16.dp))

    if (activeFrame != null) {
        Image(
            bitmap = activeFrame,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = clippedModifier
        )
    } else {
        // Static fallback – must be pure image, never a player
        AuraMediaThumbnail(
            itemId = item.id,
            mediaType = item.mediaType,
            imageUrl = item.imageUrl,
            uriPath = item.uriPath,
            title = item.title,
            modifier = clippedModifier,
            locationTag = "channel_motion_preview_${channelId}_${item.id}"
        )
    }
}

@Composable
fun ChannelPreviewCard(
    channelPreview: ChannelPreviewState,
    isSelected: Boolean,
    isVisibleInViewport: Boolean,
    onClick: (Channel, MediaItem?, List<MediaItem>) -> Unit,
    onDelete: ((Channel) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val channel = channelPreview.channel
    val item = channelPreview.candidateItem
    val fullList = channelPreview.fullItems

    val isVideo = item != null && (item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) DiscoveryViolet else AuraSubtleBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable {
                onClick(channel, item, fullList)
            }
            .background(Color.Black)
    ) {
        // 1. Motion or Static Thumbnail
        if (item != null) {
            ChannelMotionThumbnail(
                channelId = channel.id,
                item = item,
                isVisibleInViewport = isVisibleInViewport,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = getEmptyStateMessage(channel),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // 2. TOP OVERLAY: Channel Title Badge (Prominent Top Banner)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
                        endY = 140f
                    )
                )
                .padding(horizontal = AuraSpacing.M, vertical = 12.dp)
                .semantics { contentDescription = "Channel ${channel.title}" },
            contentAlignment = Alignment.TopStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (channel.isDefault) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Default Station",
                            tint = Color.Yellow,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Station",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = channel.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DiscoveryViolet.copy(alpha = 0.85f),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (channel.channelKind == ChannelKind.SEARCH_SEEDED) "Search Seeded" else "Station",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (channel.channelKind == ChannelKind.SEARCH_SEEDED && onDelete != null) {
                        IconButton(
                            onClick = { onDelete(channel) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Channel",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. BOTTOM OVERLAY: Preview Item Info & Tune-In Play Button
        if (item != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                            startY = 140f
                        )
                    )
                    .padding(AuraSpacing.M),
                contentAlignment = Alignment.BottomStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Preview: ${item.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Text(
                            text = "${item.genre} • ${if (isVideo) "Motion Preview" else "Photo"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = "Tune In Channel",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}
