package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import com.example.data.MediaItem
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeMenuSheet(
    activeItem: MediaItem,
    isVideo: Boolean,
    currentPositionMs: Long,
    playbackSpeed: Float,
    isLoopEnabled: Boolean,
    abPointA: Long?,
    abPointB: Long?,
    isAbRepeatActive: Boolean,
    onDismiss: () -> Unit,
    onUpdateRating: ((String, Float) -> Unit)?,
    onFavoriteToggle: (String) -> Unit,
    onDeleteRequest: () -> Unit,
    onBeginVisualSearch: (MediaItem) -> Unit,
    onSeeSimilar: (MediaItem) -> Unit,
    onGenerateClips: (MediaItem) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetAbPointA: (Long?) -> Unit,
    onSetAbPointB: (Long?) -> Unit,
    onSetAbRepeatActive: (Boolean) -> Unit,
    onCaptureScreenshot: () -> Unit,
    onExportAbClip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var localRating by remember(activeItem.id) { mutableFloatStateOf(activeItem.rating) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isFav = activeItem.isFavorite
    var prevFavState by remember(activeItem.id) { mutableStateOf(isFav) }
    var heartScale by remember { mutableFloatStateOf(1f) }

    val animatedHeartScale by animateFloatAsState(
        targetValue = heartScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "heart_pulse_scale"
    )

    LaunchedEffect(activeItem.id, isFav) {
        if (prevFavState != isFav) {
            prevFavState = isFav
            heartScale = 1.35f
            delay(120)
            heartScale = 1.0f
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraMidnight.copy(alpha = 0.88f),
        scrimColor = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(AuraSubtleBorder)
            )
        },
        modifier = modifier.padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Media Title Row (Single-line filename with ellipsis)
            val displayFilename = if (activeItem.title.contains(".")) activeItem.title else "${activeItem.title}.${if (isVideo) "mp4" else "jpg"}"
            Text(
                text = displayFilename,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AuraCrispWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Rating & Controls Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Five-Star Rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..5) {
                        val isStarred = i <= localRating.toInt()
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Rate $i stars",
                            tint = if (isStarred) AuraStarGold else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    localRating = i.toFloat()
                                    onUpdateRating?.invoke(activeItem.id, localRating)
                                    Toast.makeText(context, "Rated $i stars", Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                }

                // Controls Row (A/B, Screenshot, Speed, Favorite, Delete)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A/B Repeat Button
                    val abLabel = when {
                        abPointA == null -> "A/B"
                        abPointB == null -> "A: ${formatTimeMs(abPointA.toFloat())}"
                        else -> "A-B"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = abLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAbRepeatActive || abPointA != null) DiscoveryViolet else AuraCrispWhite,
                            modifier = Modifier
                                .testTag("ab_repeat_button")
                                .clickable {
                                    if (abPointA == null) {
                                        val pointA = currentPositionMs
                                        onSetAbPointA(pointA)
                                        Toast.makeText(context, "A/B: Point A set at ${formatTimeMs(pointA.toFloat())}", Toast.LENGTH_SHORT).show()
                                    } else if (abPointB == null) {
                                        val targetB = currentPositionMs
                                        val pointB = if (targetB <= abPointA) abPointA + 1000L else targetB
                                        onSetAbPointB(pointB)
                                        onSetAbRepeatActive(true)
                                        Toast.makeText(context, "A/B: Point B set at ${formatTimeMs(pointB.toFloat())}. Looping A-B", Toast.LENGTH_SHORT).show()
                                    } else {
                                        onSetAbPointA(null)
                                        onSetAbPointB(null)
                                        onSetAbRepeatActive(false)
                                        Toast.makeText(context, "A/B Repeat cleared", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        )

                        if (abPointA != null && abPointB != null) {
                            IconButton(
                                onClick = onExportAbClip,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = "Export AB Clip",
                                    tint = DiscoveryViolet,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Camera / Screenshot
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Capture Screenshot",
                        tint = AuraCrispWhite,
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("capture_frame_button")
                            .clickable { onCaptureScreenshot() }
                    )

                    // Playback Speed
                    Box {
                        Text(
                            text = "${playbackSpeed}x",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuraCrispWhite,
                            modifier = Modifier
                                .testTag("playback_speed_button")
                                .clickable { showSpeedMenu = true }
                        )

                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.background(AuraCrispWhite)
                        ) {
                            listOf(0.25f, 0.5f, 1.0f, 2.0f, 4.0f).forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${speed}x ${if (playbackSpeed == speed) "✓" else ""}",
                                            color = AuraMidnight,
                                            fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        onSetPlaybackSpeed(speed)
                                        showSpeedMenu = false
                                        Toast.makeText(context, "Playback speed: ${speed}x", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    // Favorite / Like
                    Icon(
                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFav) Color(0xFFEF4444) else AuraCrispWhite,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = animatedHeartScale
                                scaleY = animatedHeartScale
                            }
                            .clickable { onFavoriteToggle(activeItem.id) }
                    )

                    // Delete Trash Icon
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Media",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("delete_media_button")
                            .clickable {
                                onDismiss()
                                onDeleteRequest()
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Primary Full-Width Action: Begin Visual Search
            AuraButton(
                text = "Begin Visual Search",
                onClick = {
                    onBeginVisualSearch(activeItem)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Secondary Action Row (See Similar & Generate Clips)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerActionButton(
                    label = "See Similar",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSeeSimilar(activeItem)
                        onDismiss()
                    }
                )
                if (isVideo) {
                    PlayerActionButton(
                        label = "Generate Clips",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onGenerateClips(activeItem)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.White.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AuraCrispWhite,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatTimeMs(ms: Float): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
