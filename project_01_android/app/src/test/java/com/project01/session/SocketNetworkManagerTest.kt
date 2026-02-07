package com.project01.session

import app.cash.turbine.turbineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class SocketNetworkManagerTest {

    private lateinit var manager: SocketNetworkManager

    /**
     * Finds a free port by briefly opening and closing a ServerSocket on port 0.
     */
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
    fun `server start and client connection`() = runTest {
        turbineScope {
            val events = manager.events.testIn(backgroundScope)

            manager.startServer()

            // Give the server accept loop a moment to start
            delay(200)

            // Connect a raw socket client to the server
            val client = Socket("127.0.0.1", manager.port)
            assertTrue("Client should be connected", client.isConnected)

            // The server writes an ObjectOutputStream header upon handleClient.
            // Read it to confirm the server acknowledged the connection.
            val ois = ObjectInputStream(client.getInputStream())
            assertNotNull("Should be able to create ObjectInputStream from server connection", ois)

            // Clean up client side
            client.close()

            // After closing the client, the server should emit a ClientDisconnected event
            val event = events.awaitItem()
            // The server may emit an Error (from the read loop breaking) or ClientDisconnected.
            // Both are acceptable indicators that disconnection was detected.
            assertTrue(
                "Expected ClientDisconnected or Error event, got $event",
                event is NetworkEvent.ClientDisconnected || event is NetworkEvent.Error
            )

            events.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `broadcast delivers message to connected client`() = runTest {
        turbineScope {
            val events = manager.events.testIn(backgroundScope)

            manager.startServer()
            delay(200)

            // Connect a raw socket client
            val client = Socket("127.0.0.1", manager.port)

            // Set up object streams. The server creates an ObjectOutputStream first
            // in handleClient, so the client must create ObjectInputStream first to
            // read the server's stream header, then ObjectOutputStream.
            val clientInput = ObjectInputStream(client.getInputStream())
            val clientOutput = ObjectOutputStream(client.getOutputStream())
            clientOutput.flush()

            // Give the server time to register the client's output stream
            delay(500)

            // Broadcast a message from the server to all connected clients
            val testMessage = "Hello from server"
            manager.broadcast(testMessage)

            // The client should receive the broadcast message.
            // There may be Heartbeat objects interleaved, so skip those.
            var received: Any? = null
            for (attempt in 1..10) {
                val obj = clientInput.readObject()
                if (obj !is Heartbeat) {
                    received = obj
                    break
                }
            }

            assertEquals("Hello from server", received)

            // Clean up
            client.close()
            events.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `client disconnection emits event and cleans up`() = runTest {
        turbineScope {
            val events = manager.events.testIn(backgroundScope)

            manager.startServer()
            delay(200)

            // Connect a client
            val client = Socket("127.0.0.1", manager.port)
            val clientInput = ObjectInputStream(client.getInputStream())
            assertNotNull(clientInput)

            // Give the server time to register the client
            delay(300)

            // Now disconnect the client
            client.close()

            // The server should detect the disconnection and emit events.
            // We may get an Error event (from the broken read loop) followed by
            // ClientDisconnected, or just ClientDisconnected.
            var gotDisconnected = false
            for (i in 1..3) {
                val event = events.awaitItem()
                if (event is NetworkEvent.ClientDisconnected) {
                    gotDisconnected = true
                    break
                }
                // An Error event from the read loop breaking is also expected
                assertTrue(
                    "Unexpected event type: $event",
                    event is NetworkEvent.Error || event is NetworkEvent.ClientDisconnected
                )
            }
            assertTrue("Should have received a ClientDisconnected event", gotDisconnected)

            events.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shutdown closes server and all active connections`() = runTest {
        manager.startServer()
        delay(200)

        // Connect two clients
        val client1 = Socket("127.0.0.1", manager.port)
        val client1Input = ObjectInputStream(client1.getInputStream())
        assertNotNull(client1Input)

        delay(200)

        val client2 = Socket("127.0.0.1", manager.port)
        val client2Input = ObjectInputStream(client2.getInputStream())
        assertNotNull(client2Input)

        delay(300)

        // Shutdown the manager
        manager.shutdown()

        // After shutdown, the server socket should be closed and reject new connections
        var connectionRefused = false
        try {
            val client3 = Socket("127.0.0.1", manager.port)
            // If we get here, the connection was somehow accepted; fail
            client3.close()
        } catch (e: Exception) {
            connectionRefused = true
        }
        assertTrue("Server should refuse new connections after shutdown", connectionRefused)

        // Existing client sockets should also be closed by the server.
        // Reading from them should fail.
        var client1ReadFailed = false
        try {
            client1Input.readObject()
        } catch (e: Exception) {
            client1ReadFailed = true
        }
        assertTrue("Client 1 read should fail after server shutdown", client1ReadFailed)

        var client2ReadFailed = false
        try {
            client2Input.readObject()
        } catch (e: Exception) {
            client2ReadFailed = true
        }
        assertTrue("Client 2 read should fail after server shutdown", client2ReadFailed)
    }
}
