package com.project01.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Exercises the real UDP protocol over loopback. The class itself is plain JVM networking;
 * Robolectric is only needed because it logs via `android.util.Log`, which throws on a bare
 * JVM (and would silently kill the responder coroutine). The test drives it the way a phone
 * would: send a probe, read the responder's address off the reply.
 */
@RunWith(RobolectricTestRunner::class)
class HostDiscoveryTest {

    private var discovery: HostDiscovery? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @After
    fun tearDown() {
        discovery?.stopResponding()
        scope.cancel()
    }

    @Test
    fun `responder answers a probe with the reply marker`() = runBlocking {
        val port = freePort()
        discovery = HostDiscovery(port).also { it.startResponding(scope) }
        delay(200)   // let the socket bind

        DatagramSocket().use { client ->
            client.soTimeout = 3000
            val probe = HostDiscovery.PROBE.toByteArray()
            client.send(DatagramPacket(probe, probe.size, InetAddress.getByName("127.0.0.1"), port))

            val packet = DatagramPacket(ByteArray(256), 256)
            client.receive(packet)

            assertEquals(HostDiscovery.REPLY, String(packet.data, 0, packet.length))
            // The address the player would use is read from the packet, never computed.
            assertNotNull(packet.address.hostAddress)
        }
    }

    @Test
    fun `responder ignores traffic that is not our probe`() = runBlocking {
        val port = freePort()
        discovery = HostDiscovery(port).also { it.startResponding(scope) }
        delay(200)

        DatagramSocket().use { client ->
            client.soTimeout = 700
            val junk = "hello?".toByteArray()
            client.send(DatagramPacket(junk, junk.size, InetAddress.getByName("127.0.0.1"), port))

            val packet = DatagramPacket(ByteArray(256), 256)
            val replied = try {
                client.receive(packet); true
            } catch (e: java.net.SocketTimeoutException) {
                false
            }
            assertEquals(false, replied)
        }
    }

    @Test
    fun `findHost returns null when no host is listening`() = runBlocking {
        // No responder started: the player must give up and report "not found" rather than
        // inventing an address to dial.
        val result = HostDiscovery(freePort()).findHost(timeoutMs = 900)
        assertNull(result)
    }

    @Test
    fun `stopResponding releases the port so a later game can bind it again`() = runBlocking {
        val port = freePort()
        val first = HostDiscovery(port).also { it.startResponding(scope) }
        delay(200)
        first.stopResponding()
        delay(200)

        val second = HostDiscovery(port).also { it.startResponding(scope) }
        discovery = second
        delay(200)

        DatagramSocket().use { client ->
            client.soTimeout = 3000
            val probe = HostDiscovery.PROBE.toByteArray()
            client.send(DatagramPacket(probe, probe.size, InetAddress.getByName("127.0.0.1"), port))
            val packet = DatagramPacket(ByteArray(256), 256)
            client.receive(packet)
            assertEquals(HostDiscovery.REPLY, String(packet.data, 0, packet.length))
        }
    }
}
