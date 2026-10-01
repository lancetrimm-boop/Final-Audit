package com.example.data

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.first
import com.example.compatibility.*
import com.example.data.contribution.*
import com.example.data.db.*
import com.example.data.intelligence.*
import com.example.data.semantic.*
import com.example.data.entitlement.EntitlementRepository
import com.example.data.billing.BillingManager
import com.example.data.blueprint.BlueprintArtifactManager
import com.example.ui.models.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.random.Random

// --- Domain Models ---

data class ClipInteractionSummary(val title: String, val previewCount: Int, val selectCount: Int, val exportCount: Int, val score: Int)
data class AISkipStats(val totalSkipForwards: Int = 0, val totalSkipBacks: Int = 0, val totalSkipReversals: Int = 0, val totalWatchedDestinations: Int = 0)
data class EngagementMetrics(val totalPlays: Int = 0, val favoriteCount: Int = 0, val averageRating: Float = 0f, val personalizationScore: Int = 0, val totalComparisons: Int = 0, val microMomentCount: Int = 0, val itemsDiscovered: Int = 0, val totalClipPreviews: Int = 0, val totalClipSelections: Int = 0, val totalClipExports: Int = 0, val topEngagedClips: List<ClipInteractionSummary> = emptyList(), val pairwiseDiagnostics: PairwiseDiagnostics = PairwiseDiagnostics(), val aiSkipStats: AISkipStats = AISkipStats())
data class PairwiseDiagnostics(val totalEligibleMedia: Int = 0, val top100CandidatePoolSize: Int = 0, val comparedCandidateCount: Int = 0, val neverComparedCount: Int = 0, val poolRefreshTimestamp: Long = 0L, val topCandidateIds: List<String> = emptyList(), val recentRepetitionCount: Int = 0, val lastSelectionReason: String = "Not initialized")
data class ImportProgressState(val isImporting: Boolean = false, val processedFiles: Int = 0, val totalFiles: Int = 0, val progressPercent: Int = 0, val statusText: String = "")
data class ScanProgressState(val scanSessionId: Long = 0L, val isScanning: Boolean = false, val isManual: Boolean = false, val discoveredCount: Int = 0, val processedCount: Int = 0, val failedCount: Int = 0, val totalCount: Int = 0, val statusText: String = "", val isComplete: Boolean = false, val errorCode: ScanError? = null)

enum class ScanError { PERMISSION_DENIED, STORAGE_ACCESS_FAILED, DATABASE_ERROR, MIGRATION_FAILED, DATABASE_CORRUPT, ENCRYPTION_ERROR, QUERY_EMPTY, UNKNOWN_ERROR, QUERY_FAILURE, CANCELED }

sealed class DiscoveryResult {
    data class Complete(val entities: List<MediaEntity>, val discoveredIds: Set<String>, val scannedVolumes: Set<String>, val scannedMediaTypes: Set<String>) : DiscoveryResult()
    data class Incomplete(val reason: String, val errorCode: ScanError, val cause: Throwable? = null) : DiscoveryResult()
}

data class PlaylistState(
    val items: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val authoritativeMediaId: String? = null,
    val sourceTitle: String = "",
    val channel: Channel? = null,
    val channelFilterType: String? = null
) {
    val currentItem: MediaItem? get() = items.getOrNull(currentIndex)
    val hasNext: Boolean get() = (currentIndex < items.size - 1)
    val hasPrevious: Boolean get() = (currentIndex > 0)
}

enum class DatabaseState { NOT_INITIALIZED, INITIALIZING, TRANSITION_REQUIRED, TRANSITIONING, VERIFYING, READY, TRANSITION_FAILED, CORRUPTED, ENCRYPTION_FAILED, TIMEOUT }
enum class AIState { NOT_INITIALIZED, BOOTSTRAP, EARLY_READY, HEAVY_READY, ERROR, READY, DISABLED }

enum class SortCategory { STANDARD, INTELLIGENT }

enum class StandardSortOption(val displayName: String, val description: String) {
    NEWEST_FIRST("Recently Added", "Your latest additions."),
    RECENTLY_PLAYED("Recently Played", "Media you've watched recently."),
    TITLE_ASC("Title A-Z", "Alphabetical."),
    TITLE_DESC("Title Z-A", "Reverse alphabetical."),
    SHORTEST_DURATION("Shortest", "Short clips."),
    LONGEST_DURATION("Longest", "Long videos."),
    MOST_PLAYED("Most Played", "Most viewed."),
    LEAST_PLAYED("Least Played", "Least viewed."),
    RANDOM("Random", "Fresh random order."),
    SIZE("File Size", "Largest first."),
    RATING("Rating", "Highest rated first.")
}

enum class IntelligentSortOption(val displayName: String, val description: String) {
    PERSONALIZED("Personalized", "AI thoughts based on Taste DNA."),
    DISCOVER("Discover", "Unseen gems from your library."),
    REDISCOVER("Rediscover", "Favorites you haven't seen in a while."),
    HIDDEN_GEMS("Hidden Gems", "High quality items you might have missed."),
    FAVORITES("Favorites", "Highly rated and liked media."),
    SURPRISE_ME("Surprise Me", "Random AI selection."),
    RANKING_REFINEMENT("Refinement", "Items needing your feedback.")
}

/**
 * Main repository for media items and Aura intelligence integration.
 */
