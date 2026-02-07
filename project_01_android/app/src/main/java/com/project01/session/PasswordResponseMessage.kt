package com.project01.session

import java.io.Serializable

data class PasswordResponseMessage(val success: Boolean) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
