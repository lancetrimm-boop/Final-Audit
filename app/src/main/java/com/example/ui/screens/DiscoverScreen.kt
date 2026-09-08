package com.example.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.EmotionalRole
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.ObsessionContentBatch
import com.example.data.ObsessionRecommendation
import com.example.data.RecommendationExplanation
import com.example.data.SystemDiscoveryState
import com.example.data.TasteReveal
import com.example.ui.components.AuraButton
import com.example.ui.components.AuraEngagementTunerCard
import com.example.ui.components.AuraMediaThumbnail
import com.example.ui.components.AuraSectionHeader
import com.example.ui.components.AuraTopBar
import com.example.ui.components.DiscoveryPolicyControl
import com.example.ui.components.VideoTilePreview
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraSlate
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.AuraSubtleSurface
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    repository: MediaRepository,
    viewModel: DiscoverViewModel,
    onMediaSelect: (MediaItem) -> Unit,
    onObsessionSelect: (ObsessionRecommendation) -> Unit,
    onScanAndImport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val feedState by viewModel.feedState.collectAsStateWithLifecycle()
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = detailState is ObsessionDetailState.Active) {
        repository.interactionRepository?.let { iRepo ->
            com.example.data.AuraInteractionService.logInteraction(
                repository,
                iRepo,
                com.example.data.AuraInteractionType.NAVIGATED_BACK
            )
        }
        viewModel.deselectObsession()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = this.maxWidth > this.maxHeight
        val horizontalPadding = if (isLandscape) (this.maxWidth * 0.15f) else 0.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AuraCrispWhite)
                .padding(horizontal = horizontalPadding)
        ) {
            when (val dState = detailState) {
                is ObsessionDetailState.Active -> {
                    ObsessionDetailView(
                        obsession = dState.obsession,
                        batch = dState.batch,
                        isLoading = dState.isLoading,
                        onBack = { viewModel.deselectObsession() },
                        onMediaSelect = onMediaSelect,
                        onFavoriteToggle = { id -> repository.addToFavorites(id) },
                        onExpand = { viewModel.expandCurrentObsession() },
                        onTrySomethingNew = { viewModel.trySomethingNew() },
                        repository = repository
                    )
                }
                else -> {
                    AuraSectionHeader(
                        title = "Discover",
                        subtitle = "Your personal media intelligence engine",
                        actions = {
                            IconButton(onClick = { /* Search Placeholder */ }) {
                                Icon(imageVector = Icons.Outlined.Search, contentDescription = "Search", tint = AuraMidnight)
                            }
                            IconButton(onClick = { viewModel.refresh() }) {
                                Icon(imageVector = Icons.Outlined.Sync, contentDescription = "Refresh", tint = AuraMidnight)
                            }
                        }
                    )

                    PullToRefreshBox(
                        isRefreshing = feedState is DiscoverFeedState.Loading,
                        onRefresh = { viewModel.refresh(forceNewSession = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        when (val state = feedState) {
                            is DiscoverFeedState.Loading -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = AuraSpacing.M, vertical = AuraSpacing.XS),
                                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.M)
                                ) {
                                    items(3) {
                                        com.example.ui.components.AuraSkeletonTile(
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                            is DiscoverFeedState.Error -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(text = state.message, color = Color.Red)
                                }
                            }
                            is DiscoverFeedState.Success -> {
                                if (state.tasteReveal != null) {
                                    TasteRevealScreen(
                                        reveal = state.tasteReveal,
                                        onConfirm = { viewModel.markTasteRevealSeen() }
                                    )
                                } else if (state.snapshot.obsessions.isEmpty()) {
                                    EmptyDiscoverView(
                                        onScanAndImport = onScanAndImport
                                    )
                                } else {
                                    DiscoveryFeed(
                                        obsessions = state.snapshot.obsessions,
                                        systemState = state.snapshot.systemState,
                                        onObsessionSelect = { obsession ->
                                            viewModel.selectObsession(obsession)
                                            onObsessionSelect(obsession)
                                        },
                                        repository = repository
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsessionDetailView(
    obsession: ObsessionRecommendation,
    batch: ObsessionContentBatch,
    isLoading: Boolean,
    onBack: () -> Unit,
    onMediaSelect: (MediaItem) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onExpand: () -> Unit,
    onTrySomethingNew: () -> Unit,
    repository: MediaRepository
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize().background(AuraCrispWhite)) {
        AuraTopBar(
            title = obsession.title,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AuraMidnight
                    )
                }
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = obsession.subtitle,
                        fontSize = 14.sp,
                        color = AuraSlate
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${batch.items.size} DISCOVERIES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = DiscoveryViolet,
                        letterSpacing = 1.sp
                    )
                }
            }

            items(
                items = batch.items,
                key = { it.id }
            ) { item ->
                ImmersiveMediaCard(
                    item = item,
                    onClick = {
                        val allItems = batch.items
                        val selectedIndex = allItems.indexOf(item)
                        val truncatedPlaylist = if (selectedIndex != -1) {
                            allItems.subList(selectedIndex, allItems.size)
                        } else {
                            listOf(item)
                        }

                        repository.setPlaylist(
                            items = truncatedPlaylist,
                            initialIndex = 0,
                            sourceTitle = obsession.title
                        )
                        onMediaSelect(item)
                    },
                    onFavoriteToggle = { onFavoriteToggle(item.id) },
                    repository = repository
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = DiscoveryViolet)
                    }
                }
            } else if (batch.items.isNotEmpty()) {
                item {
                    EndOfBatchView(
                        canExpand = batch.canExpand,
                        onExpand = onExpand,
                        onTrySomethingNew = onTrySomethingNew,
                        onBackToObsessions = onBack,
                        repository = repository
                    )
                }
            }
        }
    }

    // AURA REPAIR: Viewport-aware preview coordination for Discover Detail
    LaunchedEffect(listState, batch.items) {
        snapshotFlow { 
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleIds = visibleItems.mapNotNull { it.key as? String }
            val allIds = batch.items.map { it.id }
            val firstIdx = listState.firstVisibleItemIndex
            Triple(visibleIds, allIds, firstIdx to listState.isScrollInProgress)
        }.collect { (visible, all, scrollState) ->
            val (firstIdx, scrolling) = scrollState
            com.example.ui.components.PreviewCoordinator.updateWindow(visible, all, firstIdx)
            com.example.ui.components.PreviewCoordinator.setScrollingFast(scrolling)
        }
    }

    // Record exposures for the batch items as they appear
    LaunchedEffect(batch.items) {
        if (batch.items.isNotEmpty()) {
            repository.recordExposures(batch.items.map { it.id })
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            repository.forceFlushExposures()
        }
    }
}

