package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class ChannelPreviewState(
    val channel: Channel,
    val candidateItem: MediaItem?,
    val fullItems: List<MediaItem>
)

class ChannelViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val programmer = ChannelProgrammer()
    private val sessionManager = ChannelSessionManager(programmer)
    val channelState: StateFlow<ChannelState> = sessionManager.channelState

    val defaultChannels: List<Channel> get() = ChannelRegistry.allFixedChannels()

    val allChannels: List<Channel> get() = ChannelRegistry.allChannels()

    private val _selectedChannel = MutableStateFlow<Channel>(ChannelRegistry.defaultChannel())
    val selectedChannel: StateFlow<Channel> = _selectedChannel.asStateFlow()

    private val _selectedFilterType = MutableStateFlow<String>("VIDEOS")
    val selectedFilterType: StateFlow<String> = _selectedFilterType.asStateFlow()

    private val _channelPreviews = MutableStateFlow<List<ChannelPreviewState>>(emptyList())
    val channelPreviews: StateFlow<List<ChannelPreviewState>> = _channelPreviews.asStateFlow()

    private val _isRefreshing = MutableStateFlow<Boolean>(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var refreshEpoch = 0
    private val sessionExposures = mutableMapOf<String, Int>()

    val slideshowDelaySeconds: StateFlow<Int> = repository.slideshowDelaySeconds

    init {
        // Observe media items changes and refresh channel previews
        viewModelScope.launch {
            repository.mediaItems.collect {
                loadChannelPreviews()
            }
        }
        selectChannel(ChannelRegistry.defaultChannel())
    }

    fun loadChannelPreviews() {
        viewModelScope.launch(Dispatchers.Default) {
            val available = repository.mediaItems.value
            val filter = _selectedFilterType.value
            val context = ChannelProgrammingContext(
                availableMedia = available,
                filterType = filter,
                tasteDNA = repository.tasteDNA.value,
                exposureMap = available.associate { it.id to it.exposureCount },
                refreshEpoch = refreshEpoch,
                sessionExposures = sessionExposures.toMap()
            )

            val usedPreviewIds = mutableSetOf<String>()

            val states = allChannels.map { channel ->
                val programmed = programmer.programChannel(channel, context, limit = 20)
                val candidate = programmed.firstOrNull { it.id !in usedPreviewIds }
                if (candidate != null) {
                    usedPreviewIds.add(candidate.id)
                }

                val adjustedFullItems = if (candidate != null) {
                    listOf(candidate) + programmed.filter { it.id != candidate.id }
                } else emptyList()

                ChannelPreviewState(
                    channel = channel,
                    candidateItem = candidate,
                    fullItems = adjustedFullItems
                )
            }
            _channelPreviews.value = states
        }
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        viewModelScope.launch {
            sessionManager.selectChannel(
                channel = channel,
                repository = repository,
                filterType = _selectedFilterType.value,
                refreshEpoch = refreshEpoch,
                sessionExposures = sessionExposures.toMap()
            )
        }
    }

    fun reloadChannel(
        channel: Channel,
        filterType: String = _selectedFilterType.value,
        onComplete: (List<MediaItem>) -> Unit
    ) {
        viewModelScope.launch {
            refreshEpoch++
            // Record currently presented items in sessionExposures to depress them on reload
            val currentState = channelPreviews.value.find { it.channel.id == channel.id }
            currentState?.candidateItem?.let { currentCandidate ->
                sessionExposures[currentCandidate.id] = (sessionExposures[currentCandidate.id] ?: 0) + 1
            }
            currentState?.fullItems?.forEach { fullItem ->
                sessionExposures[fullItem.id] = (sessionExposures[fullItem.id] ?: 0) + 1
            }

            val available = repository.mediaItems.value
            val db = repository.getDatabase()
            val feedback = db?.programmingFeedbackDao()?.getAllFeedback() ?: emptyList()
            val skipEvents = db?.aiSkipDao()?.observeAllEvents()?.firstOrNull() ?: emptyList()

            val context = ChannelProgrammingContext(
                availableMedia = available,
                filterType = filterType,
                tasteDNA = repository.tasteDNA.value,
                programmingFeedback = feedback,
                exposureMap = available.associate { it.id to it.exposureCount },
                skipEvents = skipEvents,
                refreshEpoch = refreshEpoch,
                sessionExposures = sessionExposures.toMap()
            )
            val items = programmer.programChannel(
                channel = channel,
                context = context,
                limit = 20
            )

            items.forEach { item ->
                sessionExposures[item.id] = (sessionExposures[item.id] ?: 0) + 1
            }

            onComplete(items)
        }
    }

    suspend fun programNextBlock(
        channel: Channel,
        filterType: String,
        excludeIds: Set<String>,
        limit: Int = 10
    ): List<MediaItem> {
        val available = repository.mediaItems.value
        val db = repository.getDatabase()
        val feedback = db?.programmingFeedbackDao()?.getAllFeedback() ?: emptyList()
        val skipEvents = db?.aiSkipDao()?.observeAllEvents()?.firstOrNull() ?: emptyList()

        val context = ChannelProgrammingContext(
            availableMedia = available,
            filterType = filterType,
            tasteDNA = repository.tasteDNA.value,
            programmingFeedback = feedback,
            exposureMap = available.associate { it.id to it.exposureCount },
            skipEvents = skipEvents,
            refreshEpoch = refreshEpoch,
            sessionExposures = sessionExposures.toMap()
        )
        val candidates = programmer.programChannel(
            channel = channel,
            context = context,
            limit = limit + excludeIds.size
        )
        val freshItems = candidates.filter { it.id !in excludeIds }.take(limit)
        freshItems.forEach { item ->
            sessionExposures[item.id] = (sessionExposures[item.id] ?: 0) + 1
        }
        return freshItems
    }

    fun setFilterType(filterType: String) {
        if (_selectedFilterType.value == filterType) return
        _selectedFilterType.value = filterType
        loadChannelPreviews()
        selectChannel(_selectedChannel.value)
    }

    fun setSlideshowDelaySec(sec: Int) {
        repository.slideshowDelaySec = sec
    }

    fun refreshActiveChannel() {
        val current = _selectedChannel.value
        selectChannel(current)
        loadChannelPreviews()
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Record candidate IDs actually presented as active previews in sessionExposures
                _channelPreviews.value.forEach { previewState ->
                    previewState.candidateItem?.let { item ->
                        val current = sessionExposures[item.id] ?: 0
                        sessionExposures[item.id] = current + 1
                    }
                }
                refreshEpoch++

                val available = repository.mediaItems.value
                val filter = _selectedFilterType.value
                val context = ChannelProgrammingContext(
                    availableMedia = available,
                    filterType = filter,
                    tasteDNA = repository.tasteDNA.value,
                    exposureMap = available.associate { it.id to it.exposureCount },
                    refreshEpoch = refreshEpoch,
                    sessionExposures = sessionExposures.toMap()
                )

                val usedPreviewIds = mutableSetOf<String>()

                val states = allChannels.map { channel ->
                    val programmed = programmer.programChannel(channel, context, limit = 20)
                    val candidate = programmed.firstOrNull { it.id !in usedPreviewIds }
                    if (candidate != null) {
                        usedPreviewIds.add(candidate.id)
                    }

                    val adjustedFullItems = if (candidate != null) {
                        listOf(candidate) + programmed.filter { it.id != candidate.id }
                    } else emptyList()

                    ChannelPreviewState(
                        channel = channel,
                        candidateItem = candidate,
                        fullItems = adjustedFullItems
                    )
                }
                _channelPreviews.value = states

                val currentChannel = _selectedChannel.value
                sessionManager.selectChannel(
                    channel = currentChannel,
                    repository = repository,
                    filterType = filter,
                    refreshEpoch = refreshEpoch,
                    sessionExposures = sessionExposures.toMap()
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
