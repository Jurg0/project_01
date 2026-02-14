package com.project01.ui

sealed class UiError(val message: String) {
    class Recoverable(
        message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    ) : UiError(message)

    class Informational(message: String) : UiError(message)

    class Critical(
        message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    ) : UiError(message)
}

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    HOST,
    RECONNECTING
}