@Composable
fun EndOfBatchView(
    canExpand: Boolean,
    onExpand: () -> Unit,
    onTrySomethingNew: () -> Unit,
    onBackToObsessions: () -> Unit,
    repository: MediaRepository
) {
    LaunchedEffect(Unit) {
        repository.interactionRepository?.let { iRepo ->
            com.example.data.AuraInteractionService.logInteraction(
                repository,
                iRepo,
                com.example.data.AuraInteractionType.END_OF_BATCH_REACHED
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AuraSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = DiscoveryViolet.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        
        Spacer(modifier = Modifier.height(AuraSpacing.M))
        
        Text(
            text = "End of current batch",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = AuraMidnight
        )
        
        Spacer(modifier = Modifier.height(AuraSpacing.XS))
        
        Text(
            text = "Aura has analyzed all candidates in this obsession. Would you like more or something different?",
            style = MaterialTheme.typography.bodySmall,
            color = AuraSlate,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp
        )
        
        Spacer(modifier = Modifier.height(AuraSpacing.L))
        
        if (canExpand) {
            AuraButton(
                text = "SEE MORE IN THIS STYLE",
                onClick = onExpand,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(AuraSpacing.S))
        }
        
        AuraButton(
            text = "TRY SOMETHING NEW",
            onClick = onTrySomethingNew,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(AuraSpacing.S))
        
        androidx.compose.material3.TextButton(
            onClick = onBackToObsessions,
            contentPadding = PaddingValues(AuraSpacing.XS)
        ) {
            Text(
                "BACK TO DISCOVER", 
                style = MaterialTheme.typography.labelLarge,
                color = AuraMutedSlate, 
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun ImmersiveMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    repository: MediaRepository
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.M)
            .clip(RoundedCornerShape(20.dp))
            .background(AuraSubtleSurface)
            .clickable { onClick() }
            .border(1.dp, AuraSubtleBorder, RoundedCornerShape(20.dp))
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(if (isLandscape) 2.5f else 1.3f)) {
            AuraMediaThumbnail(
                itemId = item.id,
                mediaType = item.mediaType,
                imageUrl = item.imageUrl,
                uriPath = item.uriPath,
                title = item.title,
                modifier = Modifier.fillMaxSize(),
                locationTag = "immersive_card"
            )

            // Top Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AuraSpacing.XS),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = onFavoriteToggle,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) Color.Red else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(AuraSpacing.M)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = AuraMidnight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            if (item.aiSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.aiSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = AuraSlate,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun DiscoveryFeed(
    obsessions: List<ObsessionRecommendation>,
    systemState: SystemDiscoveryState,
    onObsessionSelect: (ObsessionRecommendation) -> Unit,
    repository: MediaRepository
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = repository.discoverScrollIndex,
        initialFirstVisibleItemScrollOffset = repository.discoverScrollOffset
    )

    val tasteDNA by repository.tasteDNA.collectAsStateWithLifecycle()
    val preferenceProfile by repository.preferenceProfile.collectAsStateWithLifecycle()
    val stats by repository.intelligenceStats.collectAsStateWithLifecycle()
    val discoveryPolicy by repository.discoveryPolicy.collectAsStateWithLifecycle()

    val dashboardViewModel: com.example.ui.screens.IntelligenceDashboardViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return com.example.ui.screens.IntelligenceDashboardViewModel(repository.intelligenceRepository!!) as T
            }
        }
    )
    val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    val report = dashboardState.report

    // Save scroll position for restoration
    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                repository.discoverScrollIndex = index
                repository.discoverScrollOffset = offset
            }
    }

    // AURA REPAIR: Viewport-aware preview coordination for Discover Feed
    LaunchedEffect(listState, obsessions) {
        snapshotFlow { 
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            // For obsession cards, the "media" is often the first preview item
            val visibleIds = visibleItems.mapNotNull { obsessions.getOrNull(it.index)?.previewItems?.firstOrNull()?.id }
            val allIds = obsessions.mapNotNull { it.previewItems.firstOrNull()?.id }
            val firstIdx = listState.firstVisibleItemIndex
            Triple(visibleIds, allIds, firstIdx to listState.isScrollInProgress)
        }.collect { (visible, all, scrollState) ->
            val (firstIdx, scrolling) = scrollState
            com.example.ui.components.PreviewCoordinator.updateWindow(visible, all, firstIdx)
            com.example.ui.components.PreviewCoordinator.setScrollingFast(scrolling)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = AuraSpacing.XS, bottom = AuraSpacing.XXL),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.M)
    ) {
        items(
            items = obsessions,
            key = { it.id }
        ) { obsession ->
            ObsessionCard(
                obsession = obsession,
                onClick = { onObsessionSelect(obsession) },
                repository = repository
            )
        }

        item {
            Spacer(modifier = Modifier.height(AuraSpacing.M))
            
            Text(
                text = "Intelligence Tuning",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = AuraMidnight,
                modifier = Modifier.padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XS)
            )
            
            Box(modifier = Modifier.padding(horizontal = AuraSpacing.M)) {
                Column {
                    DiscoveryPolicyControl(
                        policy = discoveryPolicy,
                        onPolicyChange = { repository.updateDiscoveryPolicy(it) }
                    )
                    
                    Spacer(modifier = Modifier.height(AuraSpacing.M))
                    
                    AuraEngagementTunerCard(
                        tasteDNA = tasteDNA,
                        preferenceProfile = preferenceProfile,
                        onTasteDnaUpdate = { updatedDna -> 
                            repository.updateTasteDNA(updatedDna, isUserGenerated = true, evidenceCategory = "Discover Manual Tuning") 
                        },
                        onPreferenceProfileUpdate = { updatedProfile -> 
                            repository.updatePreferenceProfile(updatedProfile) 
                        },
                        aiDescription = report?.tasteProfile?.description,
                        showWeightsAtTop = true,
                        collapsibleSliders = true,
                        initialSlidersExpanded = false,
                        isDiscoverContext = true
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(AuraSpacing.XL))
        }
    }

    // Phase 7: Record exposures for the visible preview items
    LaunchedEffect(obsessions) {
        val allPreviewIds = obsessions.flatMap { it.previewItems.map { item -> item.id } }
        repository.recordExposures(allPreviewIds)
    }

    DisposableEffect(Unit) {
        onDispose {
            repository.forceFlushExposures()
        }
    }
}

