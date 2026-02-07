package com.project01.session

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

internal data class Heartbeat(val timestamp: Long = System.currentTimeMillis()) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

class SocketNetworkManager(val port: Int = 8888) : NetworkManager {

    companion object {
        private const val TAG = "SocketNetworkManager"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
    }

    private val serverSocket = ServerSocket(port)
    private val clients = ConcurrentHashMap<String, Socket>()
    private val clientOutputStreams = ConcurrentHashMap<String, ObjectOutputStream>()
    private val lastHeartbeat = ConcurrentHashMap<String, Long>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<NetworkEvent>()
    override val events: Flow<NetworkEvent> = _events.asSharedFlow()

    override fun startServer() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    val client = serverSocket.accept()
                    val address = client.inetAddress.hostAddress
                    if (address != null) {
                        clients[address] = client
                        lastHeartbeat[address] = System.currentTimeMillis()
                        launch { handleClient(client) }
                    } else {
                        Log.w(TAG, "Accepted client with null address, closing")
                        client.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting client", e)
                    _events.emit(NetworkEvent.Error(e))
                }
            }
        }
        startHeartbeat()
    }

    private fun startHeartbeat() {
        coroutineScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                // Send heartbeat to all clients
                val snapshot = clientOutputStreams.entries.toList()
                snapshot.forEach { (address, stream) ->
                    try {
                        stream.writeObject(Heartbeat())
                        stream.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat failed for $address", e)
                    }
                }
                // Check for timed-out clients
                val now = System.currentTimeMillis()
                lastHeartbeat.entries.toList().forEach { (address, lastSeen) ->
                    if (now - lastSeen > HEARTBEAT_TIMEOUT_MS) {
                        Log.w(TAG, "Client $address timed out")
                        lastHeartbeat.remove(address)
                        clientOutputStreams.remove(address)
                        clients.remove(address)?.let { socket ->
                            try { socket.close() } catch (_: Exception) {}
                        }
                        _events.emit(NetworkEvent.ClientDisconnected(address))
                    }
                }
            }
        }
    }

    override fun connectTo(host: String, port: Int) {
        coroutineScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                val address = socket.localAddress.hostAddress
                if (address != null) {
                    clients[address] = socket
                    launch { handleClient(socket) }
                } else {
                    Log.w(TAG, "Connected socket with null address, closing")
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to host", e)
                _events.emit(NetworkEvent.Error(e))
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            var outputStream: ObjectOutputStream? = null
            var inputStream: ObjectInputStream? = null
            try {
                outputStream = ObjectOutputStream(client.getOutputStream())
                client.inetAddress.hostAddress?.let { address ->
                    clientOutputStreams[address] = outputStream
                }
                inputStream = ObjectInputStream(client.getInputStream())
                while (isActive) {
                    val data = inputStream.readObject()
                    client.inetAddress.hostAddress?.let { address ->
                        if (data is Heartbeat) {
                            lastHeartbeat[address] = System.currentTimeMillis()
                        } else {
                            lastHeartbeat[address] = System.currentTimeMillis()
                            _events.emit(NetworkEvent.DataReceived(data, address))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
                _events.emit(NetworkEvent.Error(e))
            } finally {
                removeClient(client)
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun broadcast(data: Any) {
        withContext(Dispatchers.IO) {
            val snapshot = clientOutputStreams.entries.toList()
            snapshot.forEach { (address, stream) ->
                try {
                    stream.writeObject(data)
                    stream.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error broadcasting to $address", e)
                    clientOutputStreams.remove(address)
                    clients.remove(address)?.close()
                    _events.emit(NetworkEvent.Error(e))
                }
            }
        }
    }

    private suspend fun removeClient(client: Socket) {
        client.inetAddress.hostAddress?.let { address ->
            clients.remove(address)
            clientOutputStreams.remove(address)
            lastHeartbeat.remove(address)
            _events.emit(NetworkEvent.ClientDisconnected(address))
        }
    }

    override fun shutdown() {
        try { serverSocket.close() } catch (_: Exception) {}
        clients.values.forEach { try { it.close() } catch (_: Exception) {} }
        clientOutputStreams.values.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        clientOutputStreams.clear()
        coroutineScope.cancel()
    }
}
