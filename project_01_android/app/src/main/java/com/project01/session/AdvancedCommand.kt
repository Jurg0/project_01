package com.project01.session

enum class AdvancedCommandType {
    TURN_OFF_SCREEN,
    DEACTIVATE_TORCH
}

data class AdvancedCommand(
    val type: AdvancedCommandType
) : java.io.Serializable