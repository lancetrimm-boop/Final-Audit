package com.example.data.semantic

import com.example.data.MediaItem
import java.io.File
import java.util.Calendar
import java.util.Date

/**
 * Deterministic builder for rich semantic text representations of MediaItems.
 * 
 * Synthesizes structured documents from existing metadata to improve MiniLM
 * embedding quality by providing explicit context labels and normalized categories.
 */
object SemanticDocumentBuilder {

    /**
     * Authoritative version of the document synthesis logic.
     * Incrementing this will force re-indexing of all items to capture new semantic signals.
     */
    const val DOCUMENT_VERSION = 1

    /**
     * Builds a structured textual document for the given MediaItem.
     */
    fun buildDocument(item: MediaItem): String {
        val sb = StringBuilder()

        // 1. Title (Authoritative semantic anchor)
        val cleanTitle = item.title.trim()
        if (cleanTitle.isNotBlank()) {
            sb.append("Title: $cleanTitle\n")
        }

        // 2. Folder Context (Geospatial/Organizational signal)
        val folder = extractParentFolder(item.uriPath)
        if (folder.isNotBlank()) {
            sb.append("Folder: $folder\n")
        }

        // 3. Creator/Artist
        val creator = item.creatorName?.trim() ?: ""
        if (creator.isNotBlank()) {
            sb.append("Creator: $creator\n")
        }

        // 4. Genre & Mood Tags
        val tags = mutableListOf<String>()
        if (item.genre.isNotBlank() && item.genre != "Unknown") {
            tags.add(item.genre)
        }
        tags.addAll(item.moodTags.filter { it.isNotBlank() })
        
        if (tags.isNotEmpty()) {
            sb.append("Tags: ${tags.joinToString(", ")}\n")
        }

        // 5. Format & Category
        val format = normalizeFormat(item.mediaType, item.containerFormat)
        if (format.isNotBlank()) {
            sb.append("Format: $format\n")
        }

        // 6. Duration Bucketing (for Videos/Audio)
        if (item.mediaType == "VIDEO" || item.durationMs > 0) {
            val bucket = bucketDuration(item.durationMs)
            sb.append("Duration: $bucket\n")
        }

        // 7. Temporal context (Year)
        val year = if (item.year > 0) {
            item.year
        } else {
            extractYearFromTimestamp(item.dateAdded)
        }
        
        if (year != null && year > 1900) {
            sb.append("Year: $year\n")
        }

        // Fallback: If document is empty, use display name or raw path
        if (sb.isEmpty()) {
            val fallback = cleanTitle.ifBlank { 
                item.uriPath.substringAfterLast('/').ifBlank { "Untitled Media" }
            }
            return "Title: $fallback"
        }

        return sb.toString().trim()
    }

    /**
     * Extracts the immediate parent folder name from a file path.
     */
    fun extractParentFolder(path: String?): String {
        if (path == null || path.isBlank()) return ""
        return try {
            val file = File(path)
            val parent = file.parentFile
            if (parent != null && parent.name.isNotBlank()) {
                parent.name
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Buckets duration into human-readable semantic categories.
     */
    fun bucketDuration(durationMs: Long): String {
        return when {
            durationMs <= 0 -> "Unknown duration"
            durationMs < 60_000 -> "Short clip"
            durationMs < 300_000 -> "Standard video"
            else -> "Extended footage"
        }
    }

    /**
     * Normalizes media type and format into a semantic category.
     */
    private fun normalizeFormat(mediaType: String, containerFormat: String): String {
        val base = mediaType.lowercase()
        val extension = containerFormat.lowercase().removePrefix(".")
        return if (extension.isNotBlank()) {
            "$base ($extension)"
        } else {
            base
        }
    }

    /**
     * Extracts the calendar year from a Unix timestamp (ms).
     */
    private fun extractYearFromTimestamp(timestampMs: Long): Int? {
        if (timestampMs <= 0) return null
        return try {
            val cal = Calendar.getInstance()
            cal.time = Date(timestampMs)
            cal.get(Calendar.YEAR)
        } catch (e: Exception) {
            null
        }
    }
}
