package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("game_state_snapshot")
data class GameStateSnapshot(
    val videoList: List<VideoDto>,
    val currentVideoIndex: Int,
    val playbackPosition: Long,
    val isPlaying: Boolean,
    val playerAddresses: List<String>,
    val gameMasterAddress: String,
    val timestamp: Long
) : GameMessage
