package com.example.data.semantic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReciprocalRankFusionMetadataTest {

    @Test
    fun `test fuse propagates authoritative metadata flag`() {
        val keywordItem = RankedChannelItem(
            mediaId = "m1",
            rawScore = 100.0f,
            rank = 1,
            metadata = mapOf("is_authoritative" to "true")
        )
        
        val results = ReciprocalRankFusion.fuse(
            mapOf(SearchChannel.KEYWORD to listOf(keywordItem))
        )

        assertTrue("Should have authoritative flag", results[0].isAuthoritativeLexical)
    }

    @Test
    fun `test fuse does not set flag for normal items`() {
        val keywordItem = RankedChannelItem(
            mediaId = "m2",
            rawScore = 60.0f,
            rank = 1,
            metadata = emptyMap()
        )
        
        val results = ReciprocalRankFusion.fuse(
            mapOf(SearchChannel.KEYWORD to listOf(keywordItem))
        )

        assertFalse("Should not have authoritative flag", results[0].isAuthoritativeLexical)
    }
}
