package com.example.playback

import androidx.media3.common.PlaybackException

/**
 * Categories of playback errors for classification and handling.
 */
enum class PlaybackErrorCategory {
    NETWORK,
    IO,
    DECODER,
    DRM,
    PARSING,
    UNSPECIFIED
}

/**
 * Recommended actions for error recovery.
 */
enum class PlaybackRecoveryAction {
    RETRY,
    CONVERT,
    SKIP,
    DISMISS
}

/**
 * Complete classification of a playback failure.
 */
data class PlaybackErrorClassification(
    val category: PlaybackErrorCategory,
    val isRecoverable: Boolean,
    val recommendedAction: PlaybackRecoveryAction,
    val message: String,
    val technicalDetails: String
)

/**
 * Manager for classifying and determining recovery strategies for playback errors.
 */
object PlaybackErrorClassifier {

    /**
     * Classifies a Media3 PlaybackException into actionable categories.
     */
    fun classify(error: PlaybackException): PlaybackErrorClassification {
        return when (error.errorCode) {
            // NETWORK ERRORS
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.NETWORK,
                    isRecoverable = true,
                    recommendedAction = PlaybackRecoveryAction.RETRY,
                    message = "Network connection failed. Please check your internet and try again.",
                    technicalDetails = "Network error: ${error.errorCodeName}"
                )
            }

            // IO / FILE ERRORS
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.IO,
                    isRecoverable = false,
                    recommendedAction = PlaybackRecoveryAction.SKIP,
                    message = "The media file could not be found or read.",
                    technicalDetails = "IO error: ${error.errorCodeName}"
                )
            }

            // DECODER ERRORS (Often fixable by conversion)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.DECODER,
                    isRecoverable = true,
                    recommendedAction = PlaybackRecoveryAction.CONVERT,
                    message = "This device is having trouble playing this video format. Conversion might fix it.",
                    technicalDetails = "Decoder error: ${error.errorCodeName}"
                )
            }

            // PARSING ERRORS
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.PARSING,
                    isRecoverable = true,
                    recommendedAction = PlaybackRecoveryAction.CONVERT,
                    message = "The media file format is unsupported or malformed. Conversion might fix it.",
                    technicalDetails = "Parsing error: ${error.errorCodeName}"
                )
            }

            // DRM ERRORS
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.DRM,
                    isRecoverable = false,
                    recommendedAction = PlaybackRecoveryAction.SKIP,
                    message = "Protected content could not be authorized.",
                    technicalDetails = "DRM error: ${error.errorCodeName}"
                )
            }

            // SPECIAL CASE: Behind live window is usually a non-terminal glitch
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.UNSPECIFIED,
                    isRecoverable = true,
                    recommendedAction = PlaybackRecoveryAction.RETRY,
                    message = "Playback fell behind the live stream window.",
                    technicalDetails = "Behind live window"
                )
            }

            else -> {
                PlaybackErrorClassification(
                    category = PlaybackErrorCategory.UNSPECIFIED,
                    isRecoverable = true,
                    recommendedAction = PlaybackRecoveryAction.RETRY,
                    message = "An unexpected playback error occurred.",
                    technicalDetails = "Unspecified error: ${error.errorCodeName} (${error.errorCode})"
                )
            }
        }
    }
}
