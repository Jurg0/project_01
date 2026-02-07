package com.project01.session

class GameSync(private val networkManager: NetworkManager) {

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

    fun shutdown() {
        networkManager.shutdown()
    }
}
