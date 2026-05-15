package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AdvancedCommandType {
    TURN_OFF_SCREEN,
    TURN_ON_SCREEN,
    DEACTIVATE_TORCH,
    ACTIVATE_TORCH,
    LIGHTS_ON,
    LIGHTS_OFF
}

@Serializable
@SerialName("advanced_command")
data class AdvancedCommand(
    val type: AdvancedCommandType
) : GameMessage
