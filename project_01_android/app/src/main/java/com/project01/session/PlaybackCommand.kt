package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackCommandType {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS
}

@Serializable
@SerialName("playback_command")
data class PlaybackCommand(
    val type: PlaybackCommandType,
    val videoIndex: Int = -1,
    val playbackPosition: Long = -1,
    val playWhenReady: Boolean = true
) : GameMessage
