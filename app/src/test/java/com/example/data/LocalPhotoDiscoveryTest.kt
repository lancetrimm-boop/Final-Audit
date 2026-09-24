package com.example.data

import com.example.data.db.MediaEntity
import org.junit.Assert.*
import org.junit.Test

class LocalPhotoDiscoveryTest {

    @Test
    fun testPhotoAndVideoEntities_DistinctIdsAndMediaTypes() {
        val idNum = 12345L
        
        val videoEntity = MediaEntity(
            id = "local_vid_$idNum",
            title = "Test Video",
            mediaType = "VIDEO",
            uriPath = "content://media/external/video/media/$idNum",
            sizeBytes = 10485760L
        )

        val photoEntity = MediaEntity(
            id = "local_img_$idNum",
            title = "Test Photo",
            mediaType = "PHOTO",
            uriPath = "content://media/external/images/media/$idNum",
            sizeBytes = 2097152L
        )

        assertNotEquals("Photo and Video IDs should be distinct even for same numeric ID", videoEntity.id, photoEntity.id)
        assertEquals("local_vid_12345", videoEntity.id)
        assertEquals("local_img_12345", photoEntity.id)
        assertEquals("VIDEO", videoEntity.mediaType)
        assertEquals("PHOTO", photoEntity.mediaType)
    }

    @Test
    fun testDiscoveryResult_AcceptsBothPhotosAndVideos() {
        val vid = MediaEntity(id = "local_vid_1", title = "V1", mediaType = "VIDEO", uriPath = "content://v/1")
        val img = MediaEntity(id = "local_img_1", title = "P1", mediaType = "PHOTO", uriPath = "content://i/1")

        val entities = listOf(vid, img)
        val ids = setOf("local_vid_1", "local_img_1")
        val result = DiscoveryResult.Complete(entities, ids, setOf("external"), setOf("VIDEO", "PHOTO"))

        assertEquals(2, result.entities.size)
        assertTrue(result.entities.any { it.mediaType == "VIDEO" })
        assertTrue(result.entities.any { it.mediaType == "PHOTO" })
    }
}
