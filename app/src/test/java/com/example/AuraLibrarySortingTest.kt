package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.data.db.AuraDatabase
import com.example.data.intelligence.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuraLibrarySortingTest {

    private lateinit var repository: MediaRepository
    private lateinit var database: AuraDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MediaRepository()
        repository.setApplicationContextForTesting(context)
        repository.setDatabaseForTesting(database)
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
    }

    private fun createItem(
        id: String, 
        title: String, 
        dateAdded: Long = System.currentTimeMillis(),
        lastViewed: Long? = null,
        viewCount: Int = 0,
        rating: Float = 0f,
        durationMs: Long = 0L,
        exposureCount: Int = 0
    ) = MediaItem(
        id = id,
        title = title,
        mediaType = "VIDEO",
        uriPath = "test_$id.mp4",
        imageUrl = "test_$id.jpg",
        dateAdded = dateAdded,
        lastViewedTimestamp = lastViewed,
        viewCount = viewCount,
        rating = rating,
        durationMs = durationMs,
        exposureCount = exposureCount,
        compatibilityStatus = CompatibilityStatus.PLAYABLE
    )

    @Test
    fun testStandardSort_Title() = runBlocking {
        val items = listOf(
            createItem("1", "Banana"),
            createItem("2", "Apple"),
            createItem("3", "Cherry")
        )

        val sortedAsc = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.TITLE_ASC, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Apple", sortedAsc[0].title)
        assertEquals("Banana", sortedAsc[1].title)
        assertEquals("Cherry", sortedAsc[2].title)

        val sortedDesc = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.TITLE_DESC, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Cherry", sortedDesc[0].title)
        assertEquals("Banana", sortedDesc[1].title)
        assertEquals("Apple", sortedDesc[2].title)
    }

    @Test
    fun testStandardSort_Duration() = runBlocking {
        val items = listOf(
            createItem("1", "Short", durationMs = 1000),
            createItem("2", "Long", durationMs = 5000),
            createItem("3", "Medium", durationMs = 3000)
        )

        val sortedShort = repository.getFilteredAndSortedMedia(
            "ALL", SortCategory.STANDARD, StandardSortOption.SHORTEST_DURATION, IntelligentSortOption.PERSONALIZED, inputItems = items
        )
        assertEquals("Short", sortedShort[0].title)
        assertEquals("Medium", sortedShort[1].title)
        assertEquals("Long", sortedShort[2].title)
    }

    @Test
    fun testLoadNextLibraryPage_ExcludesAndPaginates() = runBlocking {
        val items = (1..50).map { createItem("$it", "Item $it") }
        repository.setMediaItemsForTesting(items)

        val page1 = repository.loadNextLibraryPage(emptySet(), limit = 20)
        assertEquals(20, page1.size)

        val excludeIds = page1.map { it.id }.toSet()
        val page2 = repository.loadNextLibraryPage(excludeIds, limit = 20)
        assertEquals(20, page2.size)
        assertTrue(page2.none { it.id in excludeIds })
    }

    @Test
    fun testQuarantineGate_ExcludesUnplayableAndNoPreviewItems() = runBlocking {
        val playable = createItem("1", "Playable Video").copy(imageUrl = "thumb.jpg", uriPath = "video1.mp4")
        val badMkv = createItem("2", "Bad MKV").copy(compatibilityStatus = CompatibilityStatus.UNSUPPORTED, imageUrl = "thumb2.jpg", uriPath = "bad.mkv")
        val corrupt = createItem("3", "Corrupt File").copy(compatibilityStatus = CompatibilityStatus.CORRUPT, uriPath = "corrupt.mp4")
        
        repository.setMediaItemsForTesting(listOf(playable, badMkv, corrupt))

        val page = repository.loadLibraryPage(offset = 0, limit = 10)
        assertEquals(1, page.size)
        assertEquals("1", page[0].id)

        val quarantined = repository.getQuarantinedItems()
        assertEquals(2, quarantined.size)
        assertTrue(quarantined.any { it.id == "2" })
        assertTrue(quarantined.any { it.id == "3" })

        // Convert the bad MKV
        repository.updateConvertedMedia("2", "bad.mkv", "converted.mp4")

        val pageAfterConvert = repository.loadLibraryPage(offset = 0, limit = 10)
        assertEquals(2, pageAfterConvert.size)
        assertTrue(pageAfterConvert.any { it.id == "2" })
    }

    @Test
    fun testUpdateConvertedMedia_BlankMediaId_PersistsByUriFallback() = runBlocking {
        val badMkvEntity = com.example.data.db.MediaEntity(
            id = "known_mkv_id",
            title = "Unplayable MKV",
            mediaType = "VIDEO",
            uriPath = "unplayable.mkv",
            compatibilityStatus = CompatibilityStatus.UNSUPPORTED.name
        )
        database.mediaDao().insert(badMkvEntity)

        val badMkvItem = badMkvEntity.toMediaItem()
        repository.setMediaItemsForTesting(listOf(badMkvItem))

        // Call updateConvertedMedia with blank mediaId ("")
        repository.updateConvertedMedia(
            mediaId = "",
            sourceUri = "unplayable.mkv",
            outputPath = "converted_mkv.mp4"
        )

        // Verify in-memory state updated
        val page = repository.loadLibraryPage(offset = 0, limit = 10)
        assertEquals(1, page.size)
        assertEquals("known_mkv_id", page[0].id)

        // Verify Room database entity updated via getMediaByUri
        val persistedEntity = database.mediaDao().getMediaByUri("unplayable.mkv")
        assertNotNull(persistedEntity)
        assertEquals("PLAYABLE", persistedEntity?.compatibilityStatus)
        assertEquals("CONVERTED", persistedEntity?.conversionStatus)
        assertEquals("converted_mkv.mp4", persistedEntity?.convertedUri)
    }
}
