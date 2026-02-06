package com.project01.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class SocketNetworkManager(private val port: Int = 8888) : NetworkManager {

    private val serverSocket = ServerSocket(port)
    private val clients = mutableMapOf<String, Socket>()
    private val clientOutputStreams = mutableMapOf<String, ObjectOutputStream>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Pair<Any, String>>()
    override val events: Flow<Pair<Any, String>> = _events.asSharedFlow()

    override fun startServer() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    val client = serverSocket.accept()
                    client.inetAddress.hostAddress?.let { address ->
                        clients[address] = client
                        launch { handleClient(client) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun connectTo(host: String, port: Int) {
        coroutineScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.localAddress.hostAddress?.let { address ->
                    clients[address] = socket
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val outputStream = ObjectOutputStream(client.getOutputStream())
                client.inetAddress.hostAddress?.let { address ->
                    clientOutputStreams[address] = outputStream
                }
                val inputStream = ObjectInputStream(client.getInputStream())
                while (isActive) {
                    val data = inputStream.readObject()
                    client.inetAddress.hostAddress?.let { address ->
                        _events.emit(data to address)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                removeClient(client)
                client.close()
            }
        }
    }

    override suspend fun broadcast(data: Any) {
        withContext(Dispatchers.IO) {
            clientOutputStreams.values.forEach {
                try {
                    it.writeObject(data)
                    it.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun removeClient(client: Socket) {
        client.inetAddress.hostAddress?.let { address ->
            clients.remove(address)
            clientOutputStreams.remove(address)
        }
    }

    override fun shutdown() {
        coroutineScope.cancel()
        serverSocket.close()
        clients.values.forEach { it.close() }
        clientOutputStreams.values.forEach { it.close() }
    }
}
