package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("playback_state")
data class PlaybackState(
    val videoIndex: Int,
    val playbackPosition: Long,
    val playWhenReady: Boolean
) : GameMessage
