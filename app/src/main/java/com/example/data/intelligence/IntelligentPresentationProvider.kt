package com.example.data.intelligence

import com.example.data.MediaItem

/**
 * Data model for an intelligently grouped UI section.
 */
data class IntelligentSection(
    val title: String,
    val subtitle: String,
    val items: List<MediaItem>,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Transforms raw intelligence results into structured presentation sections.
 */
object IntelligentPresentationProvider {

    /**
     * Groups a set of ranked favorites into intelligent presentation sections.
     */
    fun transformFavorites(
        candidates: List<IntelligenceCandidate>,
        styleProfile: SignatureStyleProfile
    ): List<IntelligentSection> {
        val sections = mutableListOf<IntelligentSection>()
        val usedIds = mutableSetOf<String>()

        // 1. Top Picks (Absolute highest rank from Core)
        val topPicks = candidates.take(5)
        if (topPicks.isNotEmpty()) {
            sections.add(IntelligentSection(
                title = "Your Top Picks",
                subtitle = "Items that match your current vibe perfectly.",
                items = topPicks.map { it.item }
            ))
            usedIds.addAll(topPicks.map { it.item.id })
        }

        // 2. Overlooked (High preference but not viewed recently)
        val now = System.currentTimeMillis()
        val weekMs = 7 * 24 * 60 * 60 * 1000L
        val overlooked = candidates
            .filter { it.item.id !in usedIds }
            .filter { it.item.lastViewedTimestamp == null || (now - it.item.lastViewedTimestamp!!) > weekMs }
            .take(4)
        
        if (overlooked.isNotEmpty()) {
            sections.add(IntelligentSection(
                title = "Overlooked Gems",
                subtitle = "Favorites you haven't revisited in a while.",
                items = overlooked.map { it.item }
            ))
            usedIds.addAll(overlooked.map { it.item.id })
        }

        // 3. By Style (Grouped by the user's signature styles)
        styleProfile.activeStyles.take(2).forEach { style ->
            val styleItems = candidates
                .filter { it.item.id !in usedIds }
                .map { it to SignatureStyleProvider.calculateMediaAffinity(it.item, style.anchor) }
                .filter { it.second > 0.75 }
                .sortedByDescending { it.second }
                .take(6)
            
            if (styleItems.isNotEmpty()) {
                sections.add(IntelligentSection(
                    title = "In ${style.anchor.displayName} Style",
                    subtitle = style.anchor.description,
                    items = styleItems.map { it.first.item }
                ))
                usedIds.addAll(styleItems.map { it.first.item.id })
            }
        }

        // 4. Everything Else (Remaining favorites)
        val remaining = candidates.filter { it.item.id !in usedIds }
        if (remaining.isNotEmpty()) {
            sections.add(IntelligentSection(
                title = "More Favorites",
                subtitle = "The rest of your saved collection.",
                items = remaining.map { it.item }
            ))
        }

        return sections
    }

    /**
     * Attaches style-alignment context to a candidate's provenance description.
     */
    fun annotateCandidate(
        candidate: IntelligenceCandidate,
        styleProfile: SignatureStyleProfile
    ): IntelligenceCandidate {
        val topStyle = styleProfile.activeStyles.map { it to SignatureStyleProvider.calculateMediaAffinity(candidate.item, it.anchor) }
            .filter { it.second > 0.8 }
            .maxByOrNull { it.second }
        
        return if (topStyle != null) {
            candidate.copy(
                provenance = "Matches your ${topStyle.first.anchor.displayName} style. " + candidate.provenance
            )
        } else {
            candidate
        }
    }
}
