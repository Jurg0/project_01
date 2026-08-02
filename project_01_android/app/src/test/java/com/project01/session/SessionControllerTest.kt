package com.project01.session

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
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
    private lateinit var wifiManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private val broadcasts = mutableListOf<GameMessage>()
    private val sentTo = mutableListOf<Pair<String, GameMessage>>()
    private val uiErrors = mutableListOf<UiError>()
    private val sessionStarts = mutableListOf<Boolean>()
    private val sessionEnds = mutableListOf<Boolean>()
    private val authenticated = mutableListOf<String>()
    private val disconnected = mutableListOf<String>()
    private var currentVideos: List<Video> = emptyList()
    private var thisDevice: WifiP2pDevice? = null
    private var wifiEnabled = true

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun newController(): SessionController {
        return SessionController(
            gameSync = gameSync,
            playbackController = playbackController,
            wifiP2pManager = wifiManager,
            channel = channel,
            scope = scope,
            thisDeviceProvider = { thisDevice },
            videosProvider = { currentVideos },
            isWifiEnabled = { wifiEnabled },
            openWifiSettings = { },
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
        thisDevice = WifiP2pDevice().apply { deviceName = "TestDevice" }
        wifiEnabled = true
        authenticated.clear()
        disconnected.clear()
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
        wifiManager = mock()
        channel = mock()
    }

    @Test
    fun `handleConnectionInfo as group owner sets HOST state and starts session as host`() {
        val controller = newController()
        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }

        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })

        assertEquals(ConnectionStatus.HOST, connectionState)
        assertEquals(listOf(true), sessionStarts)
        assertTrue(controller.isGameMaster())
    }

    @Test
    fun `handleConnectionInfo as client sets CONNECTED but does NOT start session (password gate)`() {
        val controller = newController()
        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }

        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })

        assertEquals(ConnectionStatus.CONNECTED, connectionState)
        // Group formation is "connecting/authenticating", not "started" — the client
        // session starts only on PasswordResponseMessage(success).
        assertTrue(sessionStarts.isEmpty())
        assertFalse(controller.isGameMaster())
    }

    @Test
    fun `handleConnectionInfo ignored when group not formed`() {
        val controller = newController()

        controller.handleConnectionInfo(WifiP2pInfo().apply { groupFormed = false })

        assertTrue(sessionStarts.isEmpty())
        assertFalse(controller.isGameMaster())
    }

    @Test
    fun `handleClientConnected on player stops reconnect and sets CONNECTED`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })
        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }

        controller.handleClientConnected("192.168.49.1")

        assertEquals(ConnectionStatus.CONNECTED, connectionState)
    }

    @Test
    fun `handleClientConnected on game master is no-op for connection state`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
        // After handleConnectionInfo, state is HOST. handleClientConnected arms an auth
        // timeout for the client but does not change connection state → still HOST.
        controller.handleClientConnected("peer")

        var connectionState: ConnectionStatus? = null
        controller.connectionState.observeForever { connectionState = it }
        assertEquals(ConnectionStatus.HOST, connectionState)
    }

    @Test
    fun `handleClientDisconnected on player triggers reconnect when host known`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })

        val shouldHandle = controller.handleClientDisconnected("192.168.49.1")

        assertFalse(shouldHandle)
        // No direct DISCONNECTED state since reconnect is attempted. (We don't assert
        // reconnect side-effects — ReconnectionManager has its own tests.)
    }

    @Test
    fun `handleClientDisconnected on game master returns true so caller handles roster`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })

        val shouldHandle = controller.handleClientDisconnected("peer")

        assertTrue(shouldHandle)
    }

    @Test
    fun `handleClientDisconnected ignored when ending game`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })
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
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
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
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
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
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
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
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
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
        verify(wifiManager).removeGroup(any(), anyOrNull())
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
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })
        controller.handlePasswordResponseMessage(PasswordResponseMessage(false))
        assertEquals(false, controller.passwordVerified.value)

        controller.endGame()
        advanceUntilIdle()

        assertEquals(true, controller.passwordVerified.value)
    }

    @Test
    fun `handleEndGame sets DISCONNECTED state and emits Informational error`() {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })

        controller.handleEndGame()

        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Informational && it.message == "Game ended by host" })
        assertEquals(listOf(true), sessionEnds)
        assertFalse(controller.isGameMaster())
    }

    @Test
    fun `endGame broadcasts EndGameMessage and ends session locally`() = runTest(dispatcher) {
        val controller = newController()
        controller.handleConnectionInfo(WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = true
        })

        controller.endGame()
        advanceUntilIdle()

        assertTrue(broadcasts.any { it is EndGameMessage })
        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertEquals(listOf(false), sessionEnds)
        verify(wifiManager).removeGroup(any(), anyOrNull())
    }

    @Test
    fun `createGame with wifi off skips group creation and emits recoverable error`() {
        wifiEnabled = false
        val controller = newController()

        controller.createGame("secret")

        assertEquals(ConnectionStatus.DISCONNECTED, controller.connectionState.value)
        assertTrue(uiErrors.any { it is UiError.Recoverable && it.message.contains("Wi-Fi is off") })
    }

    @Test
    fun `createGame initiates createGroup when wifi enabled`() {
        val controller = newController()

        controller.createGame("secret")

        assertEquals(ConnectionStatus.CONNECTING, controller.connectionState.value)
        verify(wifiManager).createGroup(any(), any())
    }

    @Test
    fun `retryConnection skipped when no host known`() {
        val controller = newController()

        controller.retryConnection()

        // No reconnect attempt; nothing observable to assert here other than
        // it does not throw. ReconnectionManager has its own tests.
    }

    @Test
    fun `connectToPlayer with wifi off emits Wi-Fi-off error`() {
        wifiEnabled = false
        val controller = newController()
        val peer = Player(WifiP2pDevice().apply { deviceAddress = "AA:BB" }, "AA:BB", false)

        controller.connectToPlayer(peer)

        assertTrue(uiErrors.any { it is UiError.Recoverable && it.message.contains("Wi-Fi is off") })
    }

    @Test
    fun `connectToPlayer connects as client with groupOwnerIntent 0`() {
        val controller = newController()
        val peer = Player(WifiP2pDevice().apply { deviceAddress = "AA:BB" }, "AA:BB", false)
        // No stale group to remove: fire the removeGroup callback so connect proceeds.
        whenever(wifiManager.removeGroup(any(), any())).thenAnswer {
            (it.arguments[1] as WifiP2pManager.ActionListener).onFailure(0)
            null
        }
        val configCaptor = argumentCaptor<WifiP2pConfig>()

        controller.connectToPlayer(peer)

        // Stale group is cleared before connecting so groupOwnerIntent is honored.
        verify(wifiManager).removeGroup(any(), any())
        verify(wifiManager).connect(any(), configCaptor.capture(), any())
        // A joiner must never win group ownership, else it inherits the GM role.
        assertEquals(0, configCaptor.firstValue.groupOwnerIntent)
        assertEquals("AA:BB", configCaptor.firstValue.deviceAddress)
    }

    @Test
    fun `passwordVerified starts unset`() {
        val controller = newController()
        assertNull(controller.passwordVerified.value)
    }
}
