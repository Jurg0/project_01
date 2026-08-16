package com.project01.session

import kotlinx.coroutines.flow.Flow

interface NetworkManager {
    val events: Flow<NetworkEvent>
    fun startServer()
    fun connectTo(host: String, port: Int)
    suspend fun broadcast(data: GameMessage)
    suspend fun sendTo(address: String, data: GameMessage)
    fun shutdown()
    fun consumeNonce(address: String): String? = null

    /** Diagnostics: is the accept loop running (i.e. are we hosting)? */
    fun isServerRunning(): Boolean = false

    /** Diagnostics: how many client sockets are currently held. */
    fun connectedClientCount(): Int = 0

    /** Force-drop a connected client (server side): evict it from the broadcast set,
     *  close its socket, and emit ClientDisconnected. Default no-op for non-socket impls. */
    fun disconnectClient(address: String) {}
}
