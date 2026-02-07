package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("password")
data class PasswordMessage(val password: String) : GameMessage
