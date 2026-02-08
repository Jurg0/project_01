package com.project01.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GameSync(private val networkManager: NetworkManager) {

    private val reconnectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val reconnectionManager = ReconnectionManager(networkManager, reconnectionScope)

    val port: Int
        get() = (networkManager as? SocketNetworkManager)?.port ?: 8888

    val events = networkManager.events

    fun startServer() {
        networkManager.startServer()
    }

    fun connectTo(host: String, port: Int) {
        networkManager.connectTo(host, port)
    }

    suspend fun broadcast(data: GameMessage) {
        networkManager.broadcast(data)
    }

    fun consumeNonce(address: String): String? {
        return networkManager.consumeNonce(address)
    }

    fun shutdown() {
        reconnectionManager.shutdown()
        reconnectionScope.cancel()
        networkManager.shutdown()
    }
}
