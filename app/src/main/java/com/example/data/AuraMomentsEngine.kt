package com.example.data

import com.example.data.intelligence.IntelligenceRequest
import com.example.data.intelligence.IntelligenceMode
import com.example.data.semantic.SemanticRepresentationType
import android.util.Log

enum class MomentsMode(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconName: String
) {
    FOR_YOU(
        id = "for_you",
        title = "For You",
        subtitle = "Personalized selection tuned to your taste",
        iconName = "sparkles"
    ),
    MEMORIES(
        id = "memories",
        title = "Memories",
        subtitle = "Recent and meaningful moments from your library",
        iconName = "history"
    ),
    SURPRISE_ME(
        id = "surprise_me",
        title = "Surprise Me",
        subtitle = "Hidden gems and unexpected discoveries",
        iconName = "shuffle"
    ),
    FAVORITES(
        id = "favorites",
        title = "Favorites",
        subtitle = "Your top-rated and favorited media",
        iconName = "favorite"
    ),
    AESTHETIC(
        id = "aesthetic",
        title = "Aesthetic",
        subtitle = "Visually cohesive sequences and mood harmonies",
        iconName = "palette"
    )
}

object AuraMomentsEngine {

    /**
     * Generates an intelligent, visually-sequenced slideshow playlist.
     * Uses AuraIntelligenceCore for selection and SlideshowSequencer for pathfinding.
     */
    suspend fun generateIntelligentSlideshow(
        repository: MediaRepository,
        mode: MomentsMode,
        limit: Int = 25
    ): List<MediaItem> {
        val totalStart = System.currentTimeMillis()
        val core = repository.intelligenceCore ?: return generateSlideshow(repository.mediaItems.value, limit)
        
        Log.d("AuraMoments", "Generating intelligent slideshow for mode: ${mode.id}")
        
        // 1. Candidate Selection via Core
        val selectionStart = System.currentTimeMillis()
        val request = IntelligenceRequest(
            mode = IntelligenceMode.SORT,
            sortOption = when(mode) {
                MomentsMode.SURPRISE_ME -> "DISCOVER"
                else -> "PERSONALIZED"
            },
            limit = limit * 3 // Over-sample for sequencing variety
        )
        
        val response = core.processRequest(request)
        val selectionMs = System.currentTimeMillis() - selectionStart
        if (!response.isSuccess) return generateSlideshow(repository.mediaItems.value, limit)

        var candidates = response.candidates
        
        // 2. Mode-Specific Filtering
        if (mode == MomentsMode.FAVORITES) {
            candidates = candidates.filter { it.item.isFavorite }
        }

        if (candidates.isEmpty()) return emptyList()

        // 3. Embedding Retrieval for Sequencing
        val embeddingStart = System.currentTimeMillis()
        val mediaIds = candidates.map { it.item.id }
        val embeddings = repository.semanticRepresentationRepository?.let { repo ->
            val clipProvider = repository.mobileCLIPProvider
            if (clipProvider != null) {
                repo.getCompatibleRepresentations(SemanticRepresentationType.VISUAL, clipProvider.descriptor)
                    .filter { it.mediaId in mediaIds }
                    .associate { it.mediaId to it.vector }
            } else emptyMap()
        } ?: emptyMap()
        val embeddingMs = System.currentTimeMillis() - embeddingStart

        // 4. Intelligent Sequencing (Transition Optimization)
        val sequencingStart = System.currentTimeMillis()
        val sequencedCandidates = SlideshowSequencer.sequence(
            candidates = candidates,
            embeddings = embeddings,
            config = SlideshowSequencer.SequencingConfig(targetLength = limit)
        )
        val sequencingMs = System.currentTimeMillis() - sequencingStart

        val totalMs = System.currentTimeMillis() - totalStart
        
        Log.i("AuraMomentsDiag", "--- Intelligent Slideshow Preparation Report ---")
        Log.i("AuraMomentsDiag", "Mode: ${mode.id}")
        Log.i("AuraMomentsDiag", "Stage 1: Selection: ${selectionMs}ms")
        Log.i("AuraMomentsDiag", "Stage 2: Embedding Retrieval: ${embeddingMs}ms")
        Log.i("AuraMomentsDiag", "Stage 3: Sequence Construction: ${sequencingMs}ms")
        Log.i("AuraMomentsDiag", "Total Preparation Time: ${totalMs}ms")
        Log.i("AuraMomentsDiag", "Candidates Evaluated: ${candidates.size}")
        Log.i("AuraMomentsDiag", "------------------------------------------------")

        return sequencedCandidates.map { it.item }
    }

    /**
     * Legacy heuristic generator preserved as a functional fallback.
     */
    fun generateSlideshow(
        allMedia: List<MediaItem>,
        limit: Int = 20
    ): List<MediaItem> {
        val repo = MediaRepository.instance
        // AURA PHASE 4: Filter to PHOTOS ONLY for Aura Moments (Legacy)
        val photosOnly = allMedia.filter { 
            (it.mediaType.equals("PHOTO", ignoreCase = true) || it.mediaType.equals("Image", ignoreCase = true)) &&
            repo.isItemVisibleInLibrary(it)
        }
        
        if (photosOnly.isEmpty()) return emptyList()

        // Simplified Legacy Fallback (Update 9 Cleanup)
        val result = photosOnly.sortedByDescending { it.rating }.take(limit)
        return sequenceVisualStory(result)
    }

    /**
     * Applies deterministic visual story sequencing to prevent consecutive repetitive items
     * and produce a cohesive visual flow:
     * Structure: Opening -> Related -> Visual Variation -> High-Confidence -> Closing
     */
    fun sequenceVisualStory(items: List<MediaItem>): List<MediaItem> {
        if (items.size <= 1) return items.distinctBy { it.id }

        val uniqueItems = items.distinctBy { it.id }.toMutableList()
        if (uniqueItems.size <= 1) return uniqueItems

        val sequenced = mutableListOf<MediaItem>()

        // 1. Opening establishing item
        val opening = uniqueItems.removeAt(0)
        sequenced.add(opening)

        var lastItem = opening

        // 2. Interleave items to avoid consecutive identical category / genre
        while (uniqueItems.isNotEmpty()) {
            val candidateIndex = uniqueItems.indexOfFirst { cand ->
                val categoryDiffers = cand.category.isBlank() || !cand.category.equals(lastItem.category, ignoreCase = true)
                val genreDiffers = cand.genre.isBlank() || !cand.genre.equals(lastItem.genre, ignoreCase = true)
                categoryDiffers || genreDiffers
            }

            val nextIndex = if (candidateIndex >= 0) candidateIndex else 0
            val nextItem = uniqueItems.removeAt(nextIndex)
            sequenced.add(nextItem)
            lastItem = nextItem
        }

        return sequenced
    }
}
