package com.project01.session

data class PlaybackState(
    val videoIndex: Int,
    val playbackPosition: Long,
    val playWhenReady: Boolean
) : java.io.Serializable