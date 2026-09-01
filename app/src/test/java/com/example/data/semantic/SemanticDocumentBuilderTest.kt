package com.example.data.semantic

import com.example.data.CompatibilityStatus
import com.example.data.ConversionStatus
import com.example.data.EnrichmentStatus
import com.example.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDocumentBuilderTest {

    @Test
    fun testFolderExtraction_HandlesVariousPaths() {
        assertEquals("GoPro", SemanticDocumentBuilder.extractParentFolder("/storage/emulated/0/DCIM/GoPro/hero.mp4"))
        assertEquals("Vacation", SemanticDocumentBuilder.extractParentFolder("C:\\Users\\Photos\\Vacation\\beach.jpg"))
        assertEquals("", SemanticDocumentBuilder.extractParentFolder("/file.mp4"))
        assertEquals("", SemanticDocumentBuilder.extractParentFolder(""))
        assertEquals("", SemanticDocumentBuilder.extractParentFolder(null))
    }

    @Test
    fun testDurationBuckets() {
        assertEquals("Short clip", SemanticDocumentBuilder.bucketDuration(30_000))
        assertEquals("Standard video", SemanticDocumentBuilder.bucketDuration(120_000))
        assertEquals("Extended footage", SemanticDocumentBuilder.bucketDuration(600_000))
        assertEquals("Unknown duration", SemanticDocumentBuilder.bucketDuration(0))
        assertEquals("Unknown duration", SemanticDocumentBuilder.bucketDuration(-100))
    }

    @Test
    fun testBuildDocument_CompleteMetadata() {
        val item = createTestMediaItem(
            title = "Sunset Over Lake",
            uriPath = "/DCIM/Nature/sunset.mp4",
            genre = "Nature",
            moodTags = listOf("peaceful", "orange"),
            mediaType = "VIDEO",
            durationMs = 45_000,
            year = 2025,
            containerFormat = "mp4"
        )

        val doc = SemanticDocumentBuilder.buildDocument(item)
        
        assertTrue(doc.contains("Title: Sunset Over Lake"))
        assertTrue(doc.contains("Folder: Nature"))
        assertTrue(doc.contains("Tags: Nature, peaceful, orange"))
        assertTrue(doc.contains("Format: video (mp4)"))
        assertTrue(doc.contains("Duration: Short clip"))
        assertTrue(doc.contains("Year: 2025"))
    }

    @Test
    fun testBuildDocument_MinimalMetadata_FallbacksToTitle() {
        val item = createTestMediaItem(
            title = "  ",
            uriPath = "/DCIM/Camera/IMG_123.jpg",
            mediaType = "PHOTO"
        )

        val doc = SemanticDocumentBuilder.buildDocument(item)
        
        // Should use filename from path as fallback if title is blank
        assertTrue(doc.contains("Title: IMG_123.jpg"))
        assertTrue(doc.contains("Folder: Camera"))
        assertTrue(doc.contains("Format: photo"))
    }

    @Test
    fun testBuildDocument_NullAndEmptySafety() {
        val item = createTestMediaItem(
            title = "",
            creatorName = null,
            genre = "",
            moodTags = emptyList(),
            year = 0,
            dateAdded = 0
        )

        val doc = SemanticDocumentBuilder.buildDocument(item)
        
        // Verify no "null" or empty labels are emitted
        org.junit.Assert.assertFalse(doc.contains("null"))
        org.junit.Assert.assertFalse(doc.contains("Creator:"))
        org.junit.Assert.assertFalse(doc.contains("Tags:"))
        org.junit.Assert.assertFalse(doc.contains("Year:"))
    }

    @Test
    fun testDeterminism() {
        val item = createTestMediaItem(title = "Same Item")
        val doc1 = SemanticDocumentBuilder.buildDocument(item)
        val doc2 = SemanticDocumentBuilder.buildDocument(item)
        assertEquals(doc1, doc2)
    }

    private fun createTestMediaItem(
        title: String = "Test",
        uriPath: String = "/test.mp4",
        mediaType: String = "VIDEO",
        genre: String = "",
        moodTags: List<String> = emptyList(),
        durationMs: Long = 0,
        year: Int = 0,
        containerFormat: String = "",
        dateAdded: Long = 0,
        creatorName: String? = null
    ): MediaItem {
        return MediaItem(
            id = "test-id",
            title = title,
            mediaType = mediaType,
            year = year,
            duration = "",
            genre = genre,
            imageUrl = "",
            gradientColors = emptyList(),
            rating = 0f,
            isFavorite = false,
            progress = 0f,
            progressText = "",
            category = "",
            aiSummary = "",
            moodTags = moodTags,
            uriPath = uriPath,
            itemCount = null,
            sizeBytes = 0,
            dateAdded = dateAdded,
            durationMs = durationMs,
            width = 0,
            height = 0,
            lastViewedTimestamp = null,
            viewCount = 0,
            exposureCount = 0,
            lastExposedTimestamp = null,
            dateModified = 0,
            contentHash = null,
            parentContentId = null,
            eloRating = 1500.0,
            isDeleted = false,
            compatibilityStatus = CompatibilityStatus.PLAYABLE,
            containerFormat = containerFormat,
            videoCodec = "",
            audioCodec = "",
            compatibilityReason = "",
            conversionStatus = ConversionStatus.NONE,
            convertedUri = null,
            lastCompatibilityCheckTimestamp = null,
            selectionReason = null,
            creatorId = null,
            creatorName = creatorName,
            sourcePlatform = null,
            replacedByMediaId = null,
            enrichmentStatus = EnrichmentStatus.PENDING,
            lastEnrichmentAttemptTimestamp = null,
            enrichmentFailureCount = 0
        )
    }
}
