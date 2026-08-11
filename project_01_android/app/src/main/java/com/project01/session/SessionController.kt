package com.project01.session

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the session lifecycle (createGame / joinGame / endGame), the password
 * challenge-response handshake, and the Wi-Fi P2P connection-info plumbing.
 * GameViewModel reduces to a facade that exposes LiveData and wires
 * cross-concern callbacks (start/stop periodic jobs, roster updates).
 *
 * The role bit (`player.isGameMaster`) lives here because it's set on
 * `handleConnectionInfo` and cleared on game end. Other controllers
 * (PlaybackController, FileTransferOrchestrator) receive an `isGameMaster()`
 * lambda that delegates back into this class through GameViewModel.
 */
class SessionController(
    private val gameSync: GameSync,
    private val playbackController: PlaybackController,
    private val wifiP2pManager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val scope: CoroutineScope,
    private val thisDeviceProvider: () -> WifiP2pDevice?,
    private val videosProvider: () -> List<Video>?,
    private val isWifiEnabled: () -> Boolean,
    private val openWifiSettings: () -> Unit,
    private val postUiError: (UiError) -> Unit,
    private val onSessionStarted: (isHost: Boolean) -> Unit,
    private val onSessionEnded: (remoteInitiated: Boolean) -> Unit,
    private val onClientAuthenticated: (address: String) -> Unit = {},
    // Location must be ON for Wi-Fi Direct scanning on API < 33 (permission alone isn't
    // enough); the provider returns true when it isn't required. Defaults keep tests simple.
    private val isLocationEnabled: () -> Boolean = { true },
    private val openLocationSettings: () -> Unit = {},
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

    // --- DNS-SD auto-join (client side) ---
    private var discoveryJob: Job? = null
    private var hasInitiatedConnect = false
    private var dnsSdListenersSet = false
    private val triedHostAddresses = mutableSetOf<String>()
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    // Temporary field-diagnostic flags (one-shot on-screen toasts; logs are the full story).
    private var diagServiceSeen = false
    private var diagLastFailure = Int.MIN_VALUE

    init {
        observeReconnectionState()
    }

    fun isGameMaster(): Boolean = player?.isGameMaster ?: false

    fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        isEndingGame = false
        if (info.isGroupOwner) {
            player = thisDeviceProvider()?.let { Player(it, it.deviceName, true) }
            gameSync.startServer()
            _connectionState.postValue(ConnectionStatus.HOST)
            onSessionStarted(true)
        } else {
            player = thisDeviceProvider()?.let { Player(it, it.deviceName, false) }
            lastHost = info.groupOwnerAddress.hostAddress
            lastPort = gameSync.port
            gameSync.connectTo(lastHost!!, lastPort!!)
            _connectionState.postValue(ConnectionStatus.CONNECTED)
            // Group formation means "socket connecting / authenticating", NOT "game
            // started". The client session (isGameStarted) is gated on password success
            // in handlePasswordResponseMessage — see the password hard gate.
        }
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

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before joining
    fun handlePasswordResponseMessage(message: PasswordResponseMessage) {
        _passwordVerified.postValue(message.success)
        if (message.success) {
            // The password hard gate: the client game UI (isGameStarted) starts ONLY
            // here, after verification — never on bare group formation. Idempotent on
            // reconnect re-auth.
            pendingAuthRejected = false
            stopServiceDiscovery()
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
            gameSync.reconnectionManager.stopReconnecting()
            lastHost = null
            lastPort = null
            try { wifiP2pManager.removeGroup(channel, null) } catch (_: Exception) {}
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
        gameSync.reconnectionManager.stopReconnecting()
        stopServiceDiscovery()
        hasInitiatedConnect = false
        pendingAuthRejected = false
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

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun createGame(password: String) {
        this.gamePassword = password
        if (!isWifiEnabled()) {
            postWifiOffError()
            return
        }
        _connectionState.postValue(ConnectionStatus.CONNECTING)
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Group formed → advertise the DNS-SD service so joiners auto-discover us.
                // (The connection also arrives via connectionInfoListener in the repository.)
                Log.d(TAG, "createGroup: onSuccess")
                advertiseGameService()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup: onFailure ${reasonName(reason)}")
                _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                val message = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try turning Wi-Fi off and on."
                    WifiP2pManager.ERROR -> "Wi-Fi Direct error. Try turning Wi-Fi off and on."
                    else -> "Game creation failed (error $reason)"
                }
                postUiError(UiError.Recoverable(message, "Retry") { createGame(password) })
            }
        }
        // Force the group onto the 2.4 GHz band (API 29+). Older player phones (e.g. A20e)
        // often can't discover/join a Wi-Fi Direct group the newer GM created on 5 GHz —
        // 2.4 GHz maximizes cross-device compatibility. Falls back to the default band if the
        // config can't be built (older API, or a device that rejects a band config).
        val config = if (Build.VERSION.SDK_INT >= 29) {
            try {
                WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "2.4GHz group config unsupported, using default band", e)
                null
            }
        } else null
        if (config != null) {
            Log.d(TAG, "createGroup: forcing 2.4GHz band")
            wifiP2pManager.createGroup(channel, config, listener)
        } else {
            Log.d(TAG, "createGroup: default band")
            wifiP2pManager.createGroup(channel, listener)
        }
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

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun connectToPlayer(player: Player) {
        if (!isWifiEnabled()) {
            postWifiOffError()
            return
        }
        // A joining device must never win Wi-Fi Direct group ownership. The GM's
        // autonomous group is the sole owner, and the game-master role is read
        // straight off info.isGroupOwner (handleConnectionInfo). If a joiner ends
        // up as group owner it inherits isGameMaster() == true — which exposes the
        // GM overlay and makes its ExoPlayer listener re-broadcast playback intent,
        // fighting the real GM. Both are field-reported bugs and share this one
        // cause. Guard in two places, because groupOwnerIntent alone isn't enough:
        //   1. Tear down any stale local group first — groupOwnerIntent is ignored
        //      while this device already owns an autonomous group (e.g. a device
        //      that was GM in a prior session and is now joining).
        //   2. Connect with groupOwnerIntent = 0 so GO negotiation always yields
        //      the client role for us.
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { doConnectAsClient(player) }       // stale group cleared
            override fun onFailure(reason: Int) { doConnectAsClient(player) } // nothing to clear
        })
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    private fun doConnectAsClient(player: Player) {
        val config = WifiP2pConfig().apply {
            deviceAddress = player.device.deviceAddress
            groupOwnerIntent = 0 // 0 = "never group owner"; keeps this device a client
        }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Handled by connectionInfoListener in repository
            }
            override fun onFailure(reason: Int) {
                postUiError(UiError.Recoverable("Connection failed. Try again."))
            }
        })
    }

    // --- DNS-SD service advertising (GM) ---

    /**
     * Advertise the game as a Wi-Fi Direct local service so joiners auto-discover it.
     * The TXT record deliberately excludes the password (players learn it out-of-app);
     * `proto` lets a joiner reject a version-mismatched host. Retries on BUSY.
     */
    @SuppressLint("MissingPermission") // Permission checked in MainActivity before creating a game
    private fun advertiseGameService(attempt: Int = 0) {
        val record = mapOf(
            "proto" to MessageEnvelope.PROTOCOL_VERSION.toString(),
            "port" to gameSync.port.toString(),
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(SERVICE_INSTANCE, SERVICE_TYPE, record)
        Log.d(TAG, "advertiseGameService: adding local service (attempt $attempt, port=${gameSync.port})")
        wifiP2pManager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "advertiseGameService: onSuccess — service is advertised") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "advertiseGameService: onFailure ${reasonName(reason)}")
                if (reason == WifiP2pManager.BUSY && attempt < 3) {
                    scope.launch { delay(1000); advertiseGameService(attempt + 1) }
                } else {
                    postUiError(UiError.Recoverable("Could not advertise the game. Retry.", "Retry") {
                        advertiseGameService()
                    })
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun clearGameService() {
        try { wifiP2pManager.clearLocalServices(channel, null) } catch (_: Exception) {}
    }

    // --- DNS-SD auto-join (client) ---

    /**
     * Discover the single game host and auto-connect — no device-list picking. Retries
     * every DISCOVERY_RETRY_MS (handles "host not advertising yet" and BUSY with many
     * devices) until a host is found or DISCOVERY_OVERALL_TIMEOUT_MS elapses. Called by
     * the ViewModel's join() wrapper (kept separate from joinGame so controller tests
     * don't touch Android DNS-SD APIs).
     */
    @SuppressLint("MissingPermission") // Permission checked in MainActivity before joining
    fun startServiceDiscovery() {
        // On API < 33 Wi-Fi Direct scanning silently returns nothing unless the system
        // Location toggle is on (the permission alone isn't enough). Fail fast with a clear
        // message instead of a 60s dead wait. (Provider returns true when not required.)
        if (!isLocationEnabled()) {
            Log.w(TAG, "startServiceDiscovery: Location is OFF — P2P discovery cannot work on this device")
            postUiError(UiError.Recoverable(
                "Turn on Location to find the game.", "Location settings") { openLocationSettings() })
            return
        }
        hasInitiatedConnect = false
        triedHostAddresses.clear()
        diagServiceSeen = false
        diagLastFailure = Int.MIN_VALUE
        setupDnsSdListeners()
        // Fresh service request — some OEM stacks silently drop a stale one.
        try { wifiP2pManager.clearServiceRequests(channel, null) } catch (_: Exception) {}
        if (serviceRequest == null) {
            // Untyped request (all DNS-SD services) — a type-filtered request is unreliable
            // on some stacks. We filter by instance name in onHostFound instead.
            serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        }
        wifiP2pManager.addServiceRequest(channel, serviceRequest, loggingListener("addServiceRequest"))
        Log.d(TAG, "startServiceDiscovery: begin (type=$SERVICE_TYPE)")
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val started = System.currentTimeMillis()
            var cycle = 0
            while (isActive && !hasInitiatedConnect) {
                cycle++
                Log.d(TAG, "discovery cycle $cycle")
                // Keep an active peer scan running: many OEM Wi-Fi Direct stacks (older
                // Samsung especially) only deliver DNS-SD service responses while
                // discoverPeers is in progress — discoverServices alone finds nothing.
                // This is the key cross-device fix.
                discoverPeersOnce()
                discoverServicesOnce()
                logPeers()   // diagnostic: does this device even see the GM as a peer?
                delay(DISCOVERY_RETRY_MS)
                if (System.currentTimeMillis() - started > DISCOVERY_OVERALL_TIMEOUT_MS) {
                    Log.w(TAG, "discovery timed out (${DISCOVERY_OVERALL_TIMEOUT_MS}ms), no host found")
                    postUiError(UiError.Recoverable(
                        "Couldn't find a game nearby. Make sure Wi-Fi and Location are on and you're near the host, then retry.",
                        "Retry") { startServiceDiscovery() })
                    stopServiceDiscovery()
                    return@launch
                }
            }
        }
    }

    private fun setupDnsSdListeners() {
        if (dnsSdListenersSet) return
        dnsSdListenersSet = true
        wifiP2pManager.setDnsSdResponseListeners(
            channel,
            WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
                Log.d(TAG, "DNS-SD response: instance=$instanceName type=$registrationType from=${srcDevice.deviceName}/${srcDevice.deviceAddress}")
                if (!diagServiceSeen) {
                    diagServiceSeen = true
                    postUiError(UiError.Informational("Saw service: $instanceName"))
                }
                onHostFound(instanceName, srcDevice)
            },
            WifiP2pManager.DnsSdTxtRecordListener { fullDomain, txt, srcDevice ->
                Log.d(TAG, "DNS-SD txt: domain=$fullDomain record=$txt from=${srcDevice.deviceAddress}")
            },
        )
    }

    private fun onHostFound(instanceName: String, srcDevice: WifiP2pDevice) {
        if (instanceName != SERVICE_INSTANCE) {
            Log.d(TAG, "onHostFound: ignoring '$instanceName' (want '$SERVICE_INSTANCE')")
            return
        }
        if (hasInitiatedConnect || srcDevice.deviceAddress in triedHostAddresses) return
        Log.d(TAG, "onHostFound: connecting to ${srcDevice.deviceName}/${srcDevice.deviceAddress}")
        hasInitiatedConnect = true
        triedHostAddresses += srcDevice.deviceAddress
        stopServiceDiscovery()
        // Reuse the client-connect path (removeGroup → groupOwnerIntent=0), preserving the
        // sole-GM invariant exactly as the old device-row tap did.
        connectToPlayer(Player(srcDevice, srcDevice.deviceName, false))
    }

    @SuppressLint("MissingPermission")
    fun stopServiceDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        serviceRequest?.let { req ->
            try { wifiP2pManager.removeServiceRequest(channel, req, null) } catch (_: Exception) {}
        }
        try { wifiP2pManager.stopPeerDiscovery(channel, null) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeersOnce() {
        // We don't consume the peer list for connecting (roster comes from socket auth);
        // this only keeps the P2P scan active so DNS-SD responses are delivered.
        wifiP2pManager.discoverPeers(channel, loggingListener("discoverPeers"))
    }

    /** Diagnostic only: log the peers this device currently sees. If the GM (an autonomous
     *  group owner) shows up here but no DNS-SD response arrives, the service advertising /
     *  band is the problem, not visibility. */
    @SuppressLint("MissingPermission")
    private fun logPeers() {
        wifiP2pManager.requestPeers(channel) { peers ->
            val list = peers.deviceList
            Log.d(TAG, "peers seen: ${list.size} -> " +
                list.joinToString { "${it.deviceName}/${it.deviceAddress} GO=${it.isGroupOwner} status=${it.status}" })
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesOnce() {
        wifiP2pManager.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "discoverServices: onSuccess") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverServices: onFailure ${reasonName(reason)}")
                if (reason != diagLastFailure) {
                    diagLastFailure = reason
                    postUiError(UiError.Informational("Scan issue: ${reasonName(reason)}"))
                }
                if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) {
                    // The framework dropped our service request — re-add it for the next cycle.
                    try {
                        wifiP2pManager.addServiceRequest(channel, serviceRequest, loggingListener("re-addServiceRequest"))
                    } catch (_: Exception) {}
                }
                // BUSY/ERROR: the retry loop tries again after DISCOVERY_RETRY_MS.
            }
        })
    }

    /** ActionListener that just logs — for the fire-and-forget P2P discovery calls. */
    private fun loggingListener(op: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { Log.d(TAG, "$op: onSuccess") }
        override fun onFailure(reason: Int) { Log.w(TAG, "$op: onFailure ${reasonName(reason)}") }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR(0)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED(1)"
        WifiP2pManager.BUSY -> "BUSY(2)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS(3)"
        else -> "reason=$reason"
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun endGame() {
        isEndingGame = true
        // Stop advertising immediately so no new joiner discovers a dying game.
        clearGameService()
        authTimeouts.values.forEach { it.cancel() }
        authTimeouts.clear()
        authenticatedClients.clear()
        scope.launch {
            gameSync.broadcast(EndGameMessage())
            // Give the OS TCP send buffers a moment to drain before tearing down the
            // P2P link — otherwise removeGroup() can kill the interface while the
            // EndGameMessage is still in flight, leaving clients stuck in reconnect.
            delay(END_GAME_DRAIN_MS)
            gameSync.reconnectionManager.stopReconnecting()
            player = null
            lastHost = null
            lastPort = null
            _passwordVerified.postValue(true) // clear any stale rejection so it can't re-toast on recreation
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            onSessionEnded(false)
            try {
                wifiP2pManager.removeGroup(channel, null)
            } catch (_: Exception) {}
        }
    }

    fun retryConnection() {
        val host = lastHost
        val port = lastPort
        if (host != null && port != null) {
            gameSync.reconnectionManager.startReconnecting(host, port)
        }
    }

    fun postWifiOffError() {
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        postUiError(UiError.Recoverable("Wi-Fi is off. Turn it on to start a game.", "Open Wi-Fi") {
            openWifiSettings()
        })
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
        /** Logcat tag for the temporary join/discovery diagnostics: `adb logcat -s P2pJoin`. */
        private const val TAG = "P2pJoin"

        const val END_GAME_DRAIN_MS = 500L

        // DNS-SD service identity the GM advertises and joiners look for.
        const val SERVICE_INSTANCE = "project01"
        const val SERVICE_TYPE = "_project01game._tcp"

        // Password hard-gate timings.
        const val AUTH_TIMEOUT_MS = 15_000L      // GM kicks a client that hasn't authed in this window
        const val AUTH_FAIL_DRAIN_MS = 300L      // let the rejection frame flush before closing the socket

        // Auto-join discovery timings.
        const val DISCOVERY_RETRY_MS = 5_000L
        const val DISCOVERY_OVERALL_TIMEOUT_MS = 60_000L
    }
}
