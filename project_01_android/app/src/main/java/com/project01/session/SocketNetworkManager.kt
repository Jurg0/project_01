package com.project01.session

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class SocketNetworkManager(val port: Int = 8888) : NetworkManager {

    companion object {
        private const val TAG = "SocketNetworkManager"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
    }

    private val serverSocket = ServerSocket(port)
    private val clients = ConcurrentHashMap<String, Socket>()
    private val clientOutputStreams = ConcurrentHashMap<String, OutputStream>()
    private val lastHeartbeat = ConcurrentHashMap<String, Long>()
    private val clientNonces = ConcurrentHashMap<String, String>()
    private var isServer = false
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<NetworkEvent>()
    override val events: Flow<NetworkEvent> = _events.asSharedFlow()

    // Single-threaded dispatcher for every outbound write. A plain Mutex over
    // the multi-threaded Dispatchers.IO pool was not enough: two coroutines
    // launched in order from Main land on different IO worker threads and
    // race for the lock, so wire-order ended up being decided by CPU
    // scheduling instead of caller-order. With one worker, submissions form
    // a FIFO queue — wire-order matches the order broadcast() was called,
    // which is what two consecutive PlaybackCommand broadcasts need.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Blocking I/O. Must run on [sendDispatcher]. */
    private fun writeFrame(stream: OutputStream, bytes: ByteArray) {
        stream.write(bytes)
        stream.flush()
    }

    override fun startServer() {
        isServer = true
        coroutineScope.launch {
            while (isActive) {
                try {
                    val client = serverSocket.accept()
                    val address = client.inetAddress.hostAddress
                    if (address != null) {
                        // Clean up old dead connection from same address (client reconnecting)
                        clients.remove(address)?.let { oldSocket ->
                            clientOutputStreams.remove(address)
                            lastHeartbeat.remove(address)
                            clientNonces.remove(address)
                            try { oldSocket.close() } catch (_: Exception) {}
                        }
                        clients[address] = client
                        lastHeartbeat[address] = System.currentTimeMillis()
                        launch { handleClient(client) }
                    } else {
                        Log.w(TAG, "Accepted client with null address, closing")
                        client.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting client", e)
                    _events.emit(NetworkEvent.Error(e, "accept"))
                }
            }
        }
        startHeartbeat()
    }

    private fun startHeartbeat() {
        coroutineScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val heartbeatBytes = MessageEnvelope.encode(HeartbeatMsg())
                withContext(sendDispatcher) {
                    val snapshot = clientOutputStreams.entries.toList()
                    snapshot.forEach { (address, stream) ->
                        try {
                            writeFrame(stream, heartbeatBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "Heartbeat failed for $address", e)
                        }
                    }
                }
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
                _events.emit(NetworkEvent.Error(e, "connectTo"))
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        withContext(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            var inputStream: DataInputStream? = null
            try {
                val os = client.getOutputStream()
                outputStream = os
                val address = client.inetAddress.hostAddress
                if (address != null) {
                    clientOutputStreams[address] = os
                    if (isServer) {
                        val nonce = PasswordHasher.generateNonce()
                        clientNonces[address] = nonce
                        val challengeBytes = MessageEnvelope.encode(PasswordChallenge(nonce))
                        withContext(sendDispatcher) {
                            writeFrame(os, challengeBytes)
                        }
                    }
                    _events.emit(NetworkEvent.ClientConnected(address))
                }
                inputStream = DataInputStream(client.getInputStream())
                while (isActive) {
                    val message = MessageEnvelope.readFrom(inputStream)
                    client.inetAddress.hostAddress?.let { address ->
                        if (message is HeartbeatMsg) {
                            lastHeartbeat[address] = System.currentTimeMillis()
                        } else {
                            lastHeartbeat[address] = System.currentTimeMillis()
                            _events.emit(NetworkEvent.DataReceived(message, address))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
                _events.emit(NetworkEvent.Error(e, "handleClient"))
            } finally {
                removeClient(client)
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun broadcast(data: GameMessage) {
        val bytes = MessageEnvelope.encode(data)
        val failures = mutableListOf<Pair<String, Exception>>()
        withContext(sendDispatcher) {
            val snapshot = clientOutputStreams.entries.toList()
            snapshot.forEach { (address, stream) ->
                try {
                    writeFrame(stream, bytes)
                } catch (e: Exception) {
                    clientOutputStreams.remove(address)
                    clients.remove(address)?.close()
                    failures.add(address to e)
                }
            }
        }
        // Emit error events outside the send dispatcher; SharedFlow.emit can
        // suspend on a slow subscriber and we don't want to stall the single
        // sender thread (and with it every other in-flight broadcast).
        failures.forEach { (address, e) ->
            Log.e(TAG, "Error broadcasting to $address", e)
            _events.emit(NetworkEvent.Error(e, "broadcast→$address"))
        }
    }

    override suspend fun sendTo(address: String, data: GameMessage) {
        val stream = clientOutputStreams[address] ?: return
        val bytes = MessageEnvelope.encode(data)
        var failure: Exception? = null
        withContext(sendDispatcher) {
            try {
                writeFrame(stream, bytes)
            } catch (e: Exception) {
                clientOutputStreams.remove(address)
                clients.remove(address)?.close()
                failure = e
            }
        }
        failure?.let { e ->
            Log.e(TAG, "Error sending to $address", e)
            _events.emit(NetworkEvent.Error(e, "sendTo→$address"))
        }
    }

    private suspend fun removeClient(client: Socket) {
        client.inetAddress.hostAddress?.let { address ->
            clients.remove(address)
            clientOutputStreams.remove(address)
            lastHeartbeat.remove(address)
            clientNonces.remove(address)
            _events.emit(NetworkEvent.ClientDisconnected(address))
        }
    }

    override fun consumeNonce(address: String): String? {
        return clientNonces.remove(address)
    }

    /**
     * Evict a client immediately (used by the password hard-gate to kick clients that
     * fail or never authenticate). Removing it from [clientOutputStreams] first means the
     * next broadcast skips it even before the read loop notices the closed socket. Closing
     * the socket unblocks [handleClient]'s read → its finally re-emits ClientDisconnected;
     * the duplicate is harmless (roster/auth cleanup is idempotent).
     */
    override fun disconnectClient(address: String) {
        clientOutputStreams.remove(address)
        lastHeartbeat.remove(address)
        clientNonces.remove(address)
        clients.remove(address)?.let { try { it.close() } catch (_: Exception) {} }
        coroutineScope.launch { _events.emit(NetworkEvent.ClientDisconnected(address)) }
    }

    override fun shutdown() {
        try { serverSocket.close() } catch (_: Exception) {}
        clients.values.forEach { try { it.close() } catch (_: Exception) {} }
        clientOutputStreams.values.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        clientOutputStreams.clear()
        clientNonces.clear()
        coroutineScope.cancel()
    }
}