@Composable
fun ObsessionCard(
    obsession: ObsessionRecommendation,
    onClick: () -> Unit,
    repository: MediaRepository
) {
    val mainItem = obsession.previewItems.firstOrNull() ?: return

    LaunchedEffect(obsession.id) {
        repository.interactionRepository?.let { iRepo ->
            com.example.data.AuraInteractionService.logInteraction(
                repository,
                iRepo,
                com.example.data.AuraInteractionType.OBSESSION_EXPOSURE,
                metadata = mapOf("obsessionId" to obsession.id)
            )
        }
    }

    // Entry Animation
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(700),
        label = "alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.98f,
        animationSpec = tween(700),
        label = "scale"
    )
    LaunchedEffect(Unit) { visible = true }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val cardAspectRatio = if (isLandscape) 2.2f else 1.1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.M)
            .graphicsLayer { 
                this.alpha = alpha 
                this.scaleX = scale
                this.scaleY = scale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(AuraCrispWhite)
            .clickable(
                onClickLabel = "Open ${obsession.title}",
                onClick = onClick
            )
            .border(1.dp, AuraSubtleBorder, RoundedCornerShape(20.dp))
    ) {
        // Large Media Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardAspectRatio)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        ) {
            AuraMediaThumbnail(
                itemId = mainItem.id,
                mediaType = mainItem.mediaType,
                imageUrl = mainItem.imageUrl,
                uriPath = mainItem.uriPath,
                title = mainItem.title,
                modifier = Modifier.fillMaxSize(),
                locationTag = "discover_feed"
            )
            
            // Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                        )
                    )
            )
        }

        // Details
        Column(
            modifier = Modifier.padding(AuraSpacing.M)
        ) {
            Text(
                text = obsession.title.uppercase(),
                color = DiscoveryViolet,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = obsession.subtitle,
                color = AuraMidnight,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 24.sp
            )
            
            Spacer(modifier = Modifier.height(AuraSpacing.M))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary Previews
                Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.XS)) {
                    obsession.previewItems.drop(1).take(3).forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AuraSubtleBorder)
                        ) {
                            AsyncImage(
                                model = if (item.imageUrl.isNotEmpty()) item.imageUrl else item.uriPath,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                
                AuraButton(
                    text = "EXPLORE",
                    onClick = onClick,
                    modifier = Modifier.height(36.dp).padding(start = AuraSpacing.S)
                )
            }
        }
    }
}

@Composable
fun EmptyDiscoverView(onScanAndImport: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AuraSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = AuraMutedSlate.copy(alpha = 0.3f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(AuraSpacing.M))
        Text(
            text = "Your Intelligence Awaits",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = AuraMidnight,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XS))
        Text(
            text = "Aura needs more local content to generate obsessions. Scan your device or import media to start discovering.",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraSlate,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(AuraSpacing.L))
        AuraButton(
            text = "SCAN FOR MEDIA",
            onClick = { onScanAndImport?.invoke() }
        )
    }
}

@Composable
fun RecommendationExplanationView(
    explanation: RecommendationExplanation,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AuraSubtleSurface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Why this match?",
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = DiscoveryViolet
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = explanation.primaryReason,
            fontSize = 13.sp,
            color = AuraMidnight,
            lineHeight = 18.sp
        )
        
        if (explanation.detailPoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            explanation.detailPoints.forEach { point ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(DiscoveryViolet))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = point, fontSize = 11.sp, color = AuraSlate)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        
        explanation.confidenceLabel?.let { label ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                color = DiscoveryViolet.copy(alpha = 0.7f),
                letterSpacing = 0.5.sp
            )
        }
    }
}
