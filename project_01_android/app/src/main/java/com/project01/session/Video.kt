package com.project01.session

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Video(
    val uri: Uri,
    val title: String
) : Parcelable

fun Video.toDto() = VideoDto(uri.toString(), title)

fun VideoDto.toVideo() = Video(Uri.parse(uriString), title)