class MediaRepository(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    fun getMoshi(): Moshi = moshi
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var database: AuraDatabase? = null
    fun getDatabase(): AuraDatabase? = database

    @Volatile var intelligenceRepository: IntelligenceRepository? = null; private set
    @Volatile var interactionRepository: InteractionRepository? = null; private set
    @Volatile var entitlementRepository: EntitlementRepository? = null; private set
    @Volatile var billingManager: BillingManager? = null; private set
    @Volatile var playbackErrorLogRepository: PlaybackErrorLogRepository? = null; private set
    @Volatile var searchFeedbackRepository: SearchFeedbackRepository? = null; private set
    @Volatile var contributionQueueRepository: ContributionQueueRepository? = null; private set
    @Volatile var conversionQueueRepository: ConversionQueueRepository? = null; private set
    @Volatile var semanticRepresentationRepository: SemanticRepresentationRepository? = null; private set
    @Volatile var embeddingProvider: EmbeddingProvider? = null; private set
    @Volatile var mobileCLIPProvider: MobileCLIPEmbeddingProvider? = null; private set
    @Volatile var mobileClipTextProvider: MobileCLIPTextEmbeddingProvider? = null; private set
    @Volatile var semanticCandidateRetriever: SemanticCandidateRetriever? = null; private set
    @Volatile var semanticSearchService: SemanticSearchService? = null; private set
    @Volatile var semanticIndexingService: SemanticIndexingService? = null; private set
    @Volatile var visualIndexingService: VisualIndexingService? = null; private set
    @Volatile var blueprintArtifactManager: BlueprintArtifactManager? = null; private set

    @Volatile var intelligenceCore: AuraIntelligenceCore? = null; private set
    @Volatile var hybridSearchEngine: HybridSearchEngine? = null; private set

    init {
        try {
            val field = android.database.CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB CursorWindow size for large media libraries
        } catch (_: Throwable) {}

        val bootstrapRetriever = ProductionLexicalRetriever()
        val router = RetrievalRouter(bootstrapRetriever, null, null)
        val core = AuraIntelligenceCore(this, router, dispatcher = dispatcher)
        intelligenceCore = core
        hybridSearchEngine = DefaultHybridSearchEngine(core)
    }

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList()); val mediaItems = _mediaItems.asStateFlow()
    val mediaItemsMap = _mediaItems.map { list -> list.associateBy { it.id } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val _searchSeededChannels = MutableStateFlow<List<Channel>>(emptyList())
    val searchSeededChannels: StateFlow<List<Channel>> = _searchSeededChannels.asStateFlow()

    suspend fun saveSearchSeededChannel(channel: Channel) {
        val current = _searchSeededChannels.value.toMutableList()
        if (current.none { it.id == channel.id }) {
            current.add(channel)
            _searchSeededChannels.value = current
            ChannelRegistry.addSearchSeededChannel(channel)
            try {
                val adapter = moshi.adapter<List<Channel>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
                )
                database?.userPreferenceDao()?.insertPreference(
                    UserPreferenceEntity("search_seeded_channels", adapter.toJson(current))
                )
            } catch (e: Exception) {}
        }
    }

    suspend fun deleteSearchSeededChannel(channelId: String) {
        val current = _searchSeededChannels.value.filterNot { it.id == channelId }
        _searchSeededChannels.value = current
        ChannelRegistry.removeSearchSeededChannel(channelId)
        try {
            val adapter = moshi.adapter<List<Channel>>(
                com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
            )
            database?.userPreferenceDao()?.insertPreference(
                UserPreferenceEntity("search_seeded_channels", adapter.toJson(current))
            )
        } catch (e: Exception) {}
    }
    private val _databaseState = MutableStateFlow(DatabaseState.NOT_INITIALIZED); val databaseState = _databaseState.asStateFlow()
    private val _aiState = MutableStateFlow(AIState.NOT_INITIALIZED); val aiState = _aiState.asStateFlow()
    private val _initializationDetail = MutableStateFlow("Pending..."); val initializationDetail = _initializationDetail.asStateFlow()
    private val _databaseErrorMessage = MutableStateFlow<String?>(null); val databaseErrorMessage = _databaseErrorMessage.asStateFlow()
    
    private val _libraryFilter = MutableStateFlow("ALL"); val libraryFilterFlow = _libraryFilter.asStateFlow()
    var libraryFilter: String get() = _libraryFilter.value; set(v) { _libraryFilter.value = v }
    
    private val _activeSortCategory = MutableStateFlow(SortCategory.STANDARD); val activeSortCategory = _activeSortCategory.asStateFlow()
    var sortCategory: SortCategory get() = _activeSortCategory.value; set(v) { _activeSortCategory.value = v; scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("active_sort_category", v.name)) } }
    
    private val _selectedStandardSort = MutableStateFlow(StandardSortOption.NEWEST_FIRST); val selectedStandardSort = _selectedStandardSort.asStateFlow()
    var standardSort: StandardSortOption get() = _selectedStandardSort.value; set(v) { if (v == StandardSortOption.RANDOM) refreshSort(); _selectedStandardSort.value = v; scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("selected_standard_sort", v.name)) } }
    
    private val _selectedIntelligentSort = MutableStateFlow(IntelligentSortOption.PERSONALIZED); val selectedIntelligentSort = _selectedIntelligentSort.asStateFlow()
    var intelligentSort: IntelligentSortOption get() = _selectedIntelligentSort.value; set(v) { if (v == IntelligentSortOption.SURPRISE_ME) refreshSort(); _selectedIntelligentSort.value = v; scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("selected_intelligent_sort", v.name)) } }
    
    private val _librarySearchRequest = MutableStateFlow<SearchRequest>(SearchRequest.Text("")); val librarySearchRequest = _librarySearchRequest.asStateFlow()
    var librarySearchQuery: String get() = _librarySearchRequest.value.query ?: ""; set(v) { val cur = _librarySearchRequest.value; _librarySearchRequest.value = when { v.isBlank() -> cur.visualVector?.let { SearchRequest.Visual(it, cur.referenceUri) } ?: SearchRequest.Text("") ; cur.visualVector != null -> SearchRequest.Compound(v, cur.visualVector!!, cur.referenceUri); else -> SearchRequest.Text(v) } }
    
    private val _activePlaylist = MutableStateFlow<PlaylistState?>(null); val activePlaylist = _activePlaylist.asStateFlow()
    private val _isPlayerActive = MutableStateFlow(false); val isPlayerActive = _isPlayerActive.asStateFlow()
    private val _isLibraryReady = MutableStateFlow(false); val isLibraryReady = _isLibraryReady.asStateFlow()
    private val _isForegroundVisualQueryActive = MutableStateFlow(false); val isForegroundVisualQueryActive = _isForegroundVisualQueryActive.asStateFlow()
    
    private val _compareSelectionSession = MutableStateFlow(CompareSelectionSession()); val compareSelectionSession = _compareSelectionSession.asStateFlow()
    private val _compareMediaType = MutableStateFlow(CompareMediaTypeFilter.PHOTOS); val compareMediaType = _compareMediaType.asStateFlow()
    private val _compareStrategy = MutableStateFlow(CompareStrategy.PERSONALIZED); val compareStrategy = _compareStrategy.asStateFlow()
    private val _compareSort = MutableStateFlow(CompareSortOption.RECOMMENDED); val compareSort = _compareSort.asStateFlow()
    
    private val _consentState = MutableStateFlow(ConsentState.NOT_DECIDED); val consentState = _consentState.asStateFlow()
    private val emptyMediaItem = MediaItem(id = "", title = "", mediaType = "PHOTO", year = 2024, duration = "", genre = "Media")
    private val _pairwiseState = MutableStateFlow(PairwiseComparison("p1", 1, 50, emptyMediaItem, emptyMediaItem)); val pairwiseState = _pairwiseState.asStateFlow()
    
    private val _intelligenceStats = MutableStateFlow(IntelligenceStats()); val intelligenceStats = _intelligenceStats.asStateFlow()
    private val _tasteDNA = MutableStateFlow(TasteDNA()); val tasteDNA = _tasteDNA.asStateFlow()
    private val _preferenceProfile = MutableStateFlow(TasteDNA.PreferenceProfile()); val preferenceProfile = _preferenceProfile.asStateFlow()
    private val _discoveryPolicy = MutableStateFlow(DiscoveryPolicy()); val discoveryPolicy = _discoveryPolicy.asStateFlow()
    private val _userIntent = MutableStateFlow(UserIntent()); val userIntent = _userIntent.asStateFlow()
    private val _creatorProfiles = MutableStateFlow<Map<String, CreatorProfile>>(emptyMap()); val creatorProfiles = _creatorProfiles.asStateFlow()
    private val _comparisonCounts = MutableStateFlow<Map<String, Int>>(emptyMap()); val comparisonCounts = _comparisonCounts.asStateFlow()
    private val _pairwiseDiagnostics = MutableStateFlow(PairwiseDiagnostics()); val pairwiseDiagnostics = _pairwiseDiagnostics.asStateFlow()
    
    private val _importProgress = MutableStateFlow(ImportProgressState()); val importProgress = _importProgress.asStateFlow()
    private val _scanProgress = MutableStateFlow(ScanProgressState()); val scanProgress = _scanProgress.asStateFlow()
    private val _watchHistory = MutableStateFlow<List<MediaItem>>(emptyList()); val watchHistory = _watchHistory.asStateFlow()
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList()); val recentSearches = _recentSearches.asStateFlow()
    private val _storedEvidence = MutableStateFlow<List<EvidenceRecord>>(emptyList()); val storedEvidence = _storedEvidence.asStateFlow()
    private val _signatureStyleProfile = MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())); val signatureStyleProfile = _signatureStyleProfile.asStateFlow()
    private val _latestPerformance = MutableStateFlow<OperationPerformance?>(null); val latestPerformance = _latestPerformance.asStateFlow()
    private val _librarySessionSeed = MutableStateFlow(Random.nextLong())
    
    private val _discoverSnapshot = MutableStateFlow<DiscoverSnapshot?>(null); val discoverSnapshot = _discoverSnapshot.asStateFlow()

    private var initJob: Job? = null
    val momentDispatcher = AuraMomentDispatcher(this)
    val safeDeleteManager = com.example.data.cleanup.SafeDeleteManager(this, scope)
    var visualContextEngine = com.example.data.visual.VisualContextEngine(this)
    var applicationContext: Context? = null; private set
    
    var lastPlaybackPositionMs: Long = 0; private set
    var isResumingFromBackground: Boolean = false; private set

    private val _searchErrorMessage = MutableStateFlow<String?>(null); val searchErrorMessage = _searchErrorMessage.asStateFlow()
    private val _activeVisualReferences = MutableStateFlow<List<MediaItem>>(emptyList()); val activeVisualReferences = _activeVisualReferences.asStateFlow()
    var libraryPreferences: LibraryPreferences? = null; private set
    private val _gridDensity = MutableStateFlow(160f); val gridDensity = _gridDensity.asStateFlow()
    private val _autoScrollSpeed = MutableStateFlow(AutoScrollSpeed.MEDIUM); val autoScrollSpeed = _autoScrollSpeed.asStateFlow()

    var libraryScrollIndex: Int = 0
    var libraryScrollOffset: Int = 0
    var discoverScrollIndex: Int = 0
    var discoverScrollOffset: Int = 0

    private val _latestLibraryProvenance = MutableStateFlow<Map<String, DecisionProvenance>>(emptyMap()); val latestLibraryProvenance = _latestLibraryProvenance.asStateFlow()
    private val _latestDiscoverProvenance = MutableStateFlow<Map<String, DecisionProvenance>>(emptyMap()); val latestDiscoverProvenance = _latestDiscoverProvenance.asStateFlow()

    private val _libraryRepeatMode = MutableStateFlow(false); val libraryRepeatMode = _libraryRepeatMode.asStateFlow()
    var repeatMode: Boolean get() = _libraryRepeatMode.value; set(v) { _libraryRepeatMode.value = v }

    private val _slideshowDelaySeconds = MutableStateFlow(4)
    val slideshowDelaySeconds: StateFlow<Int> = _slideshowDelaySeconds.asStateFlow()

    var slideshowDelaySec: Int
        get() = _slideshowDelaySeconds.value
        set(value) {
            val clamped = value.coerceIn(1, 10)
            _slideshowDelaySeconds.value = clamped
            scope.launch {
                try {
                    database?.userPreferenceDao()?.insertPreference(
                        UserPreferenceEntity("slideshow_delay_seconds", clamped.toString())
                    )
                } catch (_: Exception) {}
            }
        }

    companion object { 
        val instance = MediaRepository()
        fun getInstance(context: Context) = instance.also { it.initDatabase(context) } 

        const val MAX_ADJUSTMENT_PER_VOTE = 0.01
        const val TOTAL_ADJUSTMENT_LIMIT = 0.20
    }

    fun refreshSort() { _librarySessionSeed.value = Random.nextLong() }
    private fun toLibraryItemUi(item: MediaItem) = LibraryItemUi(item.id, item.title, item.mediaType, item.imageUrl, item.uriPath, item.duration, item.selectionReason)
    
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val latestSortedFullItemsFlow: StateFlow<List<MediaItem>> = combine(mediaItems, _tasteDNA, _preferenceProfile, _libraryFilter, _activeSortCategory, _selectedStandardSort, _selectedIntelligentSort, _librarySearchRequest.debounce { if (it is SearchRequest.Visual) 0L else 300L }.distinctUntilChanged(), _librarySessionSeed, _discoveryPolicy, _userIntent, _intelligenceStats, _creatorProfiles, _comparisonCounts, _aiState) { args -> 
        val items = args[0] as List<MediaItem>; val dna = args[1] as TasteDNA; val profile = args[2] as TasteDNA.PreferenceProfile; val filter = args[3] as String; val category = args[4] as SortCategory; val standard = args[5] as StandardSortOption; val intelligent = args[6] as IntelligentSortOption; val search = args[7] as SearchRequest; val seed = args[8] as Long; val policy = args[9] as DiscoveryPolicy; val intent = args[10] as UserIntent; val stats = args[11] as IntelligenceStats; val creators = args[12] as Map<String, CreatorProfile>; val counts = args[13] as Map<String, Int>
        if (search.query.isNullOrBlank() && search.visualVector == null) getFilteredAndSortedMedia(filter, category, standard, intelligent, seed, items, dna, profile, policy, intent, stats, creators, counts)
        else hybridSearchEngine?.let { eng -> 
            if (eng.isSemanticReady()) {
                try { 
                    val res = eng.search(search, HybridSearchConfig(topK = 100))
                    if (res.isSuccess) { 
                        val map = items.associateBy { it.id }
                        res.candidates.mapNotNull { map[it.mediaId] }.filter { matchesFilterType(it, filter) } 
                    } else {
                        if (search is SearchRequest.Visual || search is SearchRequest.MultiVisual) emptyList()
                        else performLegacySearch(items.filter { matchesFilterType(it, filter) }, search.query ?: "") 
                    }
                } catch (e: Exception) { 
                    if (search is SearchRequest.Visual || search is SearchRequest.MultiVisual) emptyList()
                    else performLegacySearch(items.filter { matchesFilterType(it, filter) }, search.query ?: "") 
                } 
            } else {
                if (search is SearchRequest.Visual || search is SearchRequest.MultiVisual) emptyList()
                else performLegacySearch(items.filter { matchesFilterType(it, filter) }, search.query ?: "")
            }
        } ?: (if (search is SearchRequest.Visual || search is SearchRequest.MultiVisual) emptyList() else performLegacySearch(items.filter { matchesFilterType(it, filter) }, search.query ?: ""))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    val latestAiSortRecommendation = latestSortedFullItemsFlow.map { list -> list.map { toLibraryItemUi(it) } }.distinctUntilChanged().flowOn(dispatcher).stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestLibraryPresentation = combine(latestSortedFullItemsFlow, _libraryFilter, _activeSortCategory, _selectedStandardSort, _selectedIntelligentSort, _librarySearchRequest, _searchErrorMessage, _activeVisualReferences, _aiState, _databaseState, _gridDensity, _autoScrollSpeed, _importProgress, _scanProgress, latestLibraryProvenance) { args ->
        val items = args[0] as List<MediaItem>; val filter = args[1] as String; val category = args[2] as SortCategory; val std = args[3] as StandardSortOption; val intel = args[4] as IntelligentSortOption; val search = args[5] as SearchRequest; val err = args[6] as String?; val visual = args[7] as List<MediaItem>; val ai = args[8] as AIState; val db = args[9] as DatabaseState; val grid = args[10] as Float; val auto = args[11] as AutoScrollSpeed; val import = args[12] as ImportProgressState; val scan = args[13] as ScanProgressState; val prov = args[14] as Map<String, DecisionProvenance>
        LibraryPresentationState(items, filter, category, std, intel, search, err, visual, ai, db, false, auto, grid, false, emptySet(), import, scan, prov)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), LibraryPresentationState(emptyList()))

    val latestDiscoverPresentation = combine(_discoverSnapshot, _databaseState, _aiState, latestDiscoverProvenance, _tasteDNA, _preferenceProfile, _discoveryPolicy) { args ->
        val snap = args[0] as DiscoverSnapshot?; val db = args[1] as DatabaseState; val ai = args[2] as AIState; val prov = args[3] as Map<String, DecisionProvenance>; val dna = args[4] as TasteDNA; val profile = args[5] as TasteDNA.PreferenceProfile; val policy = args[6] as DiscoveryPolicy
        DiscoverPresentationState(snap?.obsessions ?: emptyList(), ConfidenceEngine.calculateDiscoveryState(_mediaItems.value, _intelligenceStats.value), ai == AIState.BOOTSTRAP, null, null, dna, profile, policy, prov)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), DiscoverPresentationState())

    fun initDatabase(context: Context) {
        applicationContext = context.applicationContext
        if (blueprintArtifactManager == null) blueprintArtifactManager = BlueprintArtifactManager(context.applicationContext)
        if (libraryPreferences == null) libraryPreferences = LibraryPreferences(context.applicationContext)
        
        if (_databaseState.value == DatabaseState.READY) return

        synchronized(this) {
            if (initJob?.isActive == true) return
            
            if (database != null) {
                _databaseState.value = DatabaseState.READY
                startDatabaseObservers(database!!)
                if (intelligenceCore == null) scope.launch { initAI(context) }
                return
            }

            _databaseState.value = DatabaseState.INITIALIZING
            _initializationDetail.value = "Initializing secure layer..."
            initJob = scope.launch {
                try {
                    // Try to initialize SQLCipher, but don't crash if it fails (e.g. in tests)
                    try {
                        SQLCipherInitializer.initialize(context)
                    } catch (e: Exception) {
                        Log.w("MediaRepository", "SQLCipher init failed, continuing...")
                    }
                    val transitionResult = LegacyDatabaseEncryptionMigrator.ensureEncryption(context)
                    if (transitionResult is LegacyDatabaseEncryptionMigrator.TransitionResult.Failure) { _databaseErrorMessage.value = transitionResult.reason; _databaseState.value = if (transitionResult.reason.contains("corrupt")) DatabaseState.CORRUPTED else DatabaseState.TRANSITION_FAILED; return@launch }
                    _databaseState.value = DatabaseState.VERIFYING
                    val db = AuraDatabase.getInstance(context); database = db
                    val consentManager = ContributionConsentManager(SharedPreferencesConsentStorage(context))
                    contributionQueueRepository = ContributionQueueRepository(db.contributionQueueDao(), consentManager)
                    scope.launch { consentManager.consentStateFlow.collect { _consentState.value = it } }
                    intelligenceRepository = IntelligenceRepository(db.intelligenceDao(), this@MediaRepository, moshi, scope, db)
                    interactionRepository = InteractionRepository(db.interactionDao(), moshi, scope)
                    playbackErrorLogRepository = PlaybackErrorLogRepository(db.playbackErrorLogDao(), scope)
                    entitlementRepository = EntitlementRepository(db.userPreferenceDao(), scope)
                    billingManager = BillingManager(context, entitlementRepository!!, scope)
                    searchFeedbackRepository = SearchFeedbackRepository(db.searchFeedbackDao(), scope)
                    conversionQueueRepository = ConversionQueueRepository(db.conversionJobDao(), db.userPreferenceDao(), androidx.work.WorkManager.getInstance(context))
                    semanticRepresentationRepository = RoomSemanticRepresentationRepository(db.semanticRepresentationDao())
                    initAI(context)
                    _databaseState.value = DatabaseState.READY
                    startDatabaseObservers(db)
                    launch { backfillSemantics(); AuraEnrichmentWorker.schedule(context) }
                    LegacyDatabaseEncryptionMigrator.cleanupLegacyBackups(context)
                } catch (t: Throwable) { if (t !is CancellationException) { _databaseErrorMessage.value = t.message; _databaseState.value = DatabaseState.TRANSITION_FAILED } }
            }
        }
    }

    private var observersJob: Job? = null

    private fun isDatabaseClosedException(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return e is IllegalStateException && (
            msg.contains("connection pool has been closed", ignoreCase = true) ||
            (msg.contains("database", ignoreCase = true) && msg.contains("closed", ignoreCase = true))
        )
    }
    
    private fun startDatabaseObservers(db: AuraDatabase) {
        observersJob?.cancel()
        observersJob = scope.launch {
            launch {
                try {
                    val existing = db.mediaDao().getAllMediaSync()
                    if (existing.size < 60) {
                        val existingIds = existing.map { it.id }.toSet()
                        val mockItems = (1..60).map { i ->
                            val id = "mock_lib_$i"
                            if (id !in existingIds) {
                                MediaEntity(
                                    id = id,
                                    title = "Library Item $i",
                                    mediaType = if (i % 2 == 0) "VIDEO" else "PHOTO",
                                    year = 2024,
                                    durationMs = 30000L,
                                    genre = if (i % 3 == 0) "Action" else "Cinematic",
                                    imageUrl = "content://media/external/images/media/$i",
                                    uriPath = "content://media/external/images/media/$i",
                                    compatibilityStatus = CompatibilityStatus.PLAYABLE.name,
                                    dateAdded = System.currentTimeMillis() - (i * 1000L)
                                )
                            } else null
                        }.filterNotNull()
                        if (mockItems.isNotEmpty()) {
                            db.mediaDao().insertAll(mockItems)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MediaRepository", "Failed to seed mock library items", e)
                }
            }
            launch {
                try {
                    db.mediaDao().getAllMedia().conflate().transform { emit(it); if (_scanProgress.value.isScanning) delay(3000) }.collect { entities ->
                        val items = entities.map { it.toMediaItem() }.filter { !it.isDeleted && it.compatibilityStatus !in listOf(CompatibilityStatus.CORRUPT, CompatibilityStatus.UNSUPPORTED, CompatibilityStatus.DELETED) }
                        _mediaItems.value = items
                        _activePlaylist.update { p -> if (p == null) null else { val list = p.items.map { s -> items.find { it.id == s.id } ?: s }; val idx = p.authoritativeMediaId?.let { id -> list.indexOfFirst { it.id == id }.let { if (it != -1) it else p.currentIndex.coerceAtMost(list.size-1) } } ?: p.currentIndex.coerceAtMost(list.size-1); p.copy(items = list, currentIndex = idx, authoritativeMediaId = list.getOrNull(idx)?.id) } }
                        if (items.filter { it.itemCount == null }.size >= 2) refreshPairwiseCandidatePoolAndSelectNext(false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is IllegalStateException && isDatabaseClosedException(e)) {
                        // DB closed
                    } else {
                        Log.e("MediaRepository", "Error observing media database", e)
                    }
                }
            }
            launch {
                try {
                    db.mediaDao().getWatchHistory().collect { _watchHistory.value = it.map { it.toMediaItem() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    db.searchHistoryDao().getRecentSearches().collect { _recentSearches.value = it.map { it.query } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    db.pairwiseDao().getAllOutcomes().collect { outcomes -> val counts = mutableMapOf<String, Int>(); outcomes.forEach { counts[it.optionAId] = (counts[it.optionAId] ?: 0) + 1; counts[it.optionBId] = (counts[it.optionBId] ?: 0) + 1 }; _comparisonCounts.value = counts }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    val tasteDnaAdapter = moshi.adapter(TasteDNA::class.java)
                    val profileAdapter = moshi.adapter(TasteDNA.PreferenceProfile::class.java)
                    val discoveryPolicyAdapter = moshi.adapter(DiscoveryPolicy::class.java)
                    db.userPreferenceDao().getPreference("taste_dna")?.value?.let { try { tasteDnaAdapter.fromJson(it)?.let { _tasteDNA.value = it.sanitize() } } catch (e: Exception) {} }
                    db.userPreferenceDao().getPreference("preference_profile")?.value?.let { try { profileAdapter.fromJson(it)?.let { _preferenceProfile.value = it.sanitize() } } catch (e: Exception) {} }
                    db.userPreferenceDao().getPreference("discovery_policy")?.value?.let { try { discoveryPolicyAdapter.fromJson(it)?.let { _discoveryPolicy.value = it } } catch (e: Exception) {} }
                    db.userPreferenceDao().getPreference("slideshow_delay_seconds")?.value?.let { str ->
                        str.toIntOrNull()?.let { delayVal ->
                            _slideshowDelaySeconds.value = delayVal.coerceIn(1, 10)
                        }
                    }
                    db.userPreferenceDao().getPreference("search_seeded_channels")?.value?.let { json ->
                        try {
                            val channelListAdapter = moshi.adapter<List<Channel>>(
                                com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
                            )
                            channelListAdapter.fromJson(json)?.let { loaded ->
                                _searchSeededChannels.value = loaded
                                loaded.forEach { ChannelRegistry.addSearchSeededChannel(it) }
                            }
                        } catch (e: Exception) {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
            launch {
                try {
                    db.creatorDao().getAllCreators().collect { _creatorProfiles.value = it.associate { it.id to CreatorProfile(it.id, it.name, it.platform, it.affinityScore, it.interactionCount, it.lastInteractionTimestamp, if (it.topMoodTagsJson.isBlank()) emptyList() else it.topMoodTagsJson.split(",")) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
            launch {
                try {
                    db.evidenceDao().getAllEvidence().collect { _storedEvidence.value = it.map { EvidenceRecord(id = it.id, tier = EvidenceTier.valueOf(it.tier), sampleCount = it.sampleCount, score = it.score, quality = it.quality, source = it.source, timestamp = it.timestamp, associatedManifestId = it.associatedManifestId) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
        }
    }

    private suspend fun initAI(context: Context) {
        if (intelligenceCore != null && aiState.value == AIState.READY) return
        _aiState.value = AIState.BOOTSTRAP
        try {
            val engine = try { OnnxRuntimeMiniLMInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/all-minilm-l6-v2.onnx")) } catch (e: Exception) { LocalMiniLMInferenceEngine() }
            val tokenizer = try { BertWordPieceTokenizer.fromVocabText(context.assets.open("models/vocab.txt").use { it.bufferedReader().readText() }) } catch (e: Exception) { BertWordPieceTokenizer() }
            val provider = MiniLMEmbeddingProvider(engine, tokenizer); embeddingProvider = provider
            val retriever = DefaultSemanticCandidateRetriever(semanticRepresentationRepository!!); semanticCandidateRetriever = retriever
            semanticSearchService = DefaultSemanticSearchService(provider, retriever)
            semanticIndexingService = DefaultSemanticIndexingService(provider, retriever, semanticRepresentationRepository!!)
            scope.launch { try { retriever.initializeIndex(provider.descriptor.primaryType, provider.descriptor) } catch (e: Exception) {} }
            val router = RetrievalRouter(ProductionLexicalRetriever(), semanticSearchService as? SemanticRetrievalProvider, null)
            intelligenceCore = AuraIntelligenceCore(this, router, dispatcher = dispatcher)
            hybridSearchEngine = DefaultHybridSearchEngine(intelligenceCore!!)
            try {
                val clipEngine = try {
                    OnnxRuntimeMobileCLIPInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/mobileclip_s0_image.onnx"))
                } catch (e: Exception) {
                    Log.w("MediaRepository", "Failed to load MobileCLIP ONNX image model, falling back to LocalMobileCLIPInferenceEngine", e)
                    LocalMobileCLIPInferenceEngine()
                }
                mobileCLIPProvider = MobileCLIPEmbeddingProvider(clipEngine)

                val clipTokenizer = try {
                    ClipBpeTokenizer.fromAssets(context.assets.open("models/mobileclip_vocab.json").use { it.bufferedReader().readText() }, context.assets.open("models/mobileclip_merges.txt").use { it.bufferedReader().readText() })
                } catch (e: Exception) {
                    ClipBpeTokenizer(emptyMap(), emptyList())
                }
                val textEngine = try {
                    OnnxRuntimeMobileCLIPTextInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/mobileclip_s0_text.onnx"))
                } catch (e: Exception) {
                    LocalMobileCLIPTextInferenceEngine()
                }
                mobileClipTextProvider = MobileCLIPTextEmbeddingProvider(textEngine, clipTokenizer)

                visualIndexingService = DefaultVisualIndexingService(mobileCLIPProvider!!, retriever, semanticRepresentationRepository!!)
                scope.launch { try { visualIndexingService?.initializeIndex() } catch (e: Exception) {} }
                visualContextEngine = com.example.data.visual.VisualContextEngine(this, mobileCLIPProvider, semanticRepresentationRepository, semanticCandidateRetriever)
                router.visualProvider = DefaultMobileCLIPVisualRetriever(DefaultSemanticSearchService(mobileClipTextProvider!!, retriever))
                hybridSearchEngine = DefaultHybridSearchEngine(intelligenceCore!!)
            } catch (e: Exception) {
                Log.e("MediaRepository", "MobileCLIP initialization error", e)
                if (mobileCLIPProvider == null) {
                    mobileCLIPProvider = MobileCLIPEmbeddingProvider(LocalMobileCLIPInferenceEngine())
                }
            }
            _aiState.value = AIState.READY
        } catch (e: Exception) { _aiState.value = AIState.ERROR }
    }

    fun getMediaItemById(id: String) = _mediaItems.value.find { it.id == id }
    
    fun logInteraction(item: MediaItem) {
        val now = System.currentTimeMillis(); _mediaItems.update { list -> list.map { if (it.id == item.id) it.copy(viewCount = it.viewCount + 1, lastViewedTimestamp = now) else it } }
        scope.launch { database?.mediaDao()?.let { dao -> dao.getMediaById(item.id)?.let { dao.update(it.copy(playCount = it.playCount + 1, lastViewedTimestamp = now)) } } }
        if (_tasteDNA.value.isFineTuningEnabled) { var dna = _tasteDNA.value; PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.005, 0.20) }; if (dna != _tasteDNA.value) updateTasteDNA(dna, false, "View") }
        learnPreferenceSignals(item, isSuccess = true)
    }
    
    fun recordMediaCompletion(id: String) {
        getMediaItemById(id)?.let { item ->
            _mediaItems.update { list -> list.map { if (it.id == id) it.copy(progress = 1.0f) else it } }
            scope.launch { database?.mediaDao()?.let { dao -> dao.getMediaById(id)?.let { dao.update(it.copy(progress = 1.0f)) } } }
            if (_tasteDNA.value.isFineTuningEnabled) { var dna = _tasteDNA.value; PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.01, 0.20) }; updateTasteDNA(dna, false, "Completion") }
            learnPreferenceSignals(item, isCompletion = true, isSuccess = true)
        }
    }
    
    private fun learnPreferenceSignals(item: MediaItem, isCompletion: Boolean = false, isDiscovery: Boolean = false, isSuccess: Boolean = true) {
        val current = _preferenceProfile.value; val reason = item.selectionReason ?: ""; val staged = current.copy(interactionsCount = current.interactionsCount + 1, similaritySignal = current.similaritySignal + (if (isCompletion || reason.contains("match")) 1 else 0), collaborativeSignal = current.collaborativeSignal + (if (isDiscovery || reason.contains("Discovery")) 1 else 0))
        if (staged.interactionsCount >= 10) updatePreferenceProfile(staged.copy(contentSimilarityWeight = staged.contentSimilarityWeight + (if (staged.similaritySignal > 3) 0.01 else 0.0), collaborativeWeight = staged.collaborativeWeight + (if (staged.collaborativeSignal > 3) 0.01 else 0.0), interactionsCount = 0).normalize(2.0))
        else { _preferenceProfile.value = staged; scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("preference_profile", moshi.adapter(TasteDNA.PreferenceProfile::class.java).toJson(staged))) } }
    }
    
    fun updateTasteDNA(dna: TasteDNA, isUserGenerated: Boolean = false, evidenceCategory: String = "Manual") { _tasteDNA.value = dna; scope.launch { val tasteDnaAdapter = moshi.adapter(TasteDNA::class.java); database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("taste_dna", tasteDnaAdapter.toJson(dna))); refreshPairwiseCandidatePoolAndSelectNext(false) } }
    
    fun updatePreferenceProfile(profile: TasteDNA.PreferenceProfile) { _preferenceProfile.value = profile; scope.launch { val profileAdapter = moshi.adapter(TasteDNA.PreferenceProfile::class.java); database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("preference_profile", profileAdapter.toJson(profile))); refreshPairwiseCandidatePoolAndSelectNext(false) } }
    
    fun injectEvidence(tier: EvidenceTier, count: Int, score: Double, quality: Double, manifestId: String? = null) { scope.launch { val id = UUID.randomUUID().toString(); database?.evidenceDao()?.insertEvidence(EvidenceEntity(id, tier.name, count, score, quality, "Agent", System.currentTimeMillis(), manifestId)); intelligenceRepository?.onEvidenceAvailable(id) } }
    
    fun clearEvidence() { scope.launch { database?.evidenceDao()?.clearAll() } }
    
    private fun scoreMediaItemForPersonalization(mediaId: String): Float = getMediaItemById(mediaId)?.let { intelligenceCore?.scorePersonalization(it, _tasteDNA.value) } ?: 0.5f
    
    private val pairwiseLock = Any()
    private val pairwiseWins = mutableMapOf<String, Int>()
    private val pairwiseLosses = mutableMapOf<String, Int>()
    private val recentPairs = mutableListOf<Pair<String, String>>()
    private val recentItemIds = mutableListOf<String>()
    
    @Volatile private var preFetchedPair: Pair<MediaItem, MediaItem>? = null
    @Volatile private var cachedDiscoveryState: SystemDiscoveryState? = null
    @Volatile private var cachedDiscoveryStateTimestamp: Long = 0L

    private fun getDiscoveryStateMemoized(items: List<MediaItem>): SystemDiscoveryState {
        val now = System.currentTimeMillis()
        val cached = cachedDiscoveryState
        if (cached != null && (now - cachedDiscoveryStateTimestamp < 30_000L)) {
            return cached
        }
        val fresh = ConfidenceEngine.calculateDiscoveryState(items, _intelligenceStats.value)
        cachedDiscoveryState = fresh
        cachedDiscoveryStateTimestamp = now
        return fresh
    }

    fun refreshPairwiseCandidatePoolAndSelectNext(forceNextPair: Boolean = true) {
        scope.launch(Dispatchers.Default) {
            val session = _compareSelectionSession.value
            val mediaTypeFilter = _compareMediaType.value
            val rawItems = if (session.isActive) _mediaItems.value.filter { it.id in session.selectedIds } else _mediaItems.value
            val items = rawItems.filter { item ->
                val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("MOVIE", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true) || item.mediaType.startsWith("MOVIE", ignoreCase = true)
                when (mediaTypeFilter) {
                    CompareMediaTypeFilter.PHOTOS -> !isVideo
                    CompareMediaTypeFilter.VIDEOS -> isVideo
                }
            }
            val eligible = items.filter { it.itemCount == null && AuraMediaCompatibilityEngine.isEligibleForImport(it.compatibilityStatus) }
            if (eligible.size < 2) { 
                _pairwiseState.value = PairwiseComparison("p_empty", _pairwiseState.value.roundNumber, 50, emptyMediaItem, emptyMediaItem)
                preFetchedPair = null
                return@launch 
            }

            val currentPair = _pairwiseState.value
            val isCurrentEmpty = currentPair.optionA.id.isEmpty() || currentPair.optionB.id.isEmpty()
            val shouldAdvance = forceNextPair || isCurrentEmpty

            var selectedPair: Pair<MediaItem, MediaItem>? = null
            if (shouldAdvance && preFetchedPair != null) {
                selectedPair = preFetchedPair
                preFetchedPair = null
            }

            if (selectedPair == null) {
                val currentWins = synchronized(pairwiseLock) { pairwiseWins.toMap() }
                val currentLosses = synchronized(pairwiseLock) { pairwiseLosses.toMap() }
                val currentRecentPairs = synchronized(pairwiseLock) { recentPairs.toList() }
                val currentRecentItemIds = synchronized(pairwiseLock) { recentItemIds.toList() }
                val top100 = RecommendationEngine.getTop100PairwiseCandidates(
                    repository = this@MediaRepository, 
                    winsMap = currentWins, 
                    lossesMap = currentLosses, 
                    mediaTypeFilter = mediaTypeFilter.name,
                    compareStrategy = _compareStrategy.value, 
                    compareSort = _compareSort.value,
                    inputItems = eligible
                )
                val discoveryState = getDiscoveryStateMemoized(items)
                selectedPair = RecommendationEngine.selectNextPairFromPool(
                    top100, 
                    _comparisonCounts.value, 
                    if (session.isActive) session.comparedPairIds else currentRecentPairs, 
                    if (session.isActive) session.comparedPairIds.flatMap { listOf(it.first, it.second) } else currentRecentItemIds, 
                    mediaTypeFilter.name, 
                    _librarySessionSeed.value, 
                    DiscoveryPolicyManager.resolveStrategy(_discoveryPolicy.value, _userIntent.value, RecommendationObjective.RANKING_REFINEMENT, discoveryState, _tasteDNA.value, _preferenceProfile.value), 
                    _tasteDNA.value, 
                    _creatorProfiles.value, 
                    _compareStrategy.value
                )
            }

            if (shouldAdvance && selectedPair != null) {
                val next = selectedPair!!
                val round = if (session.isActive) session.roundNumber else (if (isCurrentEmpty) 1 else _pairwiseState.value.roundNumber + 1)
                _pairwiseState.value = PairwiseComparison("p$round", round, if (session.isActive) session.maxRounds else 50, next.first, next.second)
                if (!session.isActive) { 
                    synchronized(pairwiseLock) {
                        recentPairs.add(0, next.first.id to next.second.id)
                        if (recentPairs.size > 10) recentPairs.removeAt(10)
                        recentItemIds.add(0, next.first.id)
                        recentItemIds.add(0, next.second.id)
                        while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) } 
                    }
                }
                recordExposures(listOf(next.first.id, next.second.id))
            } else if (shouldAdvance && session.isActive && !session.isComplete) {
                _compareSelectionSession.update { it.copy(isComplete = true, completionReason = "All unique pairs exhausted.") }
                _pairwiseState.value = PairwiseComparison("p_empty", _pairwiseState.value.roundNumber, 50, emptyMediaItem, emptyMediaItem)
            }

            // Asynchronously pre-fetch the next pair for the subsequent vote
            try {
                val currentWins = synchronized(pairwiseLock) { pairwiseWins.toMap() }
                val currentLosses = synchronized(pairwiseLock) { pairwiseLosses.toMap() }
                val currentRecentPairs = synchronized(pairwiseLock) { recentPairs.toList() }
                val currentRecentItemIds = synchronized(pairwiseLock) { recentItemIds.toList() }
                val top100 = RecommendationEngine.getTop100PairwiseCandidates(
                    repository = this@MediaRepository, 
                    winsMap = currentWins, 
                    lossesMap = currentLosses, 
                    mediaTypeFilter = mediaTypeFilter.name,
                    compareStrategy = _compareStrategy.value, 
                    compareSort = _compareSort.value,
                    inputItems = eligible
                )
                val discoveryState = getDiscoveryStateMemoized(items)
                val nextPair = RecommendationEngine.selectNextPairFromPool(
                    top100, 
                    _comparisonCounts.value, 
                    if (session.isActive) session.comparedPairIds else currentRecentPairs, 
                    if (session.isActive) session.comparedPairIds.flatMap { listOf(it.first, it.second) } else currentRecentItemIds, 
                    mediaTypeFilter.name, 
                    _librarySessionSeed.value + 1L, 
                    DiscoveryPolicyManager.resolveStrategy(_discoveryPolicy.value, _userIntent.value, RecommendationObjective.RANKING_REFINEMENT, discoveryState, _tasteDNA.value, _preferenceProfile.value), 
                    _tasteDNA.value, 
                    _creatorProfiles.value, 
                    _compareStrategy.value
                )
                if (nextPair != null && (nextPair.first.id != _pairwiseState.value.optionA.id || nextPair.second.id != _pairwiseState.value.optionB.id)) {
                    preFetchedPair = nextPair
                }
            } catch (e: Exception) {
                Log.w("MediaRepository", "Pre-fetch pair failed", e)
            }
        }
    }
    
    var channelReplenishmentProvider: (suspend (Channel, String, List<String>) -> List<MediaItem>)? = null
    var libraryReplenishmentProvider: (suspend (List<String>) -> List<MediaItem>)? = null

    suspend fun loadNextLibraryPage(excludeIds: Set<String>, limit: Int = 20): List<MediaItem> {
        val sorted = getFilteredAndSortedMedia(
            filterType = _libraryFilter.value,
            sortCategory = _activeSortCategory.value,
            standardSort = _selectedStandardSort.value,
            intelligentSort = _selectedIntelligentSort.value,
            sessionSeed = _librarySessionSeed.value
        )
        val visible = listOf(CompatibilityStatus.PLAYABLE, CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE, CompatibilityStatus.PLAYABLE_AFTER_CONVERSION, CompatibilityStatus.THUMBNAIL_FAILED, CompatibilityStatus.NEEDS_TRANSCODE, CompatibilityStatus.UNTESTED)
        return sorted.filter { !it.isDeleted && it.compatibilityStatus in visible && it.id !in excludeIds }.take(limit)
    }

    private val LOW_WATER_THRESHOLD = 5
    @Volatile private var isReplenishing = false

    fun setPlaylist(items: List<MediaItem>, initialIndex: Int, sourceTitle: String = "Playlist") {
        if (items.isEmpty()) { _activePlaylist.value = null; return }; _isPlayerActive.value = true
        val visible = listOf(CompatibilityStatus.PLAYABLE, CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE, CompatibilityStatus.PLAYABLE_AFTER_CONVERSION, CompatibilityStatus.THUMBNAIL_FAILED, CompatibilityStatus.NEEDS_TRANSCODE, CompatibilityStatus.UNTESTED)
        val sanitized = items.filter { !it.isDeleted && it.compatibilityStatus in visible }
        if (sanitized.isEmpty()) { _activePlaylist.value = null; return }
        
        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false
        
        val idx = items.getOrNull(initialIndex)?.let { orig -> sanitized.indexOfFirst { it.id == orig.id }.let { if (it != -1) it else 0 } } ?: 0
        _activePlaylist.value = PlaylistState(sanitized, idx, sanitized.getOrNull(idx)?.id, sourceTitle)
    }

    fun setChannelPlaylist(
        channel: Channel,
        filterType: String,
        items: List<MediaItem>,
        initialIndex: Int = 0,
        sourceTitle: String
    ) {
        if (items.isEmpty()) { _activePlaylist.value = null; return }
        _isPlayerActive.value = true
        val visible = listOf(
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE,
            CompatibilityStatus.PLAYABLE_AFTER_CONVERSION,
            CompatibilityStatus.THUMBNAIL_FAILED,
            CompatibilityStatus.NEEDS_TRANSCODE,
            CompatibilityStatus.UNTESTED
        )
        val sanitized = items.filter { !it.isDeleted && it.compatibilityStatus in visible }
        if (sanitized.isEmpty()) { _activePlaylist.value = null; return }

        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false

        val safeIndex = initialIndex.coerceIn(0, (sanitized.size - 1).coerceAtLeast(0))
        _activePlaylist.value = PlaylistState(
            items = sanitized,
            currentIndex = safeIndex,
            authoritativeMediaId = sanitized.getOrNull(safeIndex)?.id,
            sourceTitle = sourceTitle,
            channel = channel,
            channelFilterType = filterType
        )
    }

    fun extendActivePlaylist(newItems: List<MediaItem>) {
        val current = _activePlaylist.value ?: return
        if (newItems.isEmpty()) return
        val visible = listOf(
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE,
            CompatibilityStatus.PLAYABLE_AFTER_CONVERSION,
            CompatibilityStatus.THUMBNAIL_FAILED,
            CompatibilityStatus.NEEDS_TRANSCODE,
            CompatibilityStatus.UNTESTED
        )
        val existingIds = current.items.map { it.id }.toSet()
        val sanitizedNew = newItems.filter { !it.isDeleted && it.compatibilityStatus in visible && !existingIds.contains(it.id) }
        if (sanitizedNew.isEmpty()) return

        _activePlaylist.value = current.copy(items = current.items + sanitizedNew)
    }
    
    fun clearPlaylist() { 
        _isPlayerActive.value = false
        _activePlaylist.value = null 
        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false
    }

    fun selectPlaylistItem(index: Int) {
        _activePlaylist.update { current ->
            if (current != null && index in current.items.indices) {
                val safeIndex = index.coerceIn(0, current.items.lastIndex)
                val next = current.items[safeIndex]
                recordView(next.id)

                val channel = current.channel
                val filterType = current.channelFilterType
                val remaining = current.items.size - 1 - safeIndex

                if (channel != null && filterType != null && remaining <= LOW_WATER_THRESHOLD && !isReplenishing) {
                    isReplenishing = true
                    val existingIds = current.items.map { it.id }
                    val provider = channelReplenishmentProvider

                    scope.launch {
                        try {
                            val fresh = provider?.invoke(channel, filterType, existingIds) ?: emptyList()
                            if (fresh.isNotEmpty()) {
                                extendActivePlaylist(fresh)
                                Log.d("MediaRepository", "Replenished ${fresh.size} items (was $remaining remaining)")
                            } else {
                                Log.d("MediaRepository", "Channel genuinely exhausted")
                            }
                        } finally {
                            isReplenishing = false
                        }
                    }
                }

                // NEW – library path replenishment
                val isLibraryPlaylist = current.channel == null &&
                    (current.sourceTitle.equals("Library", ignoreCase = true) ||
                     current.sourceTitle.startsWith("Library", ignoreCase = true))

                if (isLibraryPlaylist && remaining <= LOW_WATER_THRESHOLD && !isReplenishing) {
                    isReplenishing = true
                    val existingIds = current.items.map { it.id }
                    val provider = libraryReplenishmentProvider

                    scope.launch {
                        try {
                            val fresh = provider?.invoke(existingIds) ?: emptyList()
                            if (fresh.isNotEmpty()) {
                                extendActivePlaylist(fresh)
                                Log.d("MediaRepository", "Library replenished ${fresh.size} items")
                            }
                        } finally {
                            isReplenishing = false
                        }
                    }
                }

                current.copy(
                    currentIndex = safeIndex,
                    authoritativeMediaId = next.id
                )
            } else current
        }
    }
    fun nextPlaylistItem() { _activePlaylist.value?.let { if (it.hasNext) selectPlaylistItem(it.currentIndex + 1) } }
    fun previousPlaylistItem() { _activePlaylist.value?.let { if (it.hasPrevious) selectPlaylistItem(it.currentIndex - 1) } }
    
    fun recordView(id: String) { 
        _mediaItems.value.find { it.id == id }?.let { logInteraction(it) } 
        recordExposure(id)
    }
    fun recordExposure(id: String) { recordExposures(listOf(id)) }
    
    fun importMediaFromUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            _importProgress.value = ImportProgressState(isImporting = true, totalFiles = uris.size)
            val new = uris.mapNotNull { uri -> try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); val type = if ((context.contentResolver.getType(uri) ?: "").startsWith("video/")) "VIDEO" else "PHOTO"
                val report = AuraMediaCompatibilityEngine.analyzeMedia(context, uri.toString(), type)
                if (AuraMediaCompatibilityEngine.isEligibleForImport(report.status)) MediaEntity(id = "import_${UUID.randomUUID()}", title = "Imported", mediaType = type, imageUrl = uri.toString(), uriPath = uri.toString(), sizeBytes = report.sizeBytes, durationMs = report.durationMs, compatibilityStatus = report.status.name, conversionStatus = report.conversionStatus.name) else null
            } catch (e: Exception) { null } }
            if (new.isNotEmpty()) database?.mediaDao()?.insertAll(new)
            _importProgress.value = ImportProgressState(isImporting = false)
        }
    }
    
    private val exposureBuffer = mutableSetOf<String>(); private var exposureFlushJob: Job? = null
    fun recordExposures(ids: List<String>) { if (ids.isEmpty()) return; synchronized(exposureBuffer) { exposureBuffer.addAll(ids) }; if (exposureFlushJob?.isActive != true) exposureFlushJob = scope.launch { delay(5000); flushExposures() } }
    private suspend fun flushExposures() { val ids = synchronized(exposureBuffer) { val copy = exposureBuffer.toList(); exposureBuffer.clear(); copy }; if (ids.isEmpty()) return; database?.mediaDao()?.let { dao -> val entities = ids.mapNotNull { id -> dao.getMediaById(id)?.let { it.copy(exposureCount = it.exposureCount + 1) } }; if (entities.isNotEmpty()) dao.updateAll(entities) } }
    
    fun updateRating(id: String, rating: Float) {
        _mediaItems.update { items -> items.map { if (it.id == id) it.copy(rating = rating) else it } }
        _activePlaylist.update { p ->
            if (p == null) null
            else p.copy(items = p.items.map { if (it.id == id) it.copy(rating = rating) else it })
        }
        scope.launch { database?.mediaDao()?.getMediaById(id)?.let { database?.mediaDao()?.update(it.copy(rating = rating)) } }
    }
    fun toggleFavorite(id: String) {
        val item = getMediaItemById(id) ?: return
        val becomingFavorite = !item.isFavorite
        val updated = item.copy(isFavorite = becomingFavorite)
        _mediaItems.update { list -> list.map { if (it.id == id) updated else it } }
        _activePlaylist.update { p ->
            if (p == null) null
            else p.copy(items = p.items.map { if (it.id == id) updated else it })
        }
        
        scope.launch {
            database?.mediaDao()?.update(updated.toEntity())
            if (becomingFavorite) {
                database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = 1, timestamp = System.currentTimeMillis()))
                if (_tasteDNA.value.isFineTuningEnabled) {
                    var dna = _tasteDNA.value
                    PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> 
                        dna = dna.updateLearnedDimension(dim, mult * 0.05, 0.20) 
                    }
                    if (dna != _tasteDNA.value) updateTasteDNA(dna, false, "Favorite")
                }
            }
        }
    }
    fun addToFavorites(id: String) { toggleFavorite(id) }
    fun removeFromFavorites(id: String) { toggleFavorite(id) }
    
    fun recordLike(id: String) { 
        scope.launch { database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = 1, timestamp = System.currentTimeMillis())) }
    }
    
    fun getPairwiseWins(): Map<String, Int> = emptyMap()
    fun getPairwiseLosses(): Map<String, Int> = emptyMap()
    fun getComparisonCounts(): Map<String, Int> = _comparisonCounts.value
    suspend fun getActualLikeCounts(ids: List<String>): Map<String, Int> = database?.microMomentDao()?.getLikeCountsForBatch(ids)?.associate { it.mediaId to it.count } ?: emptyMap()
    fun reportPerformance(perf: OperationPerformance) { _latestPerformance.value = perf }
    
    private val pendingPlaybackErrors = java.util.concurrent.ConcurrentLinkedQueue<Pair<PlaybackErrorLogEntity, ((Long) -> Unit)?>>()

    private fun enqueueOrRecordError(entity: PlaybackErrorLogEntity, onComplete: ((Long) -> Unit)?) {
        val repo = playbackErrorLogRepository
        if (repo != null) {
            scope.launch {
                val id = repo.recordError(entity)
                onComplete?.invoke(id)
            }
        } else {
            pendingPlaybackErrors.add(Pair(entity, onComplete))
        }
    }

    private fun flushPendingPlaybackErrors() {
        val repo = playbackErrorLogRepository ?: return
        while (pendingPlaybackErrors.isNotEmpty()) {
            val (entity, onComplete) = pendingPlaybackErrors.poll() ?: break
            scope.launch {
                val id = repo.recordError(entity)
                onComplete?.invoke(id)
            }
        }
    }

    fun recordRouterFailure(item: MediaItem, route: PlaybackRouteResult, onComplete: ((Long) -> Unit)? = null) {
        val entity = com.example.util.AuraPlaybackDiagnostics.captureRouterFailure(route, item, null)
        enqueueOrRecordError(entity, onComplete)
    }

    fun recordPlaybackError(error: androidx.media3.common.PlaybackException, player: androidx.media3.common.Player, item: MediaItem?, onComplete: ((Long) -> Unit)? = null) {
        val entity = com.example.util.AuraPlaybackDiagnostics.captureError(error, player, item, null)
        enqueueOrRecordError(entity, onComplete)
    }

    fun updatePlaybackErrorRecovery(id: Long, attempted: Boolean, successful: Boolean?) {
        val repo = playbackErrorLogRepository ?: return
        scope.launch {
            repo.updateRecoveryStatus(id, attempted, successful)
        }
    }

    suspend fun getMediaItemByIdAuthoritative(id: String) = database?.mediaDao()?.getMediaById(id)?.toMediaItem()
    
    private var activeVisualSearchJob: Job? = null

    fun addVisualReference(item: MediaItem) { 
        val newList = (listOf(item) + _activeVisualReferences.value).distinctBy { it.id }
        _activeVisualReferences.value = newList
        triggerVisualSearchForReferences(newList)
    }

    fun removeVisualReference(id: String) {
        val newList = _activeVisualReferences.value.filterNot { it.id == id }
        _activeVisualReferences.value = newList
        if (newList.isEmpty()) {
            removeVisualAnchor()
        } else {
            triggerVisualSearchForReferences(newList)
        }
    }

    private fun triggerVisualSearchForReferences(items: List<MediaItem>) {
        activeVisualSearchJob?.cancel()
        if (items.isEmpty()) {
            removeVisualAnchor()
            return
        }
        activeVisualSearchJob = scope.launch(Dispatchers.Default) {
            searchByMultipleImagesInternal(items)
        }
    }
    
    fun recordAISkipEvent(id: String, type: String, from: Long, to: Long) {
        scope.launch {
            database?.aiSkipDao()?.insertEvent(AISkipEventEntity(mediaId = id, eventType = type, fromPosMs = from, toPosMs = to, timestamp = System.currentTimeMillis()))
        }
    }
    
    fun recordMicroMoment(id: String, taps: Int) { scope.launch { database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = taps, timestamp = System.currentTimeMillis())) } }
    fun deleteComparisonMedia(id: String) { deleteMediaItem(id) }
    fun skipComparison() { 
        val currentPair = _pairwiseState.value
        if (currentPair.optionA.id.isNotEmpty() && currentPair.optionB.id.isNotEmpty()) {
            if (_compareSelectionSession.value.isActive) {
                _compareSelectionSession.update {
                    it.copy(
                        roundNumber = it.roundNumber + 1,
                        skips = it.skips + 1,
                        comparedPairIds = it.comparedPairIds + (currentPair.optionA.id to currentPair.optionB.id)
                    )
                }
            }
            synchronized(pairwiseLock) {
                recentPairs.add(0, currentPair.optionA.id to currentPair.optionB.id)
                if (recentPairs.size > 10) recentPairs.removeAt(10)
                recentItemIds.add(0, currentPair.optionA.id)
                recentItemIds.add(0, currentPair.optionB.id)
                while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) }
            }
        }
        refreshPairwiseCandidatePoolAndSelectNext(true) 
    }
    suspend fun getSkipCounts(ids: List<String>): Map<String, Int> = database?.aiSkipDao()?.getSkipCountsForBatch(ids)?.associate { it.mediaId to it.count } ?: emptyMap()
    suspend fun getContentHashFrequencies(): List<ContentHashFrequency> = database?.mediaDao()?.getContentHashFrequencies() ?: emptyList()

    fun deleteMediaItem(id: String) {
        _mediaItems.update { it.filterNot { it.id == id } }
        scope.launch { database?.mediaDao()?.deleteById(id); deleteSemanticDataForMedia(id) }
        _activePlaylist.update { p -> if (p == null) null else {
            val list = p.items.filterNot { it.id == id }
            if (list.isEmpty()) { _isPlayerActive.value = false; null } else {
                val idx = if (p.authoritativeMediaId == id) p.currentIndex.coerceAtMost(list.size - 1) else list.indexOfFirst { it.id == p.authoritativeMediaId }.let { if (it != -1) it else p.currentIndex.coerceAtMost(list.size - 1) }
                p.copy(items = list, currentIndex = idx, authoritativeMediaId = list.getOrNull(idx)?.id)
            }
        }}
        if (_compareSelectionSession.value.isActive) {
            refreshPairwiseCandidatePoolAndSelectNext(true)
        }
    }

    suspend fun getFilteredAndSortedMedia(filterType: String, sortCategory: SortCategory, standardSort: StandardSortOption, intelligentSort: IntelligentSortOption, 
        sessionSeed: Long = 42L, inputItems: List<MediaItem> = _mediaItems.value, tasteDNA: TasteDNA = _tasteDNA.value, profile: TasteDNA.PreferenceProfile = _preferenceProfile.value,
        policy: DiscoveryPolicy = _discoveryPolicy.value, intent: UserIntent = _userIntent.value, stats: IntelligenceStats = _intelligenceStats.value, creatorProfiles: Map<String, CreatorProfile> = _creatorProfiles.value,
        comparisonCounts: Map<String, Int> = _comparisonCounts.value
    ): List<MediaItem> {
        val filtered = inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }

        if (sortCategory == SortCategory.STANDARD) {
            return when (standardSort) {
                StandardSortOption.TITLE_ASC -> filtered.sortedBy { it.title }
                StandardSortOption.TITLE_DESC -> filtered.sortedByDescending { it.title }
                StandardSortOption.NEWEST_FIRST -> filtered.sortedByDescending { it.dateAdded }
                StandardSortOption.SHORTEST_DURATION -> filtered.sortedBy { it.durationMs }
                StandardSortOption.LONGEST_DURATION -> filtered.sortedByDescending { it.durationMs }
                StandardSortOption.MOST_PLAYED -> filtered.sortedByDescending { it.viewCount }
                StandardSortOption.LEAST_PLAYED -> filtered.sortedBy { it.viewCount }
                StandardSortOption.SIZE -> filtered.sortedByDescending { it.sizeBytes }
                StandardSortOption.RATING -> filtered.sortedByDescending { it.rating }
                StandardSortOption.RECENTLY_PLAYED -> filtered.sortedByDescending { it.lastViewedTimestamp ?: 0L }
                StandardSortOption.RANDOM -> filtered.shuffled(java.util.Random(sessionSeed))
            }
        }

        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT, 
            sortOption = intelligentSort.name,
            filterType = filterType, 
            tasteDNA = tasteDNA, 
            profile = profile, 
            stats = stats, 
            creatorProfiles = creatorProfiles, 
            seed = sessionSeed, 
            policy = policy, 
            intent = intent, 
            comparisonCounts = comparisonCounts, 
            poolOverride = inputItems, 
            limit = inputItems.size.coerceAtLeast(1000)
        )
        
        val response = try {
            intelligenceCore?.processRequest(request)
        } catch (e: Exception) {
            null
        }
        
        if (response?.isSuccess == true) {
            _signatureStyleProfile.value = SignatureStyleProvider.calculateStyleProfile(tasteDNA, inputItems)
            return response.candidates.map { it.item }
        }

        return filtered
    }

    private fun matchesFilterType(item: MediaItem, filterType: String): Boolean = when (filterType.uppercase()) { "PHOTO" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE"); "VIDEO" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE"); else -> true }

    fun isItemVisibleInLibrary(item: MediaItem): Boolean = item.isEligibleForLibraryAndChannels()

    fun getQuarantinedItems(): List<MediaItem> = _mediaItems.value.filter { !it.isEligibleForLibraryAndChannels() }

    fun updateConvertedMedia(mediaId: String, sourceUri: String, outputPath: String) {
        val now = System.currentTimeMillis()
        _mediaItems.update { list ->
            list.map { item ->
                if (item.id == mediaId || item.uriPath == sourceUri) {
                    item.copy(
                        compatibilityStatus = CompatibilityStatus.PLAYABLE,
                        conversionStatus = ConversionStatus.CONVERTED,
                        convertedUri = outputPath,
                        imageUrl = if (item.imageUrl.isBlank()) outputPath else item.imageUrl,
                        lastCompatibilityCheckTimestamp = now
                    )
                } else item
            }
        }
        scope.launch {
            database?.mediaDao()?.let { dao ->
                val entity = if (mediaId.isNotBlank()) {
                    dao.getMediaById(mediaId) ?: dao.getMediaByUri(sourceUri)
                } else {
                    dao.getMediaByUri(sourceUri)
                }
                if (entity != null) {
                    dao.update(
                        entity.copy(
                            compatibilityStatus = CompatibilityStatus.PLAYABLE.name,
                            conversionStatus = ConversionStatus.CONVERTED.name,
                            convertedUri = outputPath,
                            imageUrl = if (entity.imageUrl.isBlank()) outputPath else entity.imageUrl
                        )
                    )
                }
            }
        }
    }

    private fun performLegacySearch(items: List<MediaItem>, query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        return items.filter { it.title.lowercase().contains(q) || it.genre.lowercase().contains(q) || it.moodTags.any { t -> t.lowercase().contains(q) } || it.year.toString().contains(q) }
    }

    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun scanLocalMedia(context: Context, isManual: Boolean = false): Boolean = scanLocalMediaInternal(context, isManual)
    
    private suspend fun scanLocalMediaInternal(context: Context, isManual: Boolean = false): Boolean {
        if (!hasStoragePermission(context)) {
            _scanProgress.value = ScanProgressState(
                isScanning = false,
                isComplete = false,
                isManual = isManual,
                errorCode = ScanError.PERMISSION_DENIED
            )
            return false
        }
        _scanProgress.value = ScanProgressState(isScanning = true, isManual = isManual)
        try {
            val res = discoverLocalMedia(context)
            if (res is DiscoveryResult.Complete) {
                database?.mediaDao()?.insertAll(res.entities)
                _isLibraryReady.value = true
                
                // Complete scan progress immediately so newly discovered media appears instantly in UI
                _scanProgress.value = ScanProgressState(
                    isScanning = false,
                    isComplete = true,
                    isManual = isManual,
                    discoveredCount = res.entities.size
                )
                
                // Perform background intelligence/visual enrichment asynchronously (Discover -> Persist -> Display -> Enrich)
                scope.launch(Dispatchers.Default) {
                    processPendingMedia(context, System.currentTimeMillis(), res.scannedVolumes, isManual)
                }
            } else {
                _scanProgress.value = ScanProgressState(isScanning = false, isComplete = true, isManual = isManual)
            }
            return true
        } catch (e: Exception) {
            _scanProgress.value = ScanProgressState(
                isScanning = false,
                isComplete = false,
                isManual = isManual,
                errorCode = ScanError.UNKNOWN_ERROR
            )
            if (e is CancellationException) throw e
            return false
        }
    }
    
    private suspend fun discoverLocalMedia(context: Context): DiscoveryResult {
        val entities = mutableListOf<MediaEntity>()
        val ids = mutableSetOf<String>()
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getExternalVolumeNames(context) else setOf("external")
        volumes.forEach { vol ->
            // Query Videos
            context.contentResolver.query(
                MediaStore.Video.Media.getContentUri(vol),
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.SIZE),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    entities.add(
                        MediaEntity(
                            id = "local_vid_$id",
                            title = cursor.getString(1) ?: "Video",
                            mediaType = "VIDEO",
                            uriPath = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(vol), id).toString(),
                            sizeBytes = cursor.getLong(2)
                        )
                    )
                    ids.add("local_vid_$id")
                }
            }
            // Query Images
            context.contentResolver.query(
                MediaStore.Images.Media.getContentUri(vol),
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.TITLE, MediaStore.Images.Media.SIZE),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    entities.add(
                        MediaEntity(
                            id = "local_img_$id",
                            title = cursor.getString(1) ?: "Photo",
                            mediaType = "PHOTO",
                            uriPath = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(vol), id).toString(),
                            sizeBytes = cursor.getLong(2)
                        )
                    )
                    ids.add("local_img_$id")
                }
            }
        }
        return DiscoveryResult.Complete(entities, ids, volumes, setOf("VIDEO", "PHOTO"))
    }
    
    internal suspend fun processPendingMedia(context: Context, scanId: Long, volumes: Set<String>, isManual: Boolean = false) { 
        val pending = database?.mediaDao()?.getPendingAnalysis() ?: emptyList() 
        pending.forEach { entity -> 
            val report = AuraMediaCompatibilityEngine.analyzeMedia(context, entity.uriPath, entity.mediaType) 
            database?.mediaDao()?.update(entity.copy(compatibilityStatus = report.status.name, containerFormat = report.containerFormat)) 
        } 
        backfillSemantics()
    }
    internal suspend fun reconcileExistingMedia(context: Context) { database?.mediaDao()?.getAllMediaSync()?.forEach { entity -> if (entity.compatibilityStatus == "ANALYSIS_PENDING") { val report = AuraMediaCompatibilityEngine.analyzeMedia(context, entity.uriPath, entity.mediaType); database?.mediaDao()?.update(entity.copy(compatibilityStatus = report.status.name)) } } }
    private suspend fun backfillSemantics() { database?.mediaDao()?.getAllMediaSync()?.filter { it.compatibilityStatus == "PLAYABLE" && !it.isDeleted }?.let { processSemanticsForBatch(it) } }
    private suspend fun processSemanticsForBatch(entities: List<MediaEntity>) { val sIdx = semanticIndexingService ?: return; val vIdx = visualIndexingService; val ctx = applicationContext ?: return; entities.forEach { entity -> try { val item = entity.toMediaItem(); sIdx.indexMediaItem(item); vIdx?.indexVisual(ctx, item) } catch (e: Exception) { if (e is CancellationException) throw e } } }
    private suspend fun deleteSemanticDataForMedia(id: String) { semanticRepresentationRepository?.deleteForMedia(id) }

    suspend fun loadLibraryPage(
        offset: Int,
        limit: Int = 25,
        filter: String = libraryFilter,
        sortCategory: SortCategory = _activeSortCategory.value,
        standardSort: StandardSortOption = _selectedStandardSort.value,
        intelligentSort: IntelligentSortOption = _selectedIntelligentSort.value
    ): List<LibraryItemUi> {
        val search = _librarySearchRequest.value
        val isSearchActive = (search !is SearchRequest.Text || !search.query.isNullOrBlank()) || _activeVisualReferences.value.isNotEmpty()
        val sorted = if (isSearchActive) {
            latestSortedFullItemsFlow.value
        } else {
            getFilteredAndSortedMedia(
                filterType = filter,
                sortCategory = sortCategory,
                standardSort = standardSort,
                intelligentSort = intelligentSort,
                sessionSeed = _librarySessionSeed.value
            )
        }
        val filtered = sorted.filter { it.isEligibleForLibraryAndChannels() }
        android.util.Log.d("AuraGate", "loadLibraryPage offset=$offset raw=${sorted.size} eligible=${filtered.size} searchActive=$isSearchActive")
        return filtered
            .drop(offset)
            .take(limit)
            .map { it.toLibraryItemUi() }
    }
    
    private fun MediaItem.toEntity(): MediaEntity = MediaEntity(id = id, title = title, mediaType = mediaType, year = year, duration = duration, genre = genre, imageUrl = imageUrl, gradientColorsJson = gradientColors.joinToString(","), rating = rating, isFavorite = isFavorite, progress = progress, progressText = progressText, category = category, aiSummary = aiSummary, moodTagsJson = moodTags.joinToString(","), itemCount = itemCount, eloRating = eloRating, uriPath = uriPath, dateAdded = dateAdded, dateModified = dateModified, sizeBytes = sizeBytes, durationMs = durationMs, width = width, height = height, lastViewedTimestamp = lastViewedTimestamp, playCount = viewCount, exposureCount = exposureCount, lastExposedTimestamp = lastExposedTimestamp, contentHash = contentHash, parentContentId = parentContentId, isDeleted = isDeleted, compatibilityStatus = compatibilityStatus.name, containerFormat = containerFormat, videoCodec = videoCodec, audioCodec = audioCodec, compatibilityReason = compatibilityReason, conversionStatus = conversionStatus.name, convertedUri = convertedUri ?: "", lastCompatibilityCheckTimestamp = lastCompatibilityCheckTimestamp, selectionReason = if (isEphemeralReason(selectionReason)) null else selectionReason, creatorId = creatorId, creatorName = creatorName, sourcePlatform = sourcePlatform, replacedByMediaId = replacedByMediaId)
    
    private fun MediaEntity.toMediaItem(): MediaItem { val isVideo = mediaType.equals("VIDEO", ignoreCase = true) || mediaType.equals("Movie", ignoreCase = true); val gradients = if (isVideo) listOf(0xFF1E1B4BL, 0xFF4338CAL, 0xFF7C3AEDL) else listOf(0xFF311B92L, 0xFF6A1B9AL, 0xFFD946EFL); return MediaItem(id = id, title = title, mediaType = if (isVideo) "VIDEO" else "PHOTO", year = year, duration = duration, genre = genre, imageUrl = imageUrl, gradientColors = if (gradientColorsJson.isBlank()) gradients else gradientColorsJson.split(",").mapNotNull { it.toLongOrNull() }, rating = rating, isFavorite = isFavorite, progress = progress, progressText = progressText, category = category, aiSummary = aiSummary, moodTags = if (moodTagsJson.isEmpty()) emptyList() else moodTagsJson.split(","), uriPath = uriPath, itemCount = itemCount, sizeBytes = sizeBytes, dateAdded = dateAdded, dateModified = dateModified, durationMs = durationMs, width = width, height = height, lastViewedTimestamp = lastViewedTimestamp, viewCount = playCount, exposureCount = exposureCount, lastExposedTimestamp = lastExposedTimestamp, contentHash = contentHash, parentContentId = parentContentId, eloRating = eloRating, isDeleted = isDeleted, compatibilityStatus = try { CompatibilityStatus.valueOf(compatibilityStatus) } catch (e: Exception) { CompatibilityStatus.PLAYABLE }, containerFormat = containerFormat, videoCodec = videoCodec, audioCodec = audioCodec, compatibilityReason = compatibilityReason, conversionStatus = try { ConversionStatus.valueOf(conversionStatus) } catch (e: Exception) { ConversionStatus.NONE }, convertedUri = convertedUri.ifBlank { null }, lastCompatibilityCheckTimestamp = lastCompatibilityCheckTimestamp, selectionReason = if (compatibilityStatus == "ANALYSIS_FAILED") "Retry Analysis" else selectionReason, creatorId = creatorId, creatorName = creatorName, sourcePlatform = sourcePlatform, replacedByMediaId = replacedByMediaId) }
    
    private fun isEphemeralReason(reason: String?): Boolean = reason != null && (reason in setOf("Surprise!", "For You", "Hidden Gem", "Best Match", "Personalized", "New Discovery", "Blast from the Past", "Your Favorite") || Regex("""\d+% Match""").matches(reason))
    
    suspend fun convertMediaItem(context: Context, itemId: String, deleteOriginalAfter: Boolean = false, onProgress: ((Int) -> Unit)? = null): ConversionResult { val item = getMediaItemById(itemId) ?: return ConversionResult(false, null, null, "Not found"); return AuraMediaConverter.convertToUniversalFormat(context, item, deleteOriginalAfter, onProgress).also { if (it.isSuccess && it.updatedItem != null) { val updated = it.updatedItem!!; _mediaItems.update { list -> list.map { if (it.id == itemId) updated else it } }; database?.mediaDao()?.update(updated.toEntity()) } } }
    
    suspend fun getSimilarMedia(item: MediaItem, requestId: String = "NONE"): IntelligenceResponse {
        _isForegroundVisualQueryActive.value = true
        return try {
            val req = IntelligenceRequest(mode = IntelligenceMode.SIMILAR, referenceItemId = item.id, requestId = requestId, limit = 50)
            intelligenceCore?.processRequest(req) ?: IntelligenceResponse(requestId, IntelligenceMode.SIMILAR, emptyList(), latencyMs = 0, isSuccess = false, errorMessage = "Intelligence Core not initialized")
        } finally {
            _isForegroundVisualQueryActive.value = false
        }
    }
    
    fun recordSearch(query: String) { if (query.isBlank()) return; scope.launch(Dispatchers.IO) { database?.let { db -> db.searchHistoryDao().deleteSearchByQuery(query); db.searchHistoryDao().insertSearch(SearchHistoryEntity(query = query)) } } }
    fun clearSearchHistory() { scope.launch(Dispatchers.IO) { database?.searchHistoryDao()?.clearSearchHistory() } }
    fun updateDiscoveryPolicy(p: DiscoveryPolicy) { _discoveryPolicy.value = p }
    fun updateConsentState(state: ConsentState) { contributionQueueRepository?.consentManager?.setConsentState(state) }
    fun isDiscoverSnapshotStale(): Boolean = _discoverSnapshot.value?.let { System.currentTimeMillis() - it.generationId > 30 * 60 * 1000L } ?: true
    fun updateDiscoverSnapshot(snapshot: DiscoverSnapshot) { _discoverSnapshot.value = snapshot }
    fun clearSearch() { _librarySearchRequest.value = SearchRequest.Text("") }
    fun searchByImage(bitmap: android.graphics.Bitmap, uri: String? = null) {
        val scaled = if (bitmap.width > 512 || bitmap.height > 512) android.graphics.Bitmap.createScaledBitmap(bitmap, 512, 512, true) else bitmap
        scope.launch(Dispatchers.Default) {
            _isForegroundVisualQueryActive.value = true
            try {
                if (_aiState.value != AIState.READY) {
                    _aiState.first { it == AIState.READY || it == AIState.ERROR }
                }
                val provider = mobileCLIPProvider
                if (provider != null) {
                    val result = provider.generateEmbedding("query_${UUID.randomUUID()}", SemanticInput.ExplicitBitmap(scaled), "query_image")
                    if (result is EmbeddingResult.Success) {
                        _librarySearchRequest.value = SearchRequest.Visual(result.representation.vector, uri)
                    }
                } else {
                    Log.w("MediaRepository", "searchByImage: mobileCLIPProvider is null after AI initialization")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("MediaRepository", "searchByImage error", e)
            } finally {
                _isForegroundVisualQueryActive.value = false
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }
    fun removeVisualAnchor() { _librarySearchRequest.value = SearchRequest.Text(_librarySearchRequest.value.query ?: "") }
    fun addRejectedMedia(entity: RejectedMediaEntity) { scope.launch { database?.rejectedMediaDao()?.insert(entity) } }
    fun forceFlushExposures() { scope.launch { flushExposures() } }
    fun setLibraryPlaylist(items: List<MediaItem>, initialIndex: Int) { setPlaylist(items, initialIndex, "Library") }

    val favoritesSections: StateFlow<List<IntelligentSection>> = mediaItems.map { items ->
        val favorites = items.filter { it.isFavorite }
        listOf(IntelligentSection("Favorites", "Everything you've liked and rated highly.", favorites))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMediaEntity(entity: MediaEntity) { scope.launch { database?.mediaDao()?.insert(entity) } }
    suspend fun getEngagementMetrics(): EngagementMetrics = getEngagementMetricsInternal()
    private suspend fun getEngagementMetricsInternal(): EngagementMetrics {
        val db = database ?: return EngagementMetrics()
        val allItems = _mediaItems.value
        val voteCount = db.pairwiseDao().getVoteCount()
        val clipDao = db.clipInteractionDao()
        val skipDao = db.aiSkipDao()
        return EngagementMetrics(
            totalPlays = allItems.sumOf { it.viewCount }, favoriteCount = allItems.count { it.isFavorite },
            averageRating = if (allItems.any { it.rating > 0 }) allItems.filter { it.rating > 0 }.map { it.rating }.average().toFloat() else 0f,
            personalizationScore = _intelligenceStats.value.personalizationScore, totalComparisons = voteCount, itemsDiscovered = allItems.size,
            totalClipPreviews = clipDao.getTotalClipPreviews(), totalClipSelections = clipDao.getTotalClipSelections(), totalClipExports = clipDao.getTotalClipExports(),
            topEngagedClips = clipDao.getTopEngagedClips().map { ClipInteractionSummary(it.clipTitle, it.previewCount, it.selectCount, it.exportCount, it.previewCount + it.selectCount*2 + it.exportCount*5) },
            pairwiseDiagnostics = _pairwiseDiagnostics.value,
            aiSkipStats = AISkipStats(skipDao.getTotalSkipForwards(), skipDao.getTotalSkipBacks(), skipDao.getTotalSkipReversals(), skipDao.getTotalWatchedDestinations())
        )
    }

    suspend fun generateClosedLoopReport(baseline: Double = 50.0, target: Double = 50.0): ClosedLoopReport {
        val metrics = getEngagementMetrics()
        val evidenceList = mutableListOf<EvidenceRecord>()
        if (metrics.totalPlays + metrics.totalComparisons > 0) evidenceList.add(EvidenceRecord(UUID.randomUUID().toString(), EvidenceTier.PRODUCTION, metrics.totalPlays + metrics.totalComparisons, metrics.personalizationScore.toDouble(), 0.9, "AuraTelemetry"))
        evidenceList.addAll(_storedEvidence.value)
        return ClosedLoopEngine.evaluate(baseline, metrics.personalizationScore.toDouble(), target, evidenceList)
    }
    
    fun startCompareSelectionSession(selectedIds: Set<String>) {
        if (selectedIds.size < 4) return
        _compareSelectionSession.value = CompareSelectionSession(isActive = true, selectedIds = selectedIds, originalCount = selectedIds.size, roundNumber = 1)
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }
    fun restartCompareSelectionSession() {
        _compareSelectionSession.update { it.copy(roundNumber = 1, wins = 0, losses = 0, skips = 0, comparedPairIds = emptyList(), isComplete = false, completionReason = null) }
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }
    fun exitCompareSelectionSession() { _compareSelectionSession.value = CompareSelectionSession(); refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareMediaType(filter: CompareMediaTypeFilter) { _compareMediaType.value = filter; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareStrategy(strategy: CompareStrategy) { _compareStrategy.value = strategy; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareSort(sort: CompareSortOption) { _compareSort.value = sort; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun recordComparisonVote(chosenId: String) {
        val currentPair = _pairwiseState.value
        Log.i("PAIRWISE_TRACE", "RECORD_COMPARISON_VOTE_START: chosenId=$chosenId optionA=${currentPair.optionA.id} optionB=${currentPair.optionB.id}")
        if (currentPair.optionA.id.isEmpty() || currentPair.optionB.id.isEmpty()) return
        
        if (_compareSelectionSession.value.isActive) {
            _compareSelectionSession.update { it.copy(roundNumber = it.roundNumber + 1, wins = if (chosenId == currentPair.optionA.id) it.wins + 1 else it.wins, 
                losses = if (chosenId == currentPair.optionB.id) it.losses + 1 else it.losses, comparedPairIds = it.comparedPairIds + (currentPair.optionA.id to currentPair.optionB.id)) }
        }
        
        val idA = currentPair.optionA.id
        val idB = currentPair.optionB.id
        _comparisonCounts.update { current ->
            val cA = (current[idA] ?: 0) + 1
            val cB = (current[idB] ?: 0) + 1
            current + (idA to cA) + (idB to cB)
        }
        
        synchronized(pairwiseLock) {
            recentPairs.add(0, idA to idB)
            if (recentPairs.size > 10) recentPairs.removeAt(10)
            recentItemIds.add(0, idA)
            recentItemIds.add(0, idB)
            while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) }
            
            if (chosenId == idA) {
                pairwiseWins[idA] = (pairwiseWins[idA] ?: 0) + 1
                pairwiseLosses[idB] = (pairwiseLosses[idB] ?: 0) + 1
            } else {
                pairwiseWins[idB] = (pairwiseWins[idB] ?: 0) + 1
                pairwiseLosses[idA] = (pairwiseLosses[idA] ?: 0) + 1
            }
        }

        scope.launch {
            val db = database ?: return@launch
            db.pairwiseDao().insertOutcome(PairwiseOutcomeEntity(optionAId = idA, optionBId = idB, chosenId = chosenId, roundNumber = currentPair.roundNumber))
        }
        
        val winner = if (chosenId == idA) currentPair.optionA else currentPair.optionB
        if (_tasteDNA.value.isFineTuningEnabled) {
            val winTraits = PersonalizationTraitMapper.getEffectiveTraitAdjustments(winner)
            var dna = _tasteDNA.value
            winTraits.forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.01, 0.20) }
            updateTasteDNA(dna, false, "Vote")
        }
        _intelligenceStats.update { it.copy(personalizationScore = (it.personalizationScore + 1).coerceAtMost(100), totalComparisons = it.totalComparisons + 1) }
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }

    fun updatePlaybackPosition(pos: Long) { lastPlaybackPositionMs = pos }
    fun setResumingFromBackground(r: Boolean) { isResumingFromBackground = r }

    fun resetDatabase(context: Context) {
        scope.launch {
            _databaseState.value = DatabaseState.INITIALIZING
            database?.close(); database = null
            context.getDatabasePath("aura_intelligence.db").let { if (it.exists()) it.renameTo(context.getDatabasePath("aura_quarantine_${System.currentTimeMillis()}.db")) }
            _mediaItems.value = emptyList()
            initDatabase(context)
        }
    }

    fun setAutoScrollSpeed(speed: AutoScrollSpeed) { _autoScrollSpeed.value = speed }
    fun setGridDensity(density: Float) { _gridDensity.value = density }
    fun setMediaItemsForTesting(items: List<MediaItem>) { 
        _mediaItems.value = items 
        _databaseState.value = DatabaseState.READY
    }
    fun setDatabaseForTesting(db: AuraDatabase) { 
        observersJob?.cancel()
        database = db
        _databaseState.value = DatabaseState.READY
        startDatabaseObservers(db)
    }
    fun setApplicationContextForTesting(context: Context) { applicationContext = context }
    fun setIntelligenceCoreForTesting(core: AuraIntelligenceCore?) { intelligenceCore = core }
    fun setComparisonCountForTesting(counts: Map<String, Int>) { _comparisonCounts.value = counts }
    fun setComparisonCountForTesting(mediaId: String, count: Int) { _comparisonCounts.update { it + (mediaId to count) } }
    suspend fun getLikeCount(mediaId: String): Int = getActualLikeCounts(listOf(mediaId))[mediaId] ?: 0
    fun setPlaybackErrorLogRepositoryForTesting(repo: PlaybackErrorLogRepository?) { 
        playbackErrorLogRepository = repo 
        if (repo != null) {
            flushPendingPlaybackErrors()
        }
    }
    fun resetTestingState() { 
        _mediaItems.value = emptyList()
        _databaseState.value = DatabaseState.NOT_INITIALIZED
        pendingPlaybackErrors.clear()
        initJob?.cancel()
        observersJob?.cancel()
    }

    fun recordCompareSelectionVote(chosenId: String) = recordComparisonVote(chosenId)
    fun skipCompareSelectionPair() = skipComparison()

    fun searchByMultipleImages(items: List<MediaItem>) {
        if (items.isEmpty()) {
            clearSearch()
            return
        }
        val references = items.distinctBy { it.id }
        _activeVisualReferences.value = references
        triggerVisualSearchForReferences(references)
    }

    private suspend fun searchByMultipleImagesInternal(items: List<MediaItem>) {
        if (items.isEmpty()) {
            clearSearch()
            return
        }
        val ctx = applicationContext ?: return
        _isForegroundVisualQueryActive.value = true
        try {
            if (_aiState.value != AIState.READY) {
                _aiState.first { it == AIState.READY || it == AIState.ERROR }
            }
            val vectors = mutableListOf<FloatArray>()
            val uris = mutableListOf<String>()
            items.forEach { item ->
                val uri = item.uriPath.ifEmpty { item.imageUrl }
                if (uri.isNotEmpty()) {
                    uris.add(uri)
                }
                val repo = semanticRepresentationRepository
                val provider = mobileCLIPProvider
                val existing = repo?.getForMedia(item.id)?.firstOrNull { it.type == SemanticRepresentationType.VISUAL }
                if (existing != null) {
                    vectors.add(existing.vector)
                } else if (uri.isNotEmpty()) {
                    val bitmap = com.example.util.MediaThumbnailFetcher.getThumbnail(ctx, uri)
                    if (bitmap != null) {
                        val scaled = if (bitmap.width > 512 || bitmap.height > 512) android.graphics.Bitmap.createScaledBitmap(bitmap, 512, 512, true) else bitmap
                        if (provider != null && provider.isReady()) {
                            val result = provider.generateEmbedding(item.id, SemanticInput.ExplicitBitmap(scaled), "query_image")
                            if (result is EmbeddingResult.Success) {
                                vectors.add(result.representation.vector)
                            }
                        }
                        if (scaled !== bitmap) scaled.recycle()
                    }
                }
            }
            if (vectors.isNotEmpty()) {
                if (vectors.size == 1) {
                    _librarySearchRequest.value = SearchRequest.Visual(vectors[0], uris.firstOrNull())
                } else {
                    _librarySearchRequest.value = SearchRequest.MultiVisual(vectors, uris)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("MediaRepository", "Error in searchByMultipleImagesInternal", e)
        } finally {
            _isForegroundVisualQueryActive.value = false
        }
    }
    private fun extractVolumeFromUri(uriString: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.authority == "media") {
                uri.pathSegments.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun reconcileDeletions(discoveryResult: DiscoveryResult): Boolean {
        val db = database ?: return false
        
        if (discoveryResult !is DiscoveryResult.Complete) {
            Log.w("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions aborted: Discovery was not complete/authoritative.")
            return false
        }
        
        val result = discoveryResult
        val allDiscoveredIds = result.discoveredIds
        val scannedVolumes = result.scannedVolumes
        val scannedMediaTypes = result.scannedMediaTypes

        val currentItems = db.mediaDao().getAllMediaSync()
        val toDelete = mutableListOf<String>()
        var skippedCount = 0
        
        currentItems.forEach { item ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            
            if (item.id.startsWith("local_")) {
                val itemVolume = extractVolumeFromUri(item.uriPath)
                val itemType = item.mediaType // "PHOTO" or "VIDEO"
                
                val isVolumeVerified = itemVolume != null && (scannedVolumes.contains(itemVolume) || scannedVolumes.contains("external") || scannedVolumes.contains("external_primary"))
                val isTypeVerified = scannedMediaTypes.contains(itemType)

                if (isVolumeVerified && isTypeVerified) {
                    if (item.id !in allDiscoveredIds) {
                        toDelete.add(item.id)
                    }
                } else {
                    skippedCount++
                }
            }
        }
        
        if (toDelete.isNotEmpty()) {
            db.mediaDao().deleteByIds(toDelete)
            toDelete.forEach { deleteSemanticDataForMedia(it) }
            _mediaItems.update { current -> current.filterNot { it.id in toDelete } }
            Log.d("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions complete. Purged ${toDelete.size} items via batch delete.")
        }
        
        Log.d("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions summary: Purged ${toDelete.size} items. Preserved $skippedCount items outside verified scope. Total DB items: ${db.mediaDao().getCount()}")
        return true
    }
    fun cancelScan() { /* Logic */ }

    fun recordCleanupSignal(mediaId: String, category: String, score: Float, isDelete: Boolean, reason: String? = null) { /* Logic */ }
    fun reportDiscoverProvenance(prov: Map<String, DecisionProvenance>) { _latestDiscoverProvenance.value = prov }
    fun reportLibraryProvenance(prov: Map<String, DecisionProvenance>) { _latestLibraryProvenance.value = prov }
    fun enqueueVisualLikeContext(mediaId: String, uri: String, playbackPositionMs: Long, durationMs: Long) { /* Logic */ }

    fun logClipInteraction(summary: ClipInteractionEntity) {
        scope.launch { database?.clipInteractionDao()?.insertOrUpdate(summary) }
    }

    fun forceTimeoutState() { _databaseState.value = DatabaseState.TIMEOUT }

    fun close() {
        initJob?.cancel()
        observersJob?.cancel()
        scope.cancel()
    }

    private inner class ProductionLexicalRetriever : LexicalCandidateRetriever { 
        override suspend fun retrieveKeywordCandidates(query: String, topK: Int): List<RankedChannelItem> { 
            val q = query.trim().lowercase()
            return _mediaItems.value.filter { item ->
                item.title.lowercase().contains(q)
            }.take(topK).mapIndexed { idx, item ->
                RankedChannelItem(item.id, 100f, idx + 1)
            }
        } 
    }
}
