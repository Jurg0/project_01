package com.project01.session

enum class PlaybackCommandType {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS
}

data class PlaybackCommand(
    val type: PlaybackCommandType,
    val videoIndex: Int = -1,
    val playbackPosition: Long = -1,
    val playWhenReady: Boolean = true
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}