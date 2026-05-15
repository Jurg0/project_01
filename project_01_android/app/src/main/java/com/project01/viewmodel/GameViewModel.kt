package com.project01.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.project01.session.*
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class GameViewModel(application: Application, val repository: GameRepository = GameRepository(application)) : AndroidViewModel(application) {

    val players: LiveData<List<Player>> = repository.players
    val videos: LiveData<List<Video>> = repository.videos
    val isGameStarted: LiveData<Boolean> = repository.isGameStarted
    val thisDevice: LiveData<WifiP2pDevice> = repository.thisDevice
    val toastMessage: LiveData<String> = repository.toastMessage
    val fileTransferEvent: LiveData<FileTransferEvent> = repository.fileTransferEvent
    private val _connectionState = MutableLiveData<ConnectionStatus>()
    val connectionState: LiveData<ConnectionStatus> = _connectionState
    private val _uiError = MutableLiveData<UiError>()
    val uiError: LiveData<UiError> = _uiError

    private val _showVideo = MutableLiveData<Boolean>()
    val showVideo: LiveData<Boolean> = _showVideo

    private val _playbackCommand = MutableLiveData<PlaybackCommand>()
    val playbackCommand: LiveData<PlaybackCommand> = _playbackCommand


    private val _advancedCommand = MutableLiveData<AdvancedCommand>()
    val advancedCommand: LiveData<AdvancedCommand> = _advancedCommand

    private var player: Player? = null
    private var lastHost: String? = null
    private var lastPort: Int? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var currentVideoIndex = 0
    private var currentPlaybackPosition = 0L
    private var currentIsPlaying = false
    private var periodicSyncJob: Job? = null
    private var periodicStatusJob: Job? = null
    private val receivedVideoFiles = mutableSetOf<String>()
    private var isEndingGame = false

    private val connectionInfoObserver = Observer<android.net.wifi.p2p.WifiP2pInfo> { info ->
        handleConnectionInfo(info)
    }
    private val gameSyncEventObserver = Observer<NetworkEvent> { event ->
        handleGameSyncEvent(event)
    }

    private val _requestEnableBluetooth = MutableLiveData<Boolean>()
    val requestEnableBluetooth: LiveData<Boolean> = _requestEnableBluetooth

    init {
        repository.gameSyncEvent.observeForever(gameSyncEventObserver)
        repository.connectionInfo.observeForever(connectionInfoObserver)
        initializeBluetooth()
        observeReconnectionState()
        restoreLastPlaylist()
    }

    private fun restoreLastPlaylist() {
        if (videos.value?.isNotEmpty() == true) return
        val saved = repository.playlistStore.loadPlaylist(PlaylistStore.LAST_USED_NAME)
        if (saved.isNullOrEmpty()) return
        repository.restoreVideos(saved)
    }

    // --- Named playlist API ---

    fun savePlaylistAs(name: String) {
        val current = videos.value ?: emptyList()
        repository.playlistStore.savePlaylist(name, current)
    }

    fun loadNamedPlaylist(name: String) {
        val loaded = repository.playlistStore.loadPlaylist(name) ?: return
        viewModelScope.launch {
            repository.restoreVideos(loaded)
            repository.playlistStore.savePlaylist(PlaylistStore.LAST_USED_NAME, loaded)
            if (isGameMaster()) {
                repository.gameSync.broadcast(VideoListMessage(loaded.map { it.toDto() }))
            }
        }
    }

    fun listSavedPlaylists(): List<String> = repository.playlistStore.listPlaylists()

    fun deleteSavedPlaylist(name: String) {
        repository.playlistStore.deletePlaylist(name)
    }

    private fun observeReconnectionState() {
        viewModelScope.launch {
            repository.gameSync.reconnectionManager.state.collectLatest { state ->
                when (state) {
                    is ReconnectionManager.ReconnectionState.Reconnecting ->
                        _connectionState.postValue(ConnectionStatus.RECONNECTING)
                    is ReconnectionManager.ReconnectionState.Failed -> {
                        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                        _uiError.postValue(UiError.Critical("Connection lost", "Retry") {
                            retryConnection()
                        })
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleConnectionInfo(info: android.net.wifi.p2p.WifiP2pInfo) {
        if (info.groupFormed) {
            isEndingGame = false
            repository.setGameStarted(true)
            if (info.isGroupOwner) {
                player = thisDevice.value?.let { Player(it, it.deviceName, true) }
                repository.gameSync.startServer()
                _connectionState.postValue(ConnectionStatus.HOST)
            } else {
                player = thisDevice.value?.let { Player(it, it.deviceName, false) }
                lastHost = info.groupOwnerAddress.hostAddress
                lastPort = repository.gameSync.port
                repository.gameSync.connectTo(lastHost!!, lastPort!!)
                _connectionState.postValue(ConnectionStatus.CONNECTED)
            }
            startPeriodicSnapshots()
            if (info.isGroupOwner) {
                startPeriodicPlaybackSync()
            } else {
                startPeriodicStatusBroadcast()
            }
        }
    }

    private fun startPeriodicPlaybackSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                delay(PLAYBACK_SYNC_INTERVAL_MS)
                if (isGameMaster() && currentIsPlaying) {
                    repository.gameSync.broadcast(
                        PlaybackState(currentVideoIndex, currentPlaybackPosition, currentIsPlaying)
                    )
                }
            }
        }
    }

    private fun initializeBluetooth() {
        // We don't connect to or scan for Bluetooth devices ourselves — Bluetooth presenters
        // pair via system settings and deliver HID key events directly to the foreground
        // activity. We just prompt the user to enable the radio so paired remotes work.
        val bluetoothManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter?.isEnabled == false) {
            _requestEnableBluetooth.postValue(true)
        }
    }

    private fun handleGameSyncEvent(event: NetworkEvent) {
        when (event) {
            is NetworkEvent.DataReceived -> {
                val (data, address) = event.data to event.sender
                when (data) {
                    is VideoListMessage -> handleVideoList(data.videos.map { it.toVideo() }, address)
                    is FileTransferRequest -> handleFileTransferRequest(data, address)
                    is PlaybackCommand -> _playbackCommand.postValue(data)
                    is PlaybackState -> applyPlaybackState(data)
                    is AdvancedCommand -> _advancedCommand.postValue(data)
                    is PasswordChallenge -> handlePasswordChallenge(data)
                    is PasswordMessage -> handlePasswordMessage(data, address)
                    is PasswordResponseMessage -> handlePasswordResponseMessage(data)
                    is GameStateSnapshot -> repository.snapshotManager.saveSnapshot(data)
                    is EndGameMessage -> handleEndGame()
                    is PlayerNameMessage -> handlePlayerName(data.playerName, address)
                    is PlayerStatusMessage -> handlePlayerStatus(data, address)
                    is HeartbeatMsg -> { /* filtered by SocketNetworkManager */ }
                }
            }
            is NetworkEvent.Error -> {
                val cls = event.exception.javaClass.simpleName
                val msg = event.exception.message?.takeIf { it.isNotBlank() } ?: "(no message)"
                _uiError.postValue(UiError.Recoverable("${event.origin}: $cls: $msg"))
            }
            is NetworkEvent.ClientConnected -> {
                if (!isGameMaster()) {
                    repository.gameSync.reconnectionManager.stopReconnecting()
                    _connectionState.postValue(ConnectionStatus.CONNECTED)
                } else {
                    addConnectedPlayer(event.address)
                }
            }
            is NetworkEvent.ClientDisconnected -> {
                if (isEndingGame) return
                if (!isGameMaster()) {
                    val host = lastHost
                    val port = lastPort
                    if (host != null && port != null) {
                        repository.gameSync.reconnectionManager.startReconnecting(host, port)
                    } else {
                        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                    }
                } else {
                    removeConnectedPlayer(event.address)
                    _uiError.postValue(UiError.Informational("Client disconnected: ${event.address}"))
                }
            }
        }
    }

    private fun handlePasswordChallenge(challenge: PasswordChallenge) {
        if (challenge.protocolVersion != MessageEnvelope.PROTOCOL_VERSION) {
            _uiError.postValue(UiError.Critical(
                "Incompatible app version (server: v${challenge.protocolVersion}, local: v${MessageEnvelope.PROTOCOL_VERSION}). Update all devices to the same version."
            ))
            return
        }
        pendingNonce = challenge.nonce
        pendingPassword?.let { password ->
            val hash = PasswordHasher.hash(password, challenge.nonce)
            viewModelScope.launch {
                repository.gameSync.broadcast(PasswordMessage(passwordHash = hash))
            }
            pendingNonce = null
        }
    }

    private fun handlePasswordMessage(message: PasswordMessage, senderAddress: String) {
        if (isGameMaster()) {
            val nonce = repository.gameSync.consumeNonce(senderAddress)
            val success = if (nonce != null) {
                val expectedHash = PasswordHasher.hash(gamePassword ?: "", nonce)
                message.passwordHash == expectedHash
            } else {
                false
            }
            viewModelScope.launch {
                repository.gameSync.broadcast(PasswordResponseMessage(success))
                if (success) {
                    pushInitialStateTo(senderAddress)
                }
            }
        }
    }

    /**
     * On a successful join, push the GM's playlist (and current playback state) to the
     * new client. Without this they keep their local lobby playlist and `videoIndex`
     * from GM's PlaybackState would seek into the wrong list.
     */
    private suspend fun pushInitialStateTo(address: String) {
        val currentVideos = videos.value.orEmpty()
        if (currentVideos.isNotEmpty()) {
            repository.gameSync.sendTo(
                address,
                VideoListMessage(currentVideos.map { it.toDto() })
            )
        }
        repository.gameSync.sendTo(
            address,
            PlaybackState(currentVideoIndex, currentPlaybackPosition, currentIsPlaying)
        )
    }

    private fun addConnectedPlayer(address: String) {
        val currentPlayers = players.value?.toMutableList() ?: mutableListOf()
        if (currentPlayers.none { it.device.deviceAddress == address }) {
            val device = WifiP2pDevice().apply {
                deviceAddress = address
                deviceName = address
            }
            currentPlayers.add(Player(device, address, false))
            repository.updatePlayers(currentPlayers)
        }
    }

    private fun removeConnectedPlayer(address: String) {
        val currentPlayers = players.value?.toMutableList() ?: return
        if (currentPlayers.removeAll { it.device.deviceAddress == address }) {
            repository.updatePlayers(currentPlayers)
        }
    }

    private fun handlePlayerName(name: String, address: String) {
        if (isGameMaster()) {
            val currentPlayers = players.value?.toMutableList() ?: return
            val index = currentPlayers.indexOfFirst { it.device.deviceAddress == address }
            if (index >= 0) {
                val existing = currentPlayers[index]
                currentPlayers[index] = Player(existing.device, name, existing.isGameMaster)
                repository.updatePlayers(currentPlayers)
            }
        }
    }

    private fun handlePlayerStatus(status: PlayerStatusMessage, address: String) {
        if (isGameMaster()) {
            val currentPlayers = players.value?.toMutableList() ?: return
            val totalVideos = videos.value?.size ?: 0
            val index = currentPlayers.indexOfFirst { it.device.deviceAddress == address }
            if (index >= 0) {
                val existing = currentPlayers[index]
                currentPlayers[index] = existing.copy(
                    batteryLevel = status.batteryLevel,
                    readyVideoCount = status.receivedVideos.size,
                    totalVideoCount = totalVideos
                )
                repository.updatePlayers(currentPlayers)
            }
        }
    }

    fun onFileTransferSuccess(fileName: String) {
        receivedVideoFiles.add(fileName)
        if (isGameMaster()) return
        // Swap the playlist entry from the GM's content:// URI to the local file
        // we just received, otherwise ExoPlayer can't read it on this device.
        val currentVideos = videos.value?.toMutableList() ?: return
        val localUri = Uri.fromFile(File(getApplication<Application>().filesDir, fileName))
        val index = currentVideos.indexOfFirst { it.title == fileName }
        if (index >= 0 && currentVideos[index].uri != localUri) {
            currentVideos[index] = Video(localUri, fileName)
            repository.restoreVideos(currentVideos)
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun startPeriodicStatusBroadcast() {
        periodicStatusJob?.cancel()
        periodicStatusJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                delay(STATUS_BROADCAST_INTERVAL_MS)
                if (!isGameMaster()) {
                    repository.gameSync.broadcast(
                        PlayerStatusMessage(
                            batteryLevel = getBatteryLevel(),
                            receivedVideos = receivedVideoFiles.toList()
                        )
                    )
                }
            }
        }
    }

    private fun handleEndGame() {
        // isEndingGame stays true until the next createGame/joinGame restores it via
        // handleConnectionInfo. Resetting it here races the ClientDisconnected event
        // that fires when the GM tears down the group, which would otherwise re-enter
        // the reconnect loop.
        isEndingGame = true
        repository.gameSync.reconnectionManager.stopReconnecting()
        periodicStatusJob?.cancel()
        receivedVideoFiles.clear()
        repository.snapshotManager.clearSnapshot()
        repository.restoreVideos(emptyList())
        repository.updatePlayers(emptyList())
        player = null
        lastHost = null
        lastPort = null
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        _uiError.postValue(UiError.Informational("Game ended by host"))
        repository.setGameStarted(false)
    }

    private fun handlePasswordResponseMessage(message: PasswordResponseMessage) {
        _passwordVerified.postValue(message.success)
        if (message.success) {
            localPlayerName?.let { name ->
                viewModelScope.launch {
                    repository.gameSync.broadcast(PlayerNameMessage(name))
                }
            }
        }
    }

    private fun handleVideoList(newVideos: List<Video>, senderAddress: String) {
        if (isGameMaster()) {
            repository.restoreVideos(newVideos)
            return
        }
        // For each title: if the file already lives in filesDir (transferred
        // earlier in this session, or left over from a prior session), use the
        // local URI directly. Otherwise ask the GM to send it. This skips the
        // redundant redownload that would otherwise happen every time the GM
        // re-broadcasts the playlist (e.g. on every new joiner via
        // pushInitialStateTo).
        val thisAddress = thisDevice.value?.deviceAddress
        val resolved = newVideos.map { video ->
            val localFile = File(getApplication<Application>().filesDir, video.title)
            if (localFile.exists()) {
                receivedVideoFiles.add(video.title)
                Video(Uri.fromFile(localFile), video.title)
            } else {
                if (thisAddress != null) {
                    requestFileTransfer(video.title, thisAddress, senderAddress)
                }
                video
            }
        }
        repository.restoreVideos(resolved)
    }

    private fun handleFileTransferRequest(request: FileTransferRequest, fromIp: String) {
        if (!isGameMaster()) return
        val video = videos.value?.find { it.title == request.fileName } ?: return
        viewModelScope.launch {
            repository.fileTransfer.sendFileWithRetry(
                fromIp,
                request.port,
                video.uri,
                getApplication<Application>().contentResolver
            )
        }
    }

    private var gamePassword: String? = null
    private var pendingPassword: String? = null
    private var pendingNonce: String? = null
    private var localPlayerName: String? = null
    private val _passwordVerified = MutableLiveData<Boolean>()
    val passwordVerified: LiveData<Boolean> = _passwordVerified

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun createGame(password: String) {
        this.gamePassword = password
        if (!repository.isWifiEnabled()) {
            postWifiOffError()
            return
        }
        _connectionState.postValue(ConnectionStatus.CONNECTING)
        repository.wifiP2pManager.createGroup(
            repository.channel,
            object : WifiP2pManager.ActionListener {
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
                    _uiError.postValue(UiError.Recoverable(message, "Retry") {
                        createGame(password)
                    })
                }
            })
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun discoverPeers() {
        if (!repository.isWifiEnabled()) {
            postWifiOffError()
            return
        }
        repository.wifiP2pManager.discoverPeers(
            repository.channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    repository.showToast("Discovery initiated")
                }

                override fun onFailure(reason: Int) {
                    _uiError.postValue(UiError.Recoverable("Peer discovery failed. Check Wi-Fi.", "Retry") {
                        discoverPeers()
                    })
                }
            })
    }

    private fun postWifiOffError() {
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        _uiError.postValue(UiError.Recoverable(
            "Wi-Fi is off. Turn it on to start a game.",
            "Open Wi-Fi"
        ) {
            repository.openWifiSettings()
        })
    }

    fun joinGame(name: String, password: String) {
        localPlayerName = name
        pendingPassword = password
        pendingNonce?.let { nonce ->
            val hash = PasswordHasher.hash(password, nonce)
            viewModelScope.launch {
                repository.gameSync.broadcast(PasswordMessage(passwordHash = hash))
            }
            pendingNonce = null
        }
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun connectToPlayer(player: Player) {
        if (!repository.isWifiEnabled()) {
            postWifiOffError()
            return
        }
        val config = WifiP2pConfig().apply {
            deviceAddress = player.device.deviceAddress
        }
        repository.wifiP2pManager.connect(
            repository.channel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Handled by connectionInfoListener in repository
                }

                override fun onFailure(reason: Int) {
                    _uiError.postValue(UiError.Recoverable("Connection failed. Try again."))
                }
            })
    }

    fun addVideo(uri: Uri) {
        viewModelScope.launch {
            val fileName = repository.getFileName(uri) ?: "Video ${videos.value?.size?.plus(1)}"
            val video = Video(uri, fileName)
            val currentVideos = videos.value?.toMutableList() ?: mutableListOf()
            currentVideos.add(video)
            applyLocalVideoChange(currentVideos)
        }
    }

    /** Apply a local (user-driven) playlist change: store in-memory, auto-save, broadcast. */
    private suspend fun applyLocalVideoChange(newList: List<Video>) {
        repository.restoreVideos(newList)
        repository.playlistStore.savePlaylist(PlaylistStore.LAST_USED_NAME, newList)
        repository.gameSync.broadcast(VideoListMessage(newList.map { it.toDto() }))
    }

    fun turnOffScreen() {
        viewModelScope.launch {
            val command = AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN)
            _advancedCommand.postValue(command)
            repository.gameSync.broadcast(command)
        }
    }

    fun turnOnScreen() {
        viewModelScope.launch {
            val command = AdvancedCommand(AdvancedCommandType.TURN_ON_SCREEN)
            _advancedCommand.postValue(command)
            repository.gameSync.broadcast(command)
        }
    }

    fun deactivateTorch() {
        viewModelScope.launch {
            val command = AdvancedCommand(AdvancedCommandType.DEACTIVATE_TORCH)
            _advancedCommand.postValue(command)
            repository.gameSync.broadcast(command)
        }
    }

    fun activateTorch() {
        viewModelScope.launch {
            val command = AdvancedCommand(AdvancedCommandType.ACTIVATE_TORCH)
            _advancedCommand.postValue(command)
            repository.gameSync.broadcast(command)
        }
    }

    fun setLights(on: Boolean) {
        viewModelScope.launch {
            val command = AdvancedCommand(
                if (on) AdvancedCommandType.LIGHTS_ON else AdvancedCommandType.LIGHTS_OFF
            )
            _advancedCommand.postValue(command)
            repository.gameSync.broadcast(command)
        }
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun endGame() {
        // isEndingGame stays true until the next createGame/joinGame restores it via
        // handleConnectionInfo. See handleEndGame() for rationale.
        isEndingGame = true
        viewModelScope.launch {
            repository.gameSync.broadcast(EndGameMessage())
            // Give the OS TCP send buffers a moment to drain before tearing down the
            // P2P link — otherwise removeGroup() can kill the interface while the
            // EndGameMessage is still in flight, leaving clients stuck in reconnect.
            delay(END_GAME_DRAIN_MS)
            repository.gameSync.reconnectionManager.stopReconnecting()
            periodicSyncJob?.cancel()
            periodicStatusJob?.cancel()
            repository.snapshotManager.clearSnapshot()
            repository.updatePlayers(emptyList())
            player = null
            lastHost = null
            lastPort = null
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            repository.setGameStarted(false)
            try {
                repository.wifiP2pManager.removeGroup(repository.channel, null)
            } catch (_: Exception) {}
        }
    }

    fun isGameMaster(): Boolean {
        return player?.isGameMaster ?: false
    }

    fun moveVideoUp(position: Int) {
        viewModelScope.launch {
            val currentVideos = videos.value?.toMutableList() ?: return@launch
            if (position in 1..currentVideos.lastIndex) {
                val video = currentVideos.removeAt(position)
                currentVideos.add(position - 1, video)
                applyLocalVideoChange(currentVideos)
            }
        }
    }

    fun moveVideoDown(position: Int) {
        viewModelScope.launch {
            val currentVideos = videos.value?.toMutableList() ?: return@launch
            if (position in 0 until currentVideos.lastIndex) {
                val video = currentVideos.removeAt(position)
                currentVideos.add(position + 1, video)
                applyLocalVideoChange(currentVideos)
            }
        }
    }

    fun removeVideo(position: Int) {
        viewModelScope.launch {
            val currentVideos = videos.value?.toMutableList() ?: return@launch
            if (position in currentVideos.indices) {
                currentVideos.removeAt(position)
                applyLocalVideoChange(currentVideos)
            }
        }
    }

    fun onVideoSelected(video: Video) {
        val videoIndex = videos.value?.indexOf(video)
        if (videoIndex != null) {
            _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex))
        }
    }

    /**
     * Called from the Player.Listener on isPlaying transitions. Records local
     * state; broadcasts only if the transition is **not** the immediate
     * after-effect of an explicit commandPlayback (which already sent the
     * authoritative command). Within the grace window the listener stays
     * quiet, so we don't double-broadcast on every Prev / Play-Next press.
     *
     * Broadcasts via PlaybackCommand (not PlaybackState) so the client's
     * drift filter can't drop the message — natural end-of-video pauses
     * propagate immediately.
     */
    fun broadcastPlaybackState(position: Long, isPlaying: Boolean, videoIndex: Int) {
        currentVideoIndex = videoIndex
        currentPlaybackPosition = position
        currentIsPlaying = isPlaying
        if (!isGameMaster()) return
        val sinceExplicit = System.currentTimeMillis() - lastCommandPlaybackAtMs
        if (sinceExplicit < COMMAND_GRACE_MS) return
        viewModelScope.launch {
            repository.gameSync.broadcast(
                PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex, position, isPlaying)
            )
        }
    }

    /**
     * Explicit GM playback action (Prev / Play / Next). Sends a PlaybackCommand
     * so the client seeks unconditionally — unlike PlaybackState, which the
     * client filters through a drift threshold and may silently drop when the
     * new position is close to its last-seen one.
     *
     * Also stamps `lastCommandPlaybackAtMs` so the Player.Listener (which
     * fires shortly after we set playWhenReady on ExoPlayer with mid-seek
     * state) doesn't race this authoritative broadcast on the wire.
     */
    fun commandPlayback(videoIndex: Int, position: Long, playWhenReady: Boolean) {
        currentVideoIndex = videoIndex
        currentPlaybackPosition = position
        currentIsPlaying = playWhenReady
        lastCommandPlaybackAtMs = System.currentTimeMillis()
        if (isGameMaster()) {
            viewModelScope.launch {
                repository.gameSync.broadcast(
                    PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex, position, playWhenReady)
                )
            }
        }
    }

    private var lastCommandPlaybackAtMs = 0L

    private fun applyPlaybackState(state: PlaybackState) {
        val drift = Math.abs(state.playbackPosition - currentPlaybackPosition)
        val videoChanged = state.videoIndex != currentVideoIndex
        val playingChanged = state.playWhenReady != currentIsPlaying
        currentVideoIndex = state.videoIndex
        currentPlaybackPosition = state.playbackPosition
        currentIsPlaying = state.playWhenReady
        // Only emit on transition — the GM rebroadcasts PlaybackState every 5s while
        // playing, and unconditional emission would fire the white flash effect on
        // every cycle (the "pulsing" the GM reported).
        if (playingChanged) {
            _showVideo.postValue(state.playWhenReady)
        }
        // Act on play/pause transitions even when drift is small, otherwise a
        // GM resume from a paused-on-blue state at the same position leaves the
        // client's surface visible but its ExoPlayer still paused.
        if (videoChanged || drift > PLAYBACK_DRIFT_THRESHOLD_MS || playingChanged) {
            _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, state.videoIndex, state.playbackPosition, state.playWhenReady))
        }
    }

    private fun requestFileTransfer(
        fileName: String,
        targetAddress: String,
        senderAddress: String
    ) {
        viewModelScope.launch {
            val port = repository.findFreePort()
            val outputFile = File(getApplication<Application>().filesDir, fileName)
            // Start the receive listener first so the ServerSocket is bound (or
            // about to bind) when the GM tries to connect back. sendFileWithRetry's
            // exponential backoff covers any residual race.
            launch {
                repository.fileTransfer.startReceivingWithRetry(port, outputFile)
            }
            repository.gameSync.broadcast(
                FileTransferRequest(fileName, port, senderAddress, targetAddress)
            )
        }
    }



    private fun startPeriodicSnapshots() {
        repository.snapshotManager.startPeriodicSnapshots(scope = viewModelScope) {
            val snapshot = buildSnapshot()
            if (snapshot != null && isGameMaster()) {
                repository.gameSync.broadcast(snapshot)
            }
            snapshot
        }
    }

    fun buildSnapshot(): GameStateSnapshot? {
        val videoList = videos.value?.map { it.toDto() } ?: return null
        return GameStateSnapshot(
            videoList = videoList,
            currentVideoIndex = currentVideoIndex,
            playbackPosition = currentPlaybackPosition,
            isPlaying = currentIsPlaying,
            playerAddresses = players.value?.map { it.device.deviceAddress } ?: emptyList(),
            gameMasterAddress = if (isGameMaster()) thisDevice.value?.deviceAddress ?: "" else "",
            timestamp = System.currentTimeMillis()
        )
    }

    fun loadSnapshot(): GameStateSnapshot? = repository.snapshotManager.loadSnapshot()

    fun clearSnapshot() = repository.snapshotManager.clearSnapshot()

    fun restoreFromSnapshot(snapshot: GameStateSnapshot) {
        val restoredVideos = snapshot.videoList.map { it.toVideo() }
        repository.restoreVideos(restoredVideos)
        currentVideoIndex = snapshot.currentVideoIndex
        currentPlaybackPosition = snapshot.playbackPosition
        currentIsPlaying = snapshot.isPlaying
        _playbackCommand.postValue(
            PlaybackCommand(
                PlaybackCommandType.PLAY_PAUSE,
                snapshot.currentVideoIndex,
                snapshot.playbackPosition,
                snapshot.isPlaying
            )
        )
    }

    companion object {
        const val PLAYBACK_SYNC_INTERVAL_MS = 5_000L
        const val PLAYBACK_DRIFT_THRESHOLD_MS = 2_000L
        const val STATUS_BROADCAST_INTERVAL_MS = 10_000L
        const val END_GAME_DRAIN_MS = 500L
        /** Window after an explicit commandPlayback during which listener-driven
         *  broadcasts stay quiet. Covers the Player.Listener fires triggered by
         *  the seek + playWhenReady set we just did. */
        const val COMMAND_GRACE_MS = 500L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return GameViewModel(application) as T
            }
        }
    }

    fun retryConnection() {
        val host = lastHost
        val port = lastPort
        if (host != null && port != null) {
            repository.gameSync.reconnectionManager.startReconnecting(host, port)
        }
    }

    override fun onCleared() {
        super.onCleared()
        periodicSyncJob?.cancel()
        periodicStatusJob?.cancel()
        repository.snapshotManager.stopPeriodicSnapshots()
        repository.connectionInfo.removeObserver(connectionInfoObserver)
        repository.gameSyncEvent.removeObserver(gameSyncEventObserver)
        repository.shutdown()
    }
}



