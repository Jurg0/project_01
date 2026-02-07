package com.project01.session

import java.io.Serializable

data class PasswordMessage(val password: String) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
