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
}
