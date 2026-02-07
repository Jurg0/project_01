package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("password_response")
data class PasswordResponseMessage(val success: Boolean) : GameMessage
