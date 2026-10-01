package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Official Registry for Aura Channels.
 * Maintains the fixed 5-channel lineup and dynamic user search-seeded channels.
 */
object ChannelRegistry {

    private val fixedChannels = listOf(
        Channel(
            id = "ME_TV",
            title = "Me TV",
            query = "cinematic vibrant atmospheric",
            channelKind = ChannelKind.FIXED,
            strategyId = "ME_TV",
            order = 0,
            isDefault = true,
            isProGated = true
        ),
        Channel(
            id = "FAVORITES",
            title = "Favorites Stream",
            query = "favorites starred liked",
            channelKind = ChannelKind.FIXED,
            strategyId = "FAVORITES",
            order = 1,
            isDefault = false,
            isProGated = true
        ),
        Channel(
            id = "REDISCOVER",
            title = "Rediscover Vault",
            query = "rediscover forgotten library discovery",
            channelKind = ChannelKind.FIXED,
            strategyId = "REDISCOVER",
            order = 2,
            isDefault = false,
            isProGated = true
        ),
        Channel(
            id = "CONTINUE",
            title = "Series Continue",
            query = "continue series episode",
            channelKind = ChannelKind.FIXED,
            strategyId = "CONTINUE",
            order = 3,
            isDefault = false,
            isProGated = true
        ),
        Channel(
            id = "MOOD",
            title = "Mood & Aesthetic",
            query = "vibrant serene aesthetic",
            channelKind = ChannelKind.FIXED,
            strategyId = "MOOD",
            order = 4,
            isDefault = false,
            isProGated = true
        )
    )

    private val customChannelsState = MutableStateFlow<List<Channel>>(emptyList())

    /**
     * Returns all 5 fixed channels in canonical order.
     */
    fun allFixedChannels(): List<Channel> = fixedChannels

    /**
     * Returns all channels (fixed + user search-seeded channels).
     */
    fun allChannels(): List<Channel> = fixedChannels + customChannelsState.value

    /**
     * Adds a search-seeded channel.
     */
    fun addSearchSeededChannel(channel: Channel) {
        val current = customChannelsState.value
        if (current.none { it.id == channel.id }) {
            customChannelsState.value = current + channel
        }
    }

    /**
     * Removes a search-seeded channel by ID.
     */
    fun removeSearchSeededChannel(channelId: String) {
        val current = customChannelsState.value
        customChannelsState.value = current.filterNot { it.id == channelId }
    }

    /**
     * Sets custom channels list (e.g. loaded from persistence).
     */
    fun setCustomChannels(channels: List<Channel>) {
        customChannelsState.value = channels
    }

    /**
     * Clears custom channels for testing.
     */
    fun clearCustomChannelsForTesting() {
        customChannelsState.value = emptyList()
    }

    /**
     * Retrieves a channel by ID.
     */
    fun get(channelId: String): Channel? {
        return fixedChannels.find { it.id == channelId } ?: customChannelsState.value.find { it.id == channelId }
    }

    /**
     * Returns the default channel (Me TV).
     */
    fun defaultChannel(): Channel = fixedChannels.first { it.isDefault }
}
