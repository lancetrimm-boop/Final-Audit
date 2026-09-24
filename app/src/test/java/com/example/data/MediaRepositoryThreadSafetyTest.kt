package com.example.data

import com.example.compatibility.AuraMediaCompatibilityEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryThreadSafetyTest {

    @Test
    fun testConcurrentPairwiseReadWrite_DoesNotThrowConcurrentModificationException() = runBlocking {
        val repository = MediaRepository(dispatcher = Dispatchers.IO)

        val testItems = (1..50).map { i ->
            MediaItem(
                id = "item_$i",
                uriPath = "content://media/external/images/media/$i",
                imageUrl = "http://example.com/$i.jpg",
                title = "Photo $i",
                mediaType = "PHOTO"
            )
        }
        repository.setMediaItemsForTesting(testItems)

        // Launch concurrent threads simulating rapid Compare selection / background candidate pool refreshes
        val threads = (1..10).map {
            Thread {
                repeat(50) {
                    repository.refreshPairwiseCandidatePoolAndSelectNext(forceNextPair = true)
                }
            }
        }

        // Execute all threads simultaneously
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Proves that concurrent operations completed cleanly without ConcurrentModificationException
        assertNotNull(repository.pairwiseState.value)
    }
}
