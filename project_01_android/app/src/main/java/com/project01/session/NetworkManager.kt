package com.project01.session

import kotlinx.coroutines.flow.Flow

interface NetworkManager {
    val events: Flow<NetworkEvent>
    fun startServer()
    fun connectTo(host: String, port: Int)
    suspend fun broadcast(data: GameMessage)
    fun shutdown()
}
