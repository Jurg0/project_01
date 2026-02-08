package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("password_challenge")
data class PasswordChallenge(val nonce: String) : GameMessage
