package com.project01.session

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Video(
    val uri: Uri,
    val title: String,
    /**
     * Size in bytes of the game master's original file, or [SIZE_UNKNOWN].
     *
     * Travels with the playlist so a player can tell a complete cached video from a truncated
     * one. Without it the cache trusts `exists()`, and a file left half-written by an older
     * build (or a killed process) is accepted forever and never re-fetched.
     */
    val sizeBytes: Long = SIZE_UNKNOWN,
) : Parcelable {
    companion object {
        const val SIZE_UNKNOWN = -1L
    }
}

fun Video.toDto() = VideoDto(uri.toString(), title, sizeBytes)

fun VideoDto.toVideo() = Video(Uri.parse(uriString), title, sizeBytes)
