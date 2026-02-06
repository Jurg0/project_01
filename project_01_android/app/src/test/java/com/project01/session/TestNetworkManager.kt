package com.project01.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TestNetworkManager : NetworkManager {

    private val _events = MutableSharedFlow<Pair<Any, String>>()
    override val events: Flow<Pair<Any, String>> = _events.asSharedFlow()

    var onStartServer: (() -> Unit)? = null
    var onConnectTo: ((host: String, port: Int) -> Unit)? = null
    var onBroadcast: (suspend (data: Any) -> Unit)? = null
    var onShutdown: (() -> Unit)? = null

    override fun startServer() {
        onStartServer?.invoke()
    }

    override fun connectTo(host: String, port: Int) {
        onConnectTo?.invoke(host, port)
    }

    override suspend fun broadcast(data: Any) {
        onBroadcast?.invoke(data)
    }

    override fun shutdown() {
        onShutdown?.invoke()
    }

    suspend fun emitEvent(event: Pair<Any, String>) {
        _events.emit(event)
    }
}
