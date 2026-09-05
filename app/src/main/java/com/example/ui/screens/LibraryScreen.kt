package com.example.ui.screens

import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.data.semantic.SearchRequest
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import coil.compose.AsyncImage
import com.example.util.MediaThumbnailFetcher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    mediaItems: List<MediaItem>,
    repository: MediaRepository,
    importProgress: ImportProgressState,
    scanProgress: ScanProgressState = ScanProgressState(),
    onMediaSelect: (MediaItem) -> Unit,
    onCompareLaunch: (Set<String>) -> Unit,
    onFavoriteToggle: ((String) -> Unit)? = null,
    onImportUris: ((List<Uri>) -> Unit)? = null,
    onScanDevice: (suspend () -> Unit)? = null,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>? = null,
    modifier: Modifier = Modifier
) {
    val selectedFilter by repository.libraryFilterFlow.collectAsStateWithLifecycle()
    val activeCategory by repository.activeSortCategory.collectAsStateWithLifecycle()
    val standardSort by repository.selectedStandardSort.collectAsStateWithLifecycle()
    val intelligentSort by repository.selectedIntelligentSort.collectAsStateWithLifecycle()
    val searchRequest by repository.librarySearchRequest.collectAsStateWithLifecycle()
    var isSearchActive by remember { mutableStateOf(false) }

    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = repository.libraryScrollIndex,
        initialFirstVisibleItemScrollOffset = repository.libraryScrollOffset
    )

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Library UI Preferences (Update 4)
    val libraryPrefs = remember(repository) { repository.libraryPreferences }
    val gridDensity by (libraryPrefs?.gridDensity ?: MutableStateFlow(160f)).collectAsStateWithLifecycle()
    val autoScrollSpeed by (libraryPrefs?.autoScrollSpeed ?: MutableStateFlow(AutoScrollSpeed.MEDIUM)).collectAsStateWithLifecycle()

    var isAutoScrollActive by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    // Auto-Scroll Engine (Update 4)
    LaunchedEffect(isAutoScrollActive, autoScrollSpeed, lastInteractionTime, isSelectionMode) {
        if (!isAutoScrollActive || isSelectionMode) return@LaunchedEffect

        // Resumption Delay: Wait 2s after last interaction
        val now = System.currentTimeMillis()
        val timeSinceInteraction = now - lastInteractionTime
        if (timeSinceInteraction < 2000) {
            delay(2000 - timeSinceInteraction)
        }

        var lastFrameTimeNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos == 0L) {
                    lastFrameTimeNanos = frameTimeNanos
                    return@withFrameNanos
                }
                
                val elapsedSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                lastFrameTimeNanos = frameTimeNanos
                
                val pixelsToScroll = autoScrollSpeed.pixelsPerSecond * elapsedSeconds
                
                // Perform scroll
                coroutineScope.launch {
                    gridState.scrollBy(pixelsToScroll)
                }
            }
        }
    }

    // Sync searchQuery with searchRequest when it changes from other sources
    LaunchedEffect(searchRequest) {
        isSearchActive = searchRequest !is SearchRequest.Text || searchRequest.query?.isNotEmpty() == true || searchRequest.visualVector != null
    }

    // Automatically scroll to top when filters or sorts change
    var isFirstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(selectedFilter, activeCategory, standardSort, intelligentSort) {
        if (isFirstLoad) {
            isFirstLoad = false
        } else {
            gridState.scrollToItem(0)
        }
    }

    // Clear selection mode when deletion finishes
    val deletionState by repository.safeDeleteManager.deletionState.collectAsStateWithLifecycle()
    LaunchedEffect(deletionState) {
        if (deletionState == com.example.data.cleanup.DeletionState.CONFIRMED ||
            deletionState == com.example.data.cleanup.DeletionState.CANCELLED ||
            deletionState == com.example.data.cleanup.DeletionState.FAILED) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    val showScrollToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 8 }
    }

    val latestSortedItems by repository.latestAiSortRecommendation.collectAsStateWithLifecycle()
    val mediaItemsMap by repository.mediaItemsMap.collectAsStateWithLifecycle()
    val dbState by repository.databaseState.collectAsStateWithLifecycle()

    LaunchedEffect(gridState) {
        snapshotFlow { Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                repository.libraryScrollIndex = index
                repository.libraryScrollOffset = offset
            }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty() && onImportUris != null) {
            onImportUris(uris)
        }
    }

    val visualSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bitmap = MediaThumbnailFetcher.getThumbnail(context, uri.toString())
                if (bitmap != null) {
                    repository.searchByImage(bitmap, uri.toString())
                    isSearchActive = true
                }
            }
        }
    }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        coroutineScope.launch {
            onScanDevice?.invoke()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = this.maxWidth > this.maxHeight
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AuraCrispWhite)
        ) {
            // Selection/Search/Library Header
            if (isSelectionMode) {
                // Ensure Auto-Scroll is disabled in selection mode
                SideEffect { isAutoScrollActive = false }

                SelectionHeader(
                    selectedCount = selectedIds.size,
                    onDelete = {
                        if (deleteLauncher != null) {
                            val itemsToDelete = selectedIds.mapNotNull { mediaItemsMap[it] }
                            repository.safeDeleteManager.requestDeletion(
                                context = context,
                                items = itemsToDelete,
                                recommendations = emptyList(),
                                launcher = deleteLauncher
                            )
                        }
                    },
                    onCompare = {
                        if (selectedIds.size >= 4) {
                            onCompareLaunch(selectedIds)
                        }
                    },
                    onSearchSimilar = {
                        val selectedItems = selectedIds.mapNotNull { mediaItemsMap[it] }
                        if (selectedItems.isNotEmpty()) {
                            if (selectedItems.size >= 2) {
                                repository.searchByMultipleImages(selectedItems)
                            } else {
                                val mediaItem = selectedItems[0]
                                coroutineScope.launch {
                                    val bitmap = MediaThumbnailFetcher.getThumbnail(context, mediaItem.uriPath)
                                    if (bitmap != null) {
                                        repository.searchByImage(bitmap, mediaItem.uriPath)
                                    }
                                }
                            }
                            isSelectionMode = false
                            selectedIds = emptySet()
                            isSearchActive = true
                        }
                    },
                    onCancel = {
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }
                )
            } else if (isSearchActive) {
                SearchHeader(
                    searchRequest = searchRequest,
                    onQueryChange = { 
                        repository.librarySearchQuery = it
                    },
                    onImageSearchClick = {
                        visualSearchLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemoveAnchor = {
                        repository.removeVisualAnchor()
                    },
                    onRemoveReference = {
                        repository.removeVisualReference(it)
                    },
                    onExit = { 
                        isSearchActive = false
                        repository.clearSearch()
                    }
                )
            } else {
                if (!isLandscape) {
                    AuraSectionHeader(
                        title = "Library",
                        subtitle = "Your complete media collection"
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.titleSmall,
                            color = DiscoveryViolet,
                            fontWeight = FontWeight.Bold
                        )
                        UtilityControlsRow(
                            selectedFilter = selectedFilter,
                            onFilterChange = { repository.libraryFilter = it },
                            onSearchClick = { isSearchActive = true },
                            onImportClick = {
                                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                            onSyncClick = {
                                if (dbState == DatabaseState.READY) {
                                    scanPermissionLauncher.launch(permissionsToRequest)
                                } else {
                                    repository.initDatabase(context)
                                }
                            },
                            onScrollToTop = {
                                coroutineScope.launch { gridState.scrollToItem(0) }
                            },
                            showScrollToTop = showScrollToTop,
                            dbState = dbState,
                            isCompact = true,
                            isAutoScrollActive = isAutoScrollActive,
                            onAutoScrollToggle = { isAutoScrollActive = it },
                            autoScrollSpeed = autoScrollSpeed,
                            onAutoScrollSpeedChange = { libraryPrefs?.setAutoScrollSpeed(it) }
                        )
                    }
                }
            }

            if (!isSelectionMode && !isSearchActive && !isLandscape) {
                UtilityControlsRow(
                    selectedFilter = selectedFilter,
                    onFilterChange = { repository.libraryFilter = it },
                    onSearchClick = { isSearchActive = true },
                    onImportClick = {
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    onSyncClick = {
                        if (dbState == DatabaseState.READY) {
                            scanPermissionLauncher.launch(permissionsToRequest)
                        } else {
                            repository.initDatabase(context)
                        }
                    },
                    onScrollToTop = {
                        coroutineScope.launch { gridState.scrollToItem(0) }
                    },
                    showScrollToTop = showScrollToTop,
                    dbState = dbState,
                    isAutoScrollActive = isAutoScrollActive,
                    onAutoScrollToggle = { isAutoScrollActive = it },
                    autoScrollSpeed = autoScrollSpeed,
                    onAutoScrollSpeedChange = { libraryPrefs?.setAutoScrollSpeed(it) }
                )
            }

            CompactControlsRow(
                activeCategory = activeCategory,
                onCategoryChange = { repository.sortCategory = it },
                standardSort = standardSort,
                onStandardSortChange = { repository.standardSort = it },
                intelligentSort = intelligentSort,
                onIntelligentSortChange = { repository.intelligentSort = it }
            )

            if (scanProgress.isScanning || importProgress.isImporting) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                        color = DiscoveryViolet,
                        trackColor = AuraSubtleBorder
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = scanProgress.isScanning,
                onRefresh = { 
                    repository.refreshSort()
                    if (dbState == com.example.data.DatabaseState.READY) {
                        scanPermissionLauncher.launch(permissionsToRequest)
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                if (latestSortedItems.isEmpty()) {
                    EmptyLibraryView(
                        onImportClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onScanClick = { scanPermissionLauncher.launch(permissionsToRequest) }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    // 1. Pause auto-scroll on any manual gesture
                                    lastInteractionTime = System.currentTimeMillis()
                                    
                                    // 2. Handle Pinch-to-Zoom
                                    if (zoom != 1f) {
                                        val newDensity = gridDensity / zoom
                                        libraryPrefs?.setGridDensity(newDensity)
                                    }
                                }
                            }
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = gridDensity.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            itemsIndexed(
                                items = latestSortedItems,
                                key = { _, item -> item.id }
                            ) { _, item ->
                                val mediaItem = mediaItemsMap[item.id]
                                if (mediaItem != null) {
                                    AuraMediaTile(
                                        item = mediaItem,
                                        isSelected = selectedIds.contains(item.id),
                                        isSelectionMode = isSelectionMode,
                                        onClick = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            if (isSelectionMode) {
                                                selectedIds = if (selectedIds.contains(item.id)) {
                                                    selectedIds - item.id
                                                } else {
                                                    selectedIds + item.id
                                                }
                                                if (selectedIds.isEmpty()) isSelectionMode = false
                                            } else {
                                                val currentMediaItems = latestSortedItems.mapNotNull { mediaItemsMap[it.id] }
                                                val clickedItemIndex = currentMediaItems.indexOfFirst { it.id == item.id }
                                                
                                                if (clickedItemIndex != -1) {
                                                    repository.setLibraryPlaylist(
                                                        items = currentMediaItems,
                                                        initialIndex = clickedItemIndex
                                                    )
                                                    onMediaSelect(currentMediaItems[clickedItemIndex])
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            if (!isSelectionMode) {
                                                isSelectionMode = true
                                                selectedIds = setOf(item.id)
                                            } else {
                                                selectedIds = if (selectedIds.contains(item.id)) {
                                                    selectedIds - item.id
                                                } else {
                                                    selectedIds + item.id
                                                }
                                                if (selectedIds.isEmpty()) isSelectionMode = false
                                            }
                                        },
                                        onLike = {
                                            repository.recordLike(item.id)
                                            repository.addToFavorites(item.id)
                                        }
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
private fun SelectionHeader(
    selectedCount: Int,
    onDelete: () -> Unit,
    onCompare: () -> Unit,
    onSearchSimilar: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiscoveryGradient)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$selectedCount Selected",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selectedCount == 1) {
                IconButton(onClick = onSearchSimilar) {
                    Icon(Icons.Default.ImageSearch, contentDescription = "Search Similar", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
            } else if (selectedCount >= 2) {
                Surface(
                    onClick = onSearchSimilar,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.height(36.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesomeMotion, contentDescription = null, tint = DiscoveryViolet, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "FIND COMMON",
                                color = DiscoveryViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Surface(
                onClick = onCompare,
                enabled = selectedCount >= 4,
                shape = CircleShape,
                color = if (selectedCount >= 4) Color.White else Color.White.copy(alpha = 0.2f),
                modifier = Modifier.height(36.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "COMPARE ($selectedCount)",
                        color = if (selectedCount >= 4) DiscoveryViolet else Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    }
}

@Composable
private fun SearchHeader(
    searchRequest: SearchRequest,
    onQueryChange: (String) -> Unit,
    onImageSearchClick: () -> Unit,
    onRemoveAnchor: () -> Unit,
    onRemoveReference: (Int) -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .background(AuraCrispWhite)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Default.Close, contentDescription = "Exit Search", tint = AuraMidnight)
        }
        
        if (searchRequest is SearchRequest.MultiVisual) {
            LazyRow(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp).widthIn(max = 200.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                itemsIndexed(searchRequest.referenceUris) { index, uri ->
                    Box(modifier = Modifier.padding(end = 4.dp)) {
                        Surface(
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                            color = AuraSubtleBorder,
                            onClick = { onRemoveReference(index) }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Reference $index",
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        } else if (searchRequest is SearchRequest.Visual || searchRequest is SearchRequest.Compound) {
            val visualUri = when (searchRequest) {
                is SearchRequest.Visual -> searchRequest.referenceUri
                is SearchRequest.Compound -> searchRequest.referenceUri
                else -> null
            }
            Box(modifier = Modifier.padding(start = 4.dp, end = 8.dp)) {
                Surface(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                    color = AuraSubtleBorder,
                    onClick = onRemoveAnchor
                ) {
                    if (visualUri != null) {
                        AsyncImage(
                            model = visualUri,
                            contentDescription = "Visual Reference",
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.padding(8.dp))
                    }
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val query = when (searchRequest) {
            is SearchRequest.Text -> searchRequest.query ?: ""
            is SearchRequest.Compound -> searchRequest.query ?: ""
            else -> ""
        }
        
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { 
                Text(
                    if (searchRequest !is SearchRequest.Text) "Add constraint..." else "Search your library...", 
                    color = AuraMutedSlate 
                ) 
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = DiscoveryViolet
            ),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AuraMidnight),
            trailingIcon = {
                if (searchRequest is SearchRequest.Text) {
                    IconButton(onClick = onImageSearchClick) {
                        Icon(Icons.Default.ImageSearch, contentDescription = "Search by Image", tint = DiscoveryViolet)
                    }
                }
            }
        )
    }
}

@Composable
private fun UtilityControlsRow(
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onImportClick: () -> Unit,
    onSyncClick: () -> Unit,
    onScrollToTop: () -> Unit,
    showScrollToTop: Boolean,
    dbState: DatabaseState,
    isCompact: Boolean = false,
    isAutoScrollActive: Boolean = false,
    onAutoScrollToggle: (Boolean) -> Unit = {},
    autoScrollSpeed: AutoScrollSpeed = AutoScrollSpeed.MEDIUM,
    onAutoScrollSpeedChange: (AutoScrollSpeed) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (isCompact) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Side: Filters or Auto-Scroll
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (!isCompact) {
                AuraFilterChip(
                    label = "All",
                    isSelected = selectedFilter == "ALL",
                    onClick = { onFilterChange("ALL") }
                )
                AuraFilterChip(
                    label = "Photos",
                    isSelected = selectedFilter == "PHOTO",
                    onClick = { onFilterChange("PHOTO") }
                )
                AuraFilterChip(
                    label = "Videos",
                    isSelected = selectedFilter == "VIDEO",
                    onClick = { onFilterChange("VIDEO") }
                )
            } else {
                // Compact mode (Landscape): Just show symbols or minimal
                IconButton(onClick = { onFilterChange("ALL") }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "All", tint = if (selectedFilter == "ALL") DiscoveryViolet else AuraMutedSlate)
                }
            }
        }

        // Right Side: Action Icons + AutoScroll
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AutoScrollToggle(
                isEnabled = isAutoScrollActive,
                onToggle = onAutoScrollToggle
            )
            
            if (isAutoScrollActive) {
                AutoScrollSpeedSelector(
                    currentSpeed = autoScrollSpeed,
                    onSpeedSelected = onAutoScrollSpeedChange
                )
            }

            if (showScrollToTop && !isCompact) {
                IconButton(onClick = onScrollToTop, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Scroll to top", tint = AuraMutedSlate)
                }
            }
            
            IconButton(onClick = onSearchClick, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = AuraMidnight)
            }
            
            IconButton(onClick = onImportClick, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import", tint = AuraMidnight)
            }
            
            IconButton(onClick = onSyncClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Sync, 
                    contentDescription = "Sync", 
                    tint = if (dbState == DatabaseState.READY) DiscoveryViolet else AuraMutedSlate
                )
            }
        }
    }
}

@Composable
private fun CompactControlsRow(
    activeCategory: SortCategory,
    onCategoryChange: (SortCategory) -> Unit,
    standardSort: StandardSortOption,
    onStandardSortChange: (StandardSortOption) -> Unit,
    intelligentSort: IntelligentSortOption,
    onIntelligentSortChange: (IntelligentSortOption) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraSortSelector(
            label = "Sort Mode",
            currentOption = if (activeCategory == SortCategory.STANDARD) "Standard" else "Aura AI",
            isSelected = true,
            options = SortCategory.entries,
            onOptionSelected = onCategoryChange,
            onPillClick = { /* No-op for main pill */ },
            getDisplayName = { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } },
            selectedColor = if (activeCategory == SortCategory.STANDARD) AuraMutedSlate else DiscoveryViolet
        )

        Spacer(modifier = Modifier.width(4.dp))

        if (activeCategory == SortCategory.STANDARD) {
            AuraSortSelector(
                label = "Criteria",
                currentOption = when (standardSort) {
                    StandardSortOption.NEWEST_FIRST -> "Newest"
                    StandardSortOption.TITLE_ASC -> "Name A-Z"
                    StandardSortOption.TITLE_DESC -> "Name Z-A"
                    StandardSortOption.MOST_PLAYED -> "Popular"
                    StandardSortOption.RECENTLY_PLAYED -> "Recent"
                    StandardSortOption.SHORTEST_DURATION -> "Shortest"
                    StandardSortOption.LONGEST_DURATION -> "Longest"
                    StandardSortOption.RANDOM -> "Shuffle"
                    StandardSortOption.LEAST_PLAYED -> "Unplayed"
                },
                isSelected = (activeCategory == SortCategory.STANDARD),
                options = StandardSortOption.entries,
                onOptionSelected = onStandardSortChange,
                onPillClick = { /* Toggle menu */ },
                getDisplayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { char -> char.uppercase() } }
            )
        } else {
            AuraSortSelector(
                label = "Intelligence",
                currentOption = when (intelligentSort) {
                    IntelligentSortOption.PERSONALIZED -> "Personalized"
                    IntelligentSortOption.DISCOVER -> "Discover"
                    IntelligentSortOption.REDISCOVER -> "Rediscover"
                    IntelligentSortOption.HIDDEN_GEMS -> "Hidden Gems"
                    IntelligentSortOption.FAVORITES -> "Favorites"
                    IntelligentSortOption.SURPRISE_ME -> "Surprise Me"
                },
                isSelected = (activeCategory == SortCategory.INTELLIGENT),
                options = IntelligentSortOption.entries,
                onOptionSelected = onIntelligentSortChange,
                onPillClick = { /* Toggle menu */ },
                getDisplayName = { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } }
            )
        }
    }
}

@Composable
private fun EmptyLibraryView(
    onImportClick: () -> Unit,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = AuraMutedSlate.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Your library is empty",
            style = MaterialTheme.typography.titleLarge,
            color = AuraMidnight
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Import media from your device or scan for local content to start building your intelligence profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraSlate,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onImportClick) {
                Text("IMPORT")
            }
            OutlinedButton(onClick = onScanClick) {
                Text("SCAN DEVICE")
            }
        }
    }
}
