package com.project01.session

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
            onSessionStarted(false)
        }
    }

    /** Called from GameViewModel's NetworkEvent.ClientConnected branch. */
    fun handleClientConnected() {
        if (isGameMaster()) return
        gameSync.reconnectionManager.stopReconnecting()
        _connectionState.postValue(ConnectionStatus.CONNECTED)
    }

    /**
     * Called from GameViewModel's NetworkEvent.ClientDisconnected branch.
     * Returns true if the caller should still perform roster cleanup
     * (game-master side) — false when SessionController handled the event
     * entirely (player-side reconnect attempt).
     */
    fun handleClientDisconnected(): Boolean {
        if (isEndingGame) return false
        if (isGameMaster()) return true
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
            gameSync.broadcast(PasswordResponseMessage(success))
            if (success) {
                pushInitialStateTo(senderAddress)
            }
        }
    }

    fun handlePasswordResponseMessage(message: PasswordResponseMessage) {
        _passwordVerified.postValue(message.success)
        if (message.success) {
            localPlayerName?.let { name ->
                scope.launch {
                    gameSync.broadcast(PlayerNameMessage(name))
                }
            }
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
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Handled by connectionInfoListener in repository
            }

            override fun onFailure(reason: Int) {
                _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                val message = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try turning Wi-Fi off and on."
                    WifiP2pManager.ERROR -> "Wi-Fi Direct error. Try turning Wi-Fi off and on."
                    else -> "Game creation failed (error $reason)"
                }
                postUiError(UiError.Recoverable(message, "Retry") { createGame(password) })
            }
        })
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
        val config = WifiP2pConfig().apply { deviceAddress = player.device.deviceAddress }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Handled by connectionInfoListener in repository
            }
            override fun onFailure(reason: Int) {
                postUiError(UiError.Recoverable("Connection failed. Try again."))
            }
        })
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun endGame() {
        isEndingGame = true
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
        const val END_GAME_DRAIN_MS = 500L
    }
}
