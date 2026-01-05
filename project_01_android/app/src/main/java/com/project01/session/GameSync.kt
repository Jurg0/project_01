package com.project01.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class GameSync {
    private val serverSocket = ServerSocket(8888)
    private val clients = mutableMapOf<String, Socket>()
    private val clientOutputStreams = mutableMapOf<String, ObjectOutputStream>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<Pair<Any, String>>()
    val events = _events.asSharedFlow()

    fun startServer() {
        coroutineScope.launch {
            while (isActive) {
                try {
                    val client = serverSocket.accept()
                    val address = client.inetAddress.hostAddress
                    clients[address] = client
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun connectTo(host: String) {
        coroutineScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, 8888), 5000)
                val address = socket.localAddress.hostAddress
                clients[address] = socket
                launch { handleClient(socket) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val outputStream = ObjectOutputStream(client.getOutputStream())
                clientOutputStreams[client.inetAddress.hostAddress] = outputStream
                val inputStream = ObjectInputStream(client.getInputStream())
                while (isActive) {
                    val data = inputStream.readObject()
                    _events.emit(data to client.inetAddress.hostAddress)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                removeClient(client)
                client.close()
            }
        }
    }

    suspend fun broadcast(data: Any) {
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
        val address = client.inetAddress.hostAddress
        clients.remove(address)
        clientOutputStreams.remove(address)
    }

    fun shutdown() {
        coroutineScope.cancel()
        serverSocket.close()
        clients.values.forEach { it.close() }
        clientOutputStreams.values.forEach { it.close() }
    }
}
