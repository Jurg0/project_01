package com.project01.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class GameSync(private val networkManager: NetworkManager) {

    val events = networkManager.events

    fun startServer() {
        networkManager.startServer()
    }

    fun connectTo(host: String, port: Int) {
        networkManager.connectTo(host, port)
    }

    suspend fun broadcast(data: Any) {
        networkManager.broadcast(data)
    }

    fun shutdown() {
        networkManager.shutdown()
    }
}
