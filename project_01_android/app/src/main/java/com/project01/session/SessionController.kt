package com.project01.session

import android.net.wifi.p2p.WifiP2pDevice
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the session lifecycle (createGame / joinGame / endGame) and the password
 * challenge-response handshake. GameViewModel reduces to a facade that exposes LiveData
 * and wires cross-concern callbacks (start/stop periodic jobs, roster updates).
 *
 * **Networking model:** everyone is on one ordinary Wi-Fi LAN — the game master hosts a
 * mobile hotspot that the player devices join *before* the game starts. The GM therefore
 * only starts a TCP server; a player finds it by asking (see [discoverHost]) and dials it,
 * falling back to deriving an address from the link only if nothing answers. This replaced a
 * Wi-Fi Direct implementation whose autonomous group owner proved undiscoverable to older
 * phones, and then a derive-only approach that broke differently on each device.
 *
 * The role bit (`player.isGameMaster`) lives here: set in [createGame] / [connectToHost]
 * and cleared on game end. Other controllers (PlaybackController,
 * FileTransferOrchestrator) receive an `isGameMaster()` lambda that delegates back into
 * this class through GameViewModel.
 */
class SessionController(
    private val gameSync: GameSync,
    private val playbackController: PlaybackController,
    private val scope: CoroutineScope,
    private val videosProvider: () -> List<Video>?,
    private val isWifiEnabled: () -> Boolean,
    private val openWifiSettings: () -> Unit,
    /** Ask the game master for its address over the LAN. Null if nothing answered. */
    private val discoverHost: suspend () -> String? = { null },
    /** Game-master side: answer discovery probes (true) or stop answering (false). */
    private val setDiscoveryResponder: (Boolean) -> Unit = {},
    /**
     * Fallback for naming the host when discovery finds nothing — derived from the link's
     * own configuration, which is why it is only a fallback: it depends on how the hosting
     * phone happens to configure its hotspot, and that varies by device and Android version.
     */
    private val resolveHostAddress: () -> String?,
    /** Pin this process's sockets to the game's Wi-Fi network (true) or release them (false). */
    private val setGameNetworkBound: (Boolean) -> Unit = {},
    private val postUiError: (UiError) -> Unit,
    private val onSessionStarted: (isHost: Boolean) -> Unit,
    private val onSessionEnded: (remoteInitiated: Boolean) -> Unit,
    private val onClientAuthenticated: (address: String) -> Unit = {},
) {
    private val _connectionState = MutableLiveData<ConnectionStatus>()
    val connectionState: LiveData<ConnectionStatus> = _connectionState

    private val _passwordVerified = MutableLiveData<Boolean>()
    val passwordVerified: LiveData<Boolean> = _passwordVerified

    private var player: Player? = null
    private var gamePassword: String? = null
    private var pendingPassword: String? = null
    private var pendingNonce: String? = null
    private var localPlayerName: String? = null
    private var lastHost: String? = null
    private var lastPort: Int? = null
    private var isEndingGame = false

    // --- Password hard gate (GM side) ---
    /** Per-client auth-timeout jobs: a client that hasn't authenticated within
     *  AUTH_TIMEOUT_MS of connecting is force-dropped. */
    private val authTimeouts = ConcurrentHashMap<String, Job>()
    private val authenticatedClients = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // --- Password hard gate (client side) ---
    /** Set when this client's password was rejected, so the ClientDisconnected that
     *  follows the GM's kick returns us to the start screen instead of reconnecting. */
    private var pendingAuthRejected = false

    /** Mirrors setDiscoveryResponder so diagnostics can report it on the host. */
    private var answeringProbes = false

    /** True from the moment this device starts hosting OR starts joining, until the session
     *  ends. Guards against a device becoming a second game master mid-session. */
    private var sessionActive = false

    init {
        observeReconnectionState()
    }

    fun isGameMaster(): Boolean = player?.isGameMaster ?: false

    /**
     * Player side: dial the game master on the Wi-Fi we're already connected to.
     *
     * Connecting the socket is NOT "the game started" — the client session is gated on
     * password success in [handlePasswordResponseMessage] (the password hard gate).
     */
    fun connectToHost() {
        if (!isWifiEnabled()) {
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            postUiError(UiError.Recoverable(
                "Turn on Wi-Fi and connect to the game's hotspot.", "Open Wi-Fi") { openWifiSettings() })
            return
        }
        sessionActive = true
        _connectionState.postValue(ConnectionStatus.CONNECTING)
        // Pin our sockets to the hotspot before probing or dialing: when the phone has mobile
        // data on, the (internet-less) hotspot isn't the default network and traffic can
        // otherwise leave over cellular and never reach the host.
        setGameNetworkBound(true)
        scope.launch {
            // Ask the host where it is; only fall back to deriving an address from the link,
            // which is what proved unreliable across devices.
            val host = discoverHost() ?: resolveHostAddress()
            if (host == null) {
                sessionActive = false
                setGameNetworkBound(false)
                _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                postUiError(UiError.Recoverable(
                    "Couldn't find the game. Make sure you're on the host's hotspot and the host has started the game.",
                    "Retry") { connectToHost() })
                return@launch
            }
            isEndingGame = false
            pendingAuthRejected = false
            player = localPlayer(localPlayerName ?: "Player", isGameMaster = false)
            lastHost = host
            lastPort = gameSync.port
            gameSync.connectTo(host, gameSync.port)
        }
    }

    /**
     * Build this device's [Player]. `Player.device` is only a Parcelable data holder for the
     * roster; with Wi-Fi Direct gone there is no framework-supplied WifiP2pDevice, so it is
     * synthesized. Must never return null — the role bit is read off `player`, so a null
     * here would silently make the game master behave as a player.
     */
    private fun localPlayer(name: String, isGameMaster: Boolean) =
        Player(WifiP2pDevice().apply { deviceName = name }, name, isGameMaster)

    /**
     * Host-side diagnostics, or null when this device isn't hosting. The real game master is
     * a phone we can't attach a debugger to, so this is the only way to see whether it is
     * actually reachable.
     */
    fun hostingState(): HostingState? {
        if (!isGameMaster()) return null
        return HostingState(
            serverRunning = gameSync.isServerRunning(),
            answeringProbes = answeringProbes,
            connectedClients = gameSync.connectedClientCount(),
            authenticatedClients = authenticatedClients.size,
        )
    }

    /** Called from GameViewModel's NetworkEvent.ClientConnected branch. */
    fun handleClientConnected(address: String) {
        if (isGameMaster()) {
            // Password hard gate: arm a timeout so a client that connects but never
            // authenticates is force-dropped instead of silently receiving broadcasts.
            authTimeouts.remove(address)?.cancel()
            authTimeouts[address] = scope.launch {
                delay(AUTH_TIMEOUT_MS)
                if (address !in authenticatedClients) gameSync.disconnectClient(address)
                authTimeouts.remove(address)
            }
            return
        }
        gameSync.reconnectionManager.stopReconnecting()
        _connectionState.postValue(ConnectionStatus.CONNECTED)
    }

    /**
     * Called from GameViewModel's NetworkEvent.ClientDisconnected branch.
     * Returns true if the caller should still perform roster cleanup
     * (game-master side) — false when SessionController handled the event
     * entirely (player-side reconnect attempt).
     */
    fun handleClientDisconnected(address: String): Boolean {
        if (isEndingGame) return false
        // A rejected client's socket close must NOT trigger a reconnect — return to start.
        if (pendingAuthRejected) { pendingAuthRejected = false; return false }
        if (isGameMaster()) {
            authTimeouts.remove(address)?.cancel()
            authenticatedClients.remove(address)
            return true
        }
        val host = lastHost
        val port = lastPort
        if (host != null && port != null) {
            gameSync.reconnectionManager.startReconnecting(host, port)
        } else {
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        }
        return false
    }

    fun handlePasswordChallenge(challenge: PasswordChallenge) {
        if (challenge.protocolVersion != MessageEnvelope.PROTOCOL_VERSION) {
            postUiError(UiError.Critical(
                "Incompatible app version (server: v${challenge.protocolVersion}, local: v${MessageEnvelope.PROTOCOL_VERSION}). Update all devices to the same version."
            ))
            return
        }
        pendingNonce = challenge.nonce
        pendingPassword?.let { password ->
            val hash = PasswordHasher.hash(password, challenge.nonce)
            scope.launch {
                gameSync.broadcast(PasswordMessage(passwordHash = hash))
            }
            pendingNonce = null
        }
    }

    fun handlePasswordMessage(message: PasswordMessage, senderAddress: String) {
        if (!isGameMaster()) return
        val nonce = gameSync.consumeNonce(senderAddress)
        val success = if (nonce != null) {
            val expectedHash = PasswordHasher.hash(gamePassword ?: "", nonce)
            message.passwordHash == expectedHash
        } else {
            false
        }
        scope.launch {
            // sendTo the specific joiner, NOT broadcast — a broadcast response leaked
            // every client's verdict to every other client, and the hard gate needs a
            // private per-client answer.
            gameSync.sendTo(senderAddress, PasswordResponseMessage(success))
            if (success) {
                authenticatedClients.add(senderAddress)
                authTimeouts.remove(senderAddress)?.cancel()
                onClientAuthenticated(senderAddress)   // roster gains this player only now
                pushInitialStateTo(senderAddress)
            } else {
                // Let the rejection frame flush, then kick — an unauthenticated client
                // must not linger in the broadcast set.
                delay(AUTH_FAIL_DRAIN_MS)
                authTimeouts.remove(senderAddress)?.cancel()
                gameSync.disconnectClient(senderAddress)
            }
        }
    }

    fun handlePasswordResponseMessage(message: PasswordResponseMessage) {
        _passwordVerified.postValue(message.success)
        if (message.success) {
            // The password hard gate: the client game UI (isGameStarted) starts ONLY
            // here, after verification — never on merely reaching the host. Idempotent on
            // reconnect re-auth.
            pendingAuthRejected = false
            onSessionStarted(false)
            localPlayerName?.let { name ->
                scope.launch {
                    gameSync.broadcast(PlayerNameMessage(name))
                }
            }
        } else {
            // Wrong password → return to the start screen. isGameStarted was never set,
            // so the UI is already the start screen; just tear down cleanly and block
            // any reconnect.
            pendingAuthRejected = true
            sessionActive = false
            gameSync.reconnectionManager.stopReconnecting()
            lastHost = null
            lastPort = null
            setGameNetworkBound(false)   // give the phone its normal network back
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            postUiError(UiError.Recoverable("Incorrect password. Returned to start."))
        }
    }

    /**
     * Local cleanup when the GM ends the session for everyone.
     * `handleEndGame` (remote-initiated) goes through the same teardown but
     * with `remoteInitiated = true` so the GameViewModel does the extra
     * client-side video/roster purge.
     */
    fun handleEndGame() {
        isEndingGame = true
        sessionActive = false
        gameSync.reconnectionManager.stopReconnecting()
        pendingAuthRejected = false
        setGameNetworkBound(false)   // give the phone its normal network back
        player = null
        lastHost = null
        lastPort = null
        _passwordVerified.postValue(true) // clear any stale rejection so it can't re-toast on recreation
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        postUiError(UiError.Informational("Game ended by host"))
        onSessionEnded(true)
    }

    /**
     * On a successful join, push the GM's playlist and current playback
     * intent to the new client. Without this they keep their local lobby
     * playlist and `videoIndex` from any subsequent state could seek into the
     * wrong list. A PlaybackCommand (not PlaybackState) so the client's
     * intent reconciles unconditionally.
     */
    private suspend fun pushInitialStateTo(address: String) {
        val currentVideos = videosProvider().orEmpty()
        if (currentVideos.isNotEmpty()) {
            gameSync.sendTo(address, VideoListMessage(currentVideos.map { it.toDto() }))
        }
        val current = playbackController.currentIntent()
        gameSync.sendTo(
            address,
            PlaybackCommand(
                PlaybackCommandType.PLAY_PAUSE,
                current.videoIndex,
                current.positionMs,
                current.isPlaying,
            )
        )
    }

    /**
     * Game master: start hosting. There is no network to create — the GM's mobile hotspot
     * is already up and the players are already joined to it — so this just claims the role
     * and starts the TCP server (which binds every interface, so it is reachable on the
     * hotspot subnet).
     *
     * Deliberately does NOT check `isWifiEnabled()`: hosting a mobile hotspot turns the
     * station Wi-Fi radio off, so that check used to block the game master from ever
     * starting a game (field-reported bug).
     */
    fun createGame(password: String) {
        // Refuse to host while this device is already in a session. The CREATE target is a
        // large invisible corner and its password dialog is deliberately identical to JOIN, so
        // a stray double-tap on a phone that has joined (or is joining) would otherwise turn it
        // into a second game master: it would skip file transfer (the GM branch of
        // handleVideoList), broadcast playback intent against the real host, and — now that
        // hosts answer discovery probes — advertise itself to other joining players.
        if (sessionActive) {
            Log.w(TAG, "ignoring createGame: this device is already in a session")
            return
        }
        sessionActive = true
        this.gamePassword = password
        isEndingGame = false
        // Set unconditionally: isGameMaster() reads this, so leaving it null would silently
        // demote the host to a player (no GM overlay, no playback broadcast, no auth gate).
        player = localPlayer("Host", isGameMaster = true)
        lastHost = null
        lastPort = null
        gameSync.startServer()
        // Answer discovery probes so joiners can find us without deriving our address.
        setDiscoveryResponder(true)
        answeringProbes = true
        _connectionState.postValue(ConnectionStatus.HOST)
        onSessionStarted(true)
    }

    fun joinGame(name: String, password: String) {
        localPlayerName = name
        pendingPassword = password
        pendingNonce?.let { nonce ->
            val hash = PasswordHasher.hash(password, nonce)
            scope.launch {
                gameSync.broadcast(PasswordMessage(passwordHash = hash))
            }
            pendingNonce = null
        }
    }

    fun endGame() {
        isEndingGame = true
        sessionActive = false
        setDiscoveryResponder(false)   // stop answering probes for a game that's ending
        answeringProbes = false
        authTimeouts.values.forEach { it.cancel() }
        authTimeouts.clear()
        authenticatedClients.clear()
        scope.launch {
            gameSync.broadcast(EndGameMessage())
            // Give the OS TCP send buffers a moment to drain before tearing the session
            // down, so the EndGameMessage isn't lost in flight and clients aren't left
            // stuck in reconnect.
            delay(END_GAME_DRAIN_MS)
            gameSync.reconnectionManager.stopReconnecting()
            player = null
            lastHost = null
            lastPort = null
            _passwordVerified.postValue(true) // clear any stale rejection so it can't re-toast on recreation
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            onSessionEnded(false)
        }
    }

    fun retryConnection() {
        val host = lastHost
        val port = lastPort
        if (host != null && port != null) {
            gameSync.reconnectionManager.startReconnecting(host, port)
        }
    }

    private fun observeReconnectionState() {
        scope.launch {
            gameSync.reconnectionManager.state.collectLatest { state ->
                when (state) {
                    is ReconnectionManager.ReconnectionState.Reconnecting ->
                        _connectionState.postValue(ConnectionStatus.RECONNECTING)
                    is ReconnectionManager.ReconnectionState.Failed -> {
                        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                        postUiError(UiError.Critical("Connection lost", "Retry") {
                            retryConnection()
                        })
                    }
                    else -> {}
                }
            }
        }
    }

    companion object {
        private const val TAG = "GameNet"

        const val END_GAME_DRAIN_MS = 500L

        // Password hard-gate timings.
        const val AUTH_TIMEOUT_MS = 15_000L      // GM kicks a client that hasn't authed in this window
        const val AUTH_FAIL_DRAIN_MS = 300L      // let the rejection frame flush before closing the socket
    }
}
