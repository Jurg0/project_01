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
}
