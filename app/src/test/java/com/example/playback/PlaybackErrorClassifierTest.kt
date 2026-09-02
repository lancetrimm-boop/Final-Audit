package com.example.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorClassifierTest {

    @Test
    fun testNetworkErrorClassification() {
        val error = PlaybackException(
            "Timeout",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.NETWORK, classification.category)
        assertTrue(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.RETRY, classification.recommendedAction)
        assertTrue(classification.technicalDetails.contains("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
    }

    @Test
    fun testIOErrorClassification() {
        val error = PlaybackException(
            "File not found",
            null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.IO, classification.category)
        assertFalse(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.SKIP, classification.recommendedAction)
    }

    @Test
    fun testDecoderErrorClassification() {
        val error = PlaybackException(
            "Decoder init failed",
            null,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.DECODER, classification.category)
        assertTrue(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.CONVERT, classification.recommendedAction)
        assertTrue(classification.message.contains("Conversion might fix it"))
    }

    @Test
    fun testParsingErrorClassification() {
        val error = PlaybackException(
            "Malformed manifest",
            null,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.PARSING, classification.category)
        assertTrue(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.CONVERT, classification.recommendedAction)
    }

    @Test
    fun testDrmErrorClassification() {
        val error = PlaybackException(
            "DRM failed",
            null,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.DRM, classification.category)
        assertFalse(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.SKIP, classification.recommendedAction)
    }

    @Test
    fun testBehindLiveWindowClassification() {
        val error = PlaybackException(
            "Behind window",
            null,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.UNSPECIFIED, classification.category)
        assertTrue(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.RETRY, classification.recommendedAction)
    }

    @Test
    fun testUnspecifiedErrorFallback() {
        val error = PlaybackException(
            "Unknown",
            null,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        val classification = PlaybackErrorClassifier.classify(error)

        assertEquals(PlaybackErrorCategory.UNSPECIFIED, classification.category)
        assertTrue(classification.isRecoverable)
        assertEquals(PlaybackRecoveryAction.RETRY, classification.recommendedAction)
    }
}
