package com.example.ui.models

import androidx.compose.runtime.Immutable
import com.example.data.MediaItem

@Immutable
data class LibraryItemUi(
    val id: String,
    val title: String,
    val mediaType: String,
    val imageUrl: String,
    val uriPath: String,
    val duration: String,
    val selectionReason: String? = null
)

fun MediaItem.toLibraryItemUi(): LibraryItemUi {
    return LibraryItemUi(
        id = id,
        title = title,
        mediaType = mediaType,
        imageUrl = imageUrl,
        uriPath = uriPath,
        duration = duration,
        selectionReason = selectionReason
    )
}

