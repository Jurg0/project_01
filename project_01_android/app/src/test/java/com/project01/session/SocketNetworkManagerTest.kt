package com.project01.session

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class SocketNetworkManagerTest {

    private lateinit var manager: SocketNetworkManager

    private fun findFreePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Before
    fun setup() {
        val port = findFreePort()
        manager = SocketNetworkManager(port)
    }

    @After
    fun teardown() {
        manager.shutdown()
    }

    @Test
    fun `server accepts client connection`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        assertTrue("Client should be connected", client.isConnected)

        client.close()
    }

    private suspend fun awaitClientConnected() {
        val ready = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            manager.events.collect { event ->
                if (event is NetworkEvent.ClientConnected) {
                    ready.complete(Unit)
                }
            }
        }
        ready.await()
        job.cancel()
    }

    @Test
    fun `broadcast delivers message to connected client`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        client.soTimeout = 5000
        val clientInput = DataInputStream(client.getInputStream())

        awaitClientConnected()

        val testMessage = PlaybackCommand(PlaybackCommandType.NEXT)
        manager.broadcast(testMessage)

        var received: GameMessage? = null
        for (attempt in 1..10) {
            val msg = MessageEnvelope.readFrom(clientInput)
            if (msg !is HeartbeatMsg && msg !is PasswordChallenge) {
                received = msg
                break
            }
        }

        assertEquals(testMessage, received)
        client.close()
    }

    @Test
    fun `shutdown closes server socket and rejects new connections`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        assertTrue(client.isConnected)
        client.close()
        delay(100)

        manager.shutdown()

        // Retry a few times to give the OS time to fully close the socket
        var connectionRefused = false
        for (attempt in 1..5) {
            delay(200)
            try {
                Socket("127.0.0.1", manager.port).close()
            } catch (e: Exception) {
                connectionRefused = true
                break
            }
        }
        assertTrue("Server should refuse new connections after shutdown", connectionRefused)
    }

    @Test
    fun `server sends PasswordChallenge to new client`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        client.soTimeout = 5000
        val clientInput = DataInputStream(client.getInputStream())

        // First message from server should be a PasswordChallenge
        val message = MessageEnvelope.readFrom(clientInput)
        assertTrue("Expected PasswordChallenge, got ${message::class.simpleName}", message is PasswordChallenge)
        val challenge = message as PasswordChallenge
        assertEquals(64, challenge.nonce.length)

        client.close()
    }

    @Test
    fun `consumeNonce returns and removes stored nonce`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        client.soTimeout = 5000
        val clientInput = DataInputStream(client.getInputStream())

        // Read the challenge to get the nonce
        val challenge = MessageEnvelope.readFrom(clientInput) as PasswordChallenge
        delay(200)

        // consumeNonce should return the nonce for the client address
        // Server stores nonce by the client's remote address as seen from the server
        // We need to find the address the server assigned
        val nonce = manager.consumeNonce("127.0.0.1")
        assertNotNull("Nonce should be present", nonce)
        assertEquals(challenge.nonce, nonce)

        // Second call should return null (consumed)
        assertNull(manager.consumeNonce("127.0.0.1"))

        client.close()
    }

    @Test
    fun `ClientConnected event emitted when client connects`() = runTest {
        manager.startServer()
        delay(200)

        launch {
            manager.events.test {
                val event = awaitItem()
                assertTrue("Expected ClientConnected, got $event", event is NetworkEvent.ClientConnected)
                cancelAndIgnoreRemainingEvents()
            }
        }

        delay(100)
        val client = Socket("127.0.0.1", manager.port)
        delay(500)

        client.close()
    }

    @Test
    fun `broadcast delivers different messages in order`() = runTest {
        manager.startServer()
        delay(200)

        val client = Socket("127.0.0.1", manager.port)
        client.soTimeout = 5000
        val clientInput = DataInputStream(client.getInputStream())

        awaitClientConnected()

        val msg1 = PlaybackCommand(PlaybackCommandType.NEXT)
        val msg2 = PlaybackCommand(PlaybackCommandType.PREVIOUS)
        manager.broadcast(msg1)
        manager.broadcast(msg2)

        fun readSkippingHeartbeats(input: DataInputStream): GameMessage? {
            for (i in 1..10) {
                val msg = MessageEnvelope.readFrom(input)
                if (msg !is HeartbeatMsg && msg !is PasswordChallenge) return msg
            }
            return null
        }

        assertEquals(msg1, readSkippingHeartbeats(clientInput))
        assertEquals(msg2, readSkippingHeartbeats(clientInput))

        client.close()
    }
}
