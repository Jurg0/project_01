package com.project01.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TestNetworkManager : NetworkManager {

    private val _events = MutableSharedFlow<NetworkEvent>()
    override val events: Flow<NetworkEvent> = _events.asSharedFlow()

    var onStartServer: (() -> Unit)? = null
    var onConnectTo: ((host: String, port: Int) -> Unit)? = null
    var onBroadcast: (suspend (data: GameMessage) -> Unit)? = null
    var onShutdown: (() -> Unit)? = null

    override fun startServer() {
        onStartServer?.invoke()
    }

    override fun connectTo(host: String, port: Int) {
        onConnectTo?.invoke(host, port)
    }

    override suspend fun broadcast(data: GameMessage) {
        onBroadcast?.invoke(data)
    }

    override fun shutdown() {
        onShutdown?.invoke()
    }

    suspend fun emitEvent(event: NetworkEvent) {
        _events.emit(event)
    }
}
