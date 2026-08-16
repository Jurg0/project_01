package com.project01.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the game master on the local network by asking it, instead of deriving its address.
 *
 * The game master answers UDP probes on [DISCOVERY_PORT]; a joining player broadcasts one
 * probe and takes the host address from the **source address of the reply**. Nothing about
 * the address is ever computed.
 *
 * This replaced a series of derivation attempts that each worked on some phones and failed on
 * others, because each assumed something about how the host configures its hotspot:
 *  - `LinkProperties.dhcpServerAddress` — API 30+ only, so silently unavailable on older phones;
 *  - the link's default-route gateway — on one field device the only gateway was IPv6
 *    link-local (`fe80::…`), which cannot be dialled without a scope id;
 *  - "the access point is .1 of our /24" — a convention that phone hotspots do not all follow
 *    (a field device's `.1` did not answer ARP at all).
 * Since the hosting phone, its Android version and its subnet all change from game to game,
 * asking is the only approach that generalises.
 *
 * The sole remaining assumption is that host and player share an L2 network, which is inherent
 * to the game (everyone is on the host's hotspot).
 */
class HostDiscovery(private val port: Int = DISCOVERY_PORT) {

    private var responderSocket: DatagramSocket? = null
    private var responderJob: Job? = null

    /**
     * Game-master side: answer discovery probes until [stopResponding].
     *
     * Bound to the wildcard address so it receives broadcasts on whichever interface the
     * hotspot runs on. Replies go straight back to the probe's sender, so the player learns
     * our address without either side knowing the subnet.
     */
    fun startResponding(scope: CoroutineScope) {
        stopResponding()
        // Bind on the caller's thread, before returning: the game master is listening the
        // moment createGame() completes, so a player probing immediately afterwards can't
        // arrive before the socket exists — and a port clash surfaces here rather than
        // vanishing inside a coroutine.
        val socket = try {
            // Deliberately not `DatagramSocket(null).apply { bind(InetSocketAddress(port)) }`:
            // inside `apply`, `port` resolves to the socket's own port property (-1 while
            // unbound), not this class's port, and the bind fails with "port out of range".
            DatagramSocket(null).also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not bind discovery responder on $port", e)
            return
        }
        responderSocket = socket
        Log.d(TAG, "responder listening on $port")

        responderJob = scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)   // blocking; stopResponding() closes the socket
                    val message = String(packet.data, 0, packet.length)
                    if (message == PROBE) {
                        val reply = REPLY.toByteArray()
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        Log.d(TAG, "answered probe from ${packet.address.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                // Also the normal exit path: closing the socket interrupts receive().
                Log.d(TAG, "responder stopped: ${e.javaClass.simpleName}")
            }
        }
    }

    fun stopResponding() {
        responderJob?.cancel()
        responderJob = null
        try { responderSocket?.close() } catch (_: Exception) {}
        responderSocket = null
    }

    /**
     * Player side: broadcast probes and return the first responder's address, or null if the
     * host doesn't answer within [timeoutMs]. Retries because a single UDP datagram can be
     * dropped without notice.
     */
    suspend fun findHost(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        val targets = broadcastTargets()
        Log.d(TAG, "probing ${targets.joinToString { it.hostAddress ?: "?" }}")
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = REPLY_WAIT_MS.toInt()
                val probe = PROBE.toByteArray()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    targets.forEach { target ->
                        try {
                            socket.send(DatagramPacket(probe, probe.size, target, port))
                        } catch (e: Exception) {
                            Log.d(TAG, "probe to ${target.hostAddress} failed: ${e.message}")
                        }
                    }
                    val reply = awaitReply(socket, deadline)
                    if (reply != null) {
                        Log.d(TAG, "host answered from $reply")
                        return@withContext reply
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
        }
        Log.w(TAG, "no host answered within ${timeoutMs}ms")
        null
    }

    /** Reads datagrams until a valid reply arrives, the socket times out, or [deadline] passes. */
    private fun awaitReply(socket: DatagramSocket, deadline: Long): String? {
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(ByteArray(BUFFER_BYTES), BUFFER_BYTES)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                return null   // give the caller a chance to re-probe
            }
            if (String(packet.data, 0, packet.length) == REPLY) {
                return packet.address.hostAddress
            }
        }
        return null
    }

    /**
     * Every broadcast address this device actually has, read from its interfaces — so it works
     * on any subnet without knowing anything about it. The global broadcast address is included
     * as a fallback for links that don't publish a directed one.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val fromInterfaces = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        } catch (e: Exception) {
            Log.w(TAG, "could not enumerate interfaces", e)
            emptyList()
        }
        val global = try {
            listOf(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            emptyList()
        }
        return (fromInterfaces + global).distinct()
    }

    companion object {
        private const val TAG = "GameNet"

        /** Separate from the game's TCP port so the two never contend. */
        const val DISCOVERY_PORT = 8889
        const val PROBE = "PROJECT01_DISCOVER_V1"
        const val REPLY = "PROJECT01_HOST_V1"

        const val DISCOVERY_TIMEOUT_MS = 4_000L
        private const val REPLY_WAIT_MS = 800L
        private const val BUFFER_BYTES = 256
    }
}
