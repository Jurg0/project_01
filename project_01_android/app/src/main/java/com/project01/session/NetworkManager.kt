package com.project01.session

import kotlinx.coroutines.flow.Flow

interface NetworkManager {
    val events: Flow<Pair<Any, String>>
    fun startServer()
    fun connectTo(host: String, port: Int)
    suspend fun broadcast(data: Any)
    fun shutdown()
}
