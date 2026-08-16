package com.project01.session

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionControllerTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var network: TestNetworkManager
    private lateinit var gameSync: GameSync
    private lateinit var playbackController: PlaybackController
    private val broadcasts = mutableListOf<GameMessage>()
    private val sentTo = mutableListOf<Pair<String, GameMessage>>()
    private val uiErrors = mutableListOf<UiError>()
    private val sessionStarts = mutableListOf<Boolean>()
    private val sessionEnds = mutableListOf<Boolean>()
    private val authenticated = mutableListOf<String>()
    private val disconnected = mutableListOf<String>()
    /** true = pinned to the game's Wi-Fi network, false = released. */
    private val networkBinds = mutableListOf<Boolean>()
    private var currentVideos: List<Video> = emptyList()
    private var wifiEnabled = true
    /** What resolveHostAddress() returns — derived from the link, used only as a fallback. */
    private var hostAddress: String? = "192.168.43.1"
    /** What LAN discovery returns — the GM answering a probe. */
    private var discoveredHost: String? = null
    private val discoveryResponder = mutableListOf<Boolean>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun newController(): SessionController {
        return SessionController(
            gameSync = gameSync,
            playbackController = playbackController,
            scope = scope,
            videosProvider = { currentVideos },
            isWifiEnabled = { wifiEnabled },
            openWifiSettings = { },
            discoverHost = { discoveredHost },
            setDiscoveryResponder = { discoveryResponder.add(it) },
            resolveHostAddress = { hostAddress },
            setGameNetworkBound = { networkBinds.add(it) },
            postUiError = { uiErrors.add(it) },
            onSessionStarted = { sessionStarts.add(it) },
            onSessionEnded = { sessionEnds.add(it) },
            onClientAuthenticated = { authenticated.add(it) },
        )
    }

    @Before
    fun setup() {
        broadcasts.clear()
        sentTo.clear()
        uiErrors.clear()
        sessionStarts.clear()
        sessionEnds.clear()
        currentVideos = emptyList()
        wifiEnabled = true
        hostAddress = "192.168.43.1"
        discoveredHost = null
        discoveryResponder.clear()
        authenticated.clear()
        disconnected.clear()
        networkBinds.clear()
        network = TestNetworkManager().apply {
            onBroadcast = { msg -> broadcasts.add(msg) }
            onSendTo = { addr, msg -> sentTo.add(addr to msg) }
            onDisconnectClient = { addr -> disconnected.add(addr) }
        }
        gameSync = GameSync(network)
        playbackController = PlaybackController(
            gameSync = gameSync,
            scope = scope,
            isGameMaster = { false },
        )
    }

    @Test
    fun `handleClientConnected on player stops reconnect and sets CONNECTED`() {
        val controller = newController()
        controller.connectToHost()
        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }

        controller.handleClientConnected("192.168.49.1")

        assertEquals(ConnectionStatus.CONNECTED, connectionState)
    }

    @Test
    fun `handleClientConnected on game master is no-op for connection state`() {
        val controller = newController()
        controller.createGame("secret")
        // createGame posts HOST. handleClientConnected arms an auth timeout for the
        // client but does not change connection state → still HOST.
        controller.handleClientConnected("peer")

        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }
        assertEquals(ConnectionStatus.HOST, connectionState)
    }

    @Test
    fun `handleClientDisconnected on player triggers reconnect when host known`() {
        val controller = newController()
        controller.connectToHost()

        val shouldHandle = controller.handleClientDisconnected("192.168.49.1")

        assertFalse(shouldHandle)
        // No direct DISCONNECTED state since reconnect is attempted. (We don't assert
        // reconnect side-effects — ReconnectionManager has its own tests.)
    }

    @Test
    fun `handleClientDisconnected on game master returns true so caller handles roster`() {
        val controller = newController()
        controller.createGame("secret")

        val shouldHandle = controller.handleClientDisconnected("peer")

        assertTrue(shouldHandle)
    }

    @Test
    fun `handleClientDisconnected ignored when ending game`() {
        val controller = newController()
        controller.connectToHost()
        controller.handleEndGame()

        val shouldHandle = controller.handleClientDisconnected("192.168.49.1")

        assertFalse(shouldHandle)
    }

    @Test
    fun `handlePasswordChallenge with mismatched protocol version emits Critical error and does not respond`() {
        val controller = newController()
        controller.joinGame("Player", "secret")

        controller.handlePasswordChallenge(PasswordChallenge(nonce = "abc", protocolVersion = 999))

        assertEquals(1, uiErrors.size)
        assertTrue(uiErrors[0] is UiError.Critical)
        assertTrue(broadcasts.none { it is PasswordMessage })
    }

    @Test
    fun `joinGame then handlePasswordChallenge sends hashed password`() = runTest(dispatcher) {
        val controller = newController()
        controller.joinGame("Player", "secret")

        controller.handlePasswordChallenge(PasswordChallenge(nonce = "abc", protocolVersion = MessageEnvelope.PROTOCOL_VERSION))
        advanceUntilIdle()

        val msg = broadcasts.filterIsInstance<PasswordMessage>().single()
        assertEquals(PasswordHasher.hash("secret", "abc"), msg.passwordHash)
    }

    @Test
    fun `handlePasswordChallenge before joinGame stashes nonce, then joinGame sends hash`() = runTest(dispatcher) {
        val controller = newController()

        controller.handlePasswordChallenge(PasswordChallenge(nonce = "xyz", protocolVersion = MessageEnvelope.PROTOCOL_VERSION))
        controller.joinGame("Player", "secret")
        advanceUntilIdle()

        val msg = broadcasts.filterIsInstance<PasswordMessage>().single()
        assertEquals(PasswordHasher.hash("secret", "xyz"), msg.passwordHash)
    }

    @Test
    fun `handlePasswordMessage on game master verifies correct hash and sends success`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")  // Sets gamePassword internally
        network.onConsumeNonce = { addr -> if (addr == "peer") "n0nce" else null }

        val correctHash = PasswordHasher.hash("secret", "n0nce")
        controller.handlePasswordMessage(PasswordMessage(correctHash), senderAddress = "peer")
        advanceUntilIdle()

        // Response goes to the specific joiner (sendTo), not broadcast.
        val response = sentTo.filter { it.first == "peer" }.map { it.second }
            .filterIsInstance<PasswordResponseMessage>().single()
        assertTrue(response.success)
        assertEquals(listOf("peer"), authenticated)
        assertTrue(disconnected.isEmpty())
    }

    @Test
    fun `handlePasswordMessage on game master rejects incorrect hash`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")
        network.onConsumeNonce = { _ -> "n0nce" }

        controller.handlePasswordMessage(PasswordMessage("wrong-hash"), senderAddress = "peer")
        advanceUntilIdle()

        val response = sentTo.filter { it.first == "peer" }.map { it.second }
            .filterIsInstance<PasswordResponseMessage>().single()
        assertFalse(response.success)
        // Hard gate: a rejected client is disconnected, not left in the broadcast set.
        assertEquals(listOf("peer"), disconnected)
        assertTrue(authenticated.isEmpty())
    }

    @Test
    fun `handlePasswordMessage with missing nonce rejects`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")
        network.onConsumeNonce = { _ -> null }

        controller.handlePasswordMessage(PasswordMessage("any-hash"), senderAddress = "peer")
        advanceUntilIdle()

        val response = sentTo.filter { it.first == "peer" }.map { it.second }
            .filterIsInstance<PasswordResponseMessage>().single()
        assertFalse(response.success)
        assertEquals(listOf("peer"), disconnected)
    }

    @Test
    fun `successful handlePasswordMessage pushes initial playlist and playback intent to joiner`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")
        currentVideos = listOf(Video(android.net.Uri.parse("content://v1"), "v1"))
        playbackController.play(0, 1000L)
        network.onConsumeNonce = { _ -> "n0nce" }

        val correctHash = PasswordHasher.hash("secret", "n0nce")
        controller.handlePasswordMessage(PasswordMessage(correctHash), senderAddress = "peer")
        advanceUntilIdle()

        // pushInitialStateTo first sends VideoListMessage, then PlaybackCommand
        // (after the per-client PasswordResponseMessage, which we filter out here).
        val toPeer = sentTo.filter { it.first == "peer" }.map { it.second }
            .filter { it !is PasswordResponseMessage }
        assertEquals(2, toPeer.size)
        assertTrue(toPeer[0] is VideoListMessage)
        val cmd = toPeer[1] as PlaybackCommand
        assertEquals(0, cmd.videoIndex)
        assertEquals(1000L, cmd.playbackPosition)
        assertTrue(cmd.playWhenReady)
    }

    @Test
    fun `handlePasswordResponseMessage updates passwordVerified and sends player name on success`() = runTest(dispatcher) {
        val controller = newController()
        controller.joinGame("Alice", "secret")
        broadcasts.clear()

        controller.handlePasswordResponseMessage(PasswordResponseMessage(true))
        advanceUntilIdle()

        assertEquals(true, controller.passwordVerified.value)
        // Password success is the gate: the client session starts only now.
        assertEquals(listOf(false), sessionStarts)
        val nameMsg = broadcasts.filterIsInstance<PlayerNameMessage>().single()
        assertEquals("Alice", nameMsg.playerName)
    }

    @Test
    fun `handlePasswordResponseMessage with failure returns to start and does not send player name`() = runTest(dispatcher) {
        val controller = newController()
        controller.joinGame("Alice", "secret")
        broadcasts.clear()

        controller.handlePasswordResponseMessage(PasswordResponseMessage(false))
        advanceUntilIdle()

        assertEquals(false, controller.passwordVerified.value)
        assertTrue(broadcasts.none { it is PlayerNameMessage })
        // Wrong password → tear down cleanly and return to the start screen.
        assertTrue(sessionStarts.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
    }

    @Test
    fun `handleEndGame clears a stale password rejection so it cannot re-toast`() {
        val controller = newController()
        controller.handlePasswordResponseMessage(PasswordResponseMessage(false))
        assertEquals(false, controller.passwordVerified.value)

        controller.handleEndGame()

        assertEquals(true, controller.passwordVerified.value)
    }

    @Test
    fun `endGame clears a stale password rejection so it cannot re-toast`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")
        controller.handlePasswordResponseMessage(PasswordResponseMessage(false))
        assertEquals(false, controller.passwordVerified.value)

        controller.endGame()
        advanceUntilIdle()

        assertEquals(true, controller.passwordVerified.value)
    }

    @Test
    fun `handleEndGame sets DISCONNECTED state and emits Informational error`() {
        val controller = newController()
        controller.connectToHost()

        controller.handleEndGame()

        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Informational && it.message == "Game ended by host" })
        assertEquals(listOf(true), sessionEnds)
        assertFalse(controller.isGameMaster())
    }

    @Test
    fun `endGame broadcasts EndGameMessage and ends session locally`() = runTest(dispatcher) {
        val controller = newController()
        controller.createGame("secret")

        controller.endGame()
        advanceUntilIdle()

        assertTrue(broadcasts.any { it is EndGameMessage })
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertEquals(listOf(false), sessionEnds)
    }

    @Test
    fun `retryConnection skipped when no host known`() {
        val controller = newController()

        controller.retryConnection()

        // No reconnect attempt; nothing observable to assert here other than
        // it does not throw. ReconnectionManager has its own tests.
    }

    @Test
    fun `passwordVerified starts unset`() {
        val controller = newController()
        assertNull(controller.passwordVerified.value)
    }

    // --- Hotspot LAN model (replaced Wi-Fi Direct) ---

    @Test
    fun `createGame claims the host role and starts the server without needing Wi-Fi on`() {
        // Hosting a mobile hotspot turns the station Wi-Fi radio off, so the game master
        // must be able to start a game with isWifiEnabled() == false.
        wifiEnabled = false
        var started = false
        network.onStartServer = { started = true }
        val controller = newController()

        controller.createGame("secret")

        assertTrue("role must be set unconditionally", controller.isGameMaster())
        assertTrue("TCP server must be started", started)
        assertEquals(ConnectionStatus.HOST, controller.connectionState.value)
        assertEquals(listOf(true), sessionStarts)
        assertTrue(uiErrors.isEmpty())
    }

    @Test
    fun `connectToHost dials the resolved gateway and does not start the session yet`() {
        hostAddress = "192.168.43.1"
        discoveredHost = null
        discoveryResponder.clear()
        var dialed: Pair<String, Int>? = null
        network.onConnectTo = { host, port -> dialed = host to port }
        val controller = newController()

        controller.connectToHost()

        assertEquals("192.168.43.1", dialed?.first)
        assertEquals(gameSync.port, dialed?.second)
        assertEquals(ConnectionStatus.CONNECTING, controller.connectionState.value)
        // The password hard gate still owns session start.
        assertTrue(sessionStarts.isEmpty())
        assertFalse(controller.isGameMaster())
    }

    @Test
    fun `connectToHost prefers the address the host reports over one derived from the link`() {
        // Deriving the host address failed across devices (no dhcpServerAddress below API 30,
        // an IPv6-only gateway on one phone, a .1 that answered nothing on another), so a
        // reply from the actual host always wins.
        discoveredHost = "10.245.195.42"
        hostAddress = "192.168.43.1"
        var dialed: Pair<String, Int>? = null
        network.onConnectTo = { host, port -> dialed = host to port }
        val controller = newController()

        controller.connectToHost()

        assertEquals("10.245.195.42", dialed?.first)
    }

    @Test
    fun `connectToHost falls back to the derived address when nothing answers`() {
        discoveredHost = null
        hostAddress = "192.168.43.1"
        var dialed: Pair<String, Int>? = null
        network.onConnectTo = { host, port -> dialed = host to port }
        val controller = newController()

        controller.connectToHost()

        assertEquals("192.168.43.1", dialed?.first)
    }

    @Test
    fun `connectToHost reports a clear failure and releases the network when the host is unfindable`() {
        discoveredHost = null
        hostAddress = null
        var dialed = false
        network.onConnectTo = { _, _ -> dialed = true }
        val controller = newController()

        controller.connectToHost()

        assertFalse("must not dial a fabricated address", dialed)
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Recoverable })
        assertTrue("binding must be released again", networkBinds.contains(false))
    }

    @Test
    fun `createGame starts answering discovery probes and ending the game stops it`() = runTest(dispatcher) {
        val controller = newController()

        controller.createGame("secret")
        assertEquals(listOf(true), discoveryResponder)

        controller.endGame()
        advanceUntilIdle()
        assertEquals(listOf(true, false), discoveryResponder)
    }

    @Test
    fun `connectToHost pins the process to the game network before dialing`() {
        // A phone with mobile data on keeps cellular as its default network (the hotspot has
        // no internet), so an unbound socket can leave over cellular and never reach the host
        // — one player connected, another failed with ConnectException in the field.
        val controller = newController()

        controller.connectToHost()

        assertEquals(listOf(true), networkBinds)
    }

    @Test
    fun `ending the game releases the network binding`() {
        val controller = newController()
        controller.connectToHost()
        networkBinds.clear()

        controller.handleEndGame()

        assertEquals("the phone must get its normal network back", listOf(false), networkBinds)
    }

    @Test
    fun `connectToHost with no resolvable host emits a recoverable error and does not dial`() {
        hostAddress = null
        var dialed = false
        network.onConnectTo = { _, _ -> dialed = true }
        val controller = newController()

        controller.connectToHost()

        assertFalse("must not dial when the host is unknown", dialed)
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Recoverable })
    }

    @Test
    fun `connectToHost with Wi-Fi off emits a recoverable error and does not dial`() {
        wifiEnabled = false
        var dialed = false
        network.onConnectTo = { _, _ -> dialed = true }
        val controller = newController()

        controller.connectToHost()

        assertFalse(dialed)
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Recoverable })
    }

    @Test
    fun `password success starts the client session after connectToHost`() = runTest(dispatcher) {
        val controller = newController()
        controller.joinGame("Alice", "secret")
        controller.connectToHost()

        controller.handlePasswordResponseMessage(PasswordResponseMessage(true))
        advanceUntilIdle()

        assertEquals(listOf(false), sessionStarts)
    }
}
