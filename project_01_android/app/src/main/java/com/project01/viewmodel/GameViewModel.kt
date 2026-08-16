package com.project01.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import android.net.wifi.p2p.WifiP2pDevice
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameViewModel(application: Application, val repository: GameRepository = GameRepository(application)) : AndroidViewModel(application) {

    val players: LiveData<List<Player>> = repository.players
    val videos: LiveData<List<Video>> = repository.videos
    val isGameStarted: LiveData<Boolean> = repository.isGameStarted
    val toastMessage: LiveData<String> = repository.toastMessage
    val fileTransferEvent: LiveData<FileTransferEvent> = repository.fileTransferEvent
    private val _uiError = MutableLiveData<UiError>()
    val uiError: LiveData<UiError> = _uiError

    private val _advancedCommand = MutableLiveData<AdvancedCommand>()
    val advancedCommand: LiveData<AdvancedCommand> = _advancedCommand

    /** Start screen shows the prepared-games editor (GM only, ahead of time) vs the plain
     *  player-facing start screen. Drives MainActivity.updateUi alongside isGameStarted. */
    private val _prepareMode = MutableLiveData(false)
    val prepareMode: LiveData<Boolean> = _prepareMode

    val playbackController: PlaybackController = PlaybackController(
        gameSync = repository.gameSync,
        scope = viewModelScope,
        isGameMaster = { isGameMaster() },
    )

    val fileTransferOrchestrator: FileTransferOrchestrator = FileTransferOrchestrator(
        gameSync = repository.gameSync,
        fileTransfer = repository.fileTransfer,
        filesDir = application.filesDir,
        contentResolver = application.contentResolver,
        scope = viewModelScope,
        isGameMaster = { isGameMaster() },
        findFreePort = { repository.findFreePort() },
        // Read the synchronous mirror, not videos.value — the swap does a
        // read-modify-write per received file and rapid Success events must each
        // see the previous swap (videos.value lags a postValue). See currentVideos.
        videosProvider = { repository.currentVideos },
        updateVideos = { repository.restoreVideos(it) },
    )

    val sessionController: SessionController = SessionController(
        gameSync = repository.gameSync,
        playbackController = playbackController,
        scope = viewModelScope,
        videosProvider = { videos.value },
        isWifiEnabled = { repository.isWifiEnabled() },
        openWifiSettings = { repository.openWifiSettings() },
        discoverHost = { repository.discoverHost() },
        setDiscoveryResponder = { running -> repository.setDiscoveryResponder(running) },
        resolveHostAddress = { repository.resolveHostAddress() },
        setGameNetworkBound = { bound -> repository.setGameNetworkBound(bound) },
        postUiError = { _uiError.postValue(it) },
        onSessionStarted = { isHost -> onSessionStarted(isHost) },
        onSessionEnded = { remoteInitiated -> onSessionEnded(remoteInitiated) },
        onClientAuthenticated = { address -> addConnectedPlayer(address) },
    )

    val connectionState: LiveData<ConnectionStatus> = sessionController.connectionState
    val passwordVerified: LiveData<Boolean> = sessionController.passwordVerified

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var periodicSyncJob: Job? = null
    private var periodicStatusJob: Job? = null

    private val gameSyncEventObserver = Observer<NetworkEvent> { event ->
        handleGameSyncEvent(event)
    }

    private val _requestEnableBluetooth = MutableLiveData<Boolean>()
    val requestEnableBluetooth: LiveData<Boolean> = _requestEnableBluetooth

    init {
        repository.gameSyncEvent.observeForever(gameSyncEventObserver)
        // Drive the player-side content:// → file:// swap from EVERY FileTransfer
        // Success. This bypasses the coalescing fileTransferEvent LiveData (which
        // dropped Success events during multi-video pulls, leaving all but the first
        // video unplayable on players — field bug). See GameRepository.onFileReceived.
        repository.onFileReceived = { fileName -> onFileTransferSuccess(fileName) }
        initializeBluetooth()
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

    /** Wired into SessionController via the onSessionStarted callback. */
    private fun onSessionStarted(isHost: Boolean) {
        repository.setGameStarted(true)
        startPeriodicSnapshots()
        if (isHost) startPeriodicPlaybackSync() else startPeriodicStatusBroadcast()
    }

    /** Wired into SessionController via the onSessionEnded callback. */
    private fun onSessionEnded(remoteInitiated: Boolean) {
        periodicSyncJob?.cancel()
        periodicStatusJob?.cancel()
        repository.snapshotManager.clearSnapshot()
        repository.updatePlayers(emptyList())
        repository.setGameStarted(false)
        playbackController.reset()
        if (remoteInitiated) {
            // Remote end (GM kicked us): wipe local session-specific state so the
            // lobby starts clean. GM-initiated end keeps the playlist so the host
            // can immediately restart.
            fileTransferOrchestrator.clearReceivedFiles()
            repository.restoreVideos(emptyList())
        }
    }

    private fun startPeriodicPlaybackSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            while (isActive) {
                delay(PlaybackController.PLAYBACK_SYNC_INTERVAL_MS)
                playbackController.broadcastDriftSync()
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
                    is FileTransferRequest -> fileTransferOrchestrator.handleFileTransferRequest(data, address)
                    is PlaybackCommand -> playbackController.applyFromWire(data)
                    is PlaybackState -> playbackController.applyDriftCorrection(data)
                    is AdvancedCommand -> _advancedCommand.postValue(data)
                    is PasswordChallenge -> sessionController.handlePasswordChallenge(data)
                    is PasswordMessage -> sessionController.handlePasswordMessage(data, address)
                    is PasswordResponseMessage -> sessionController.handlePasswordResponseMessage(data)
                    is GameStateSnapshot -> repository.snapshotManager.saveSnapshot(data)
                    is EndGameMessage -> sessionController.handleEndGame()
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
                // Roster no longer gains a player here — a connected socket is not yet an
                // authenticated player. The GM adds the roster entry on auth success via
                // the onClientAuthenticated callback below.
                sessionController.handleClientConnected(event.address)
            }
            is NetworkEvent.ClientDisconnected -> {
                val shouldHandleRoster = sessionController.handleClientDisconnected(event.address)
                if (shouldHandleRoster) {
                    removeConnectedPlayer(event.address)
                    _uiError.postValue(UiError.Informational("Client disconnected: ${event.address}"))
                }
            }
        }
    }

    private fun addConnectedPlayer(address: String) {
        // repository.currentPlayers, never players.value: these are read-modify-writes and the
        // LiveData lags a postValue, so two players authenticating close together would each
        // build on the same stale list and the second would erase the first.
        val currentPlayers = repository.currentPlayers.toMutableList()
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
        val currentPlayers = repository.currentPlayers.toMutableList()
        if (currentPlayers.removeAll { it.device.deviceAddress == address }) {
            repository.updatePlayers(currentPlayers)
        }
    }

    private fun handlePlayerName(name: String, address: String) {
        if (isGameMaster()) {
            val currentPlayers = repository.currentPlayers.toMutableList()
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
            val currentPlayers = repository.currentPlayers.toMutableList()
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
        fileTransferOrchestrator.onFileTransferSuccess(fileName)
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
                            receivedVideos = fileTransferOrchestrator.receivedVideoFiles.toList()
                        )
                    )
                }
            }
        }
    }

    private fun handleVideoList(newVideos: List<Video>, senderAddress: String) {
        if (isGameMaster()) {
            repository.restoreVideos(newVideos)
            return
        }
        val resolved = fileTransferOrchestrator.resolveAndRequestMissing(newVideos, senderAddress)
        repository.restoreVideos(resolved)
    }

    // --- Session lifecycle (delegated to SessionController) ---

    fun createGame(password: String) = sessionController.createGame(password)

    fun joinGame(name: String, password: String) = sessionController.joinGame(name, password)

    /**
     * Player join: stash the (auto-named) credentials, then dial the game master on the
     * hotspot this device is already connected to. The password hard gate then decides
     * whether the session actually starts.
     */
    fun join(password: String) {
        sessionController.joinGame(autoPlayerName(), password)
        sessionController.connectToHost()
    }

    private fun autoPlayerName(): String =
        android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Player"

    /**
     * GM CREATE (undercover): start the prepared game whose password matches, and only that.
     *
     * A password that matches nothing starts nothing. This previously fell through to
     * "start anyway with whatever playlist was last loaded", on the theory that an onlooker
     * shouldn't be able to tell a right password from a wrong one — but in the field a
     * mistyped password launched the most recent game, which is both wrong content and a
     * false success. Doing nothing is equally indistinguishable from the outside (no visible
     * change either way) while the game master sees the screen stay put and knows to retry.
     */
    fun createGameForPassword(enteredPassword: String) {
        val match = repository.preparedGameStore.findByPassword(enteredPassword)
        if (match == null) {
            Log.w(TAG, "no prepared game matches that password — not starting a session")
            return
        }
        repository.restoreVideos(match.videos.map { it.toVideo() })
        sessionController.createGame(match.password)
    }

    fun endGame() = sessionController.endGame()

    fun retryConnection() = sessionController.retryConnection()

    fun isGameMaster(): Boolean = sessionController.isGameMaster()

    // --- Prepared games (GM prepares playlist+password pairs in advance) ---

    fun listPreparedGames(): List<String> = repository.preparedGameStore.listNames()

    fun getPreparedGame(name: String): PreparedGame? = repository.preparedGameStore.load(name)

    fun prepareGame(name: String, password: String, videos: List<Video>) {
        repository.preparedGameStore.save(PreparedGame(name, password, videos.map { it.toDto() }))
    }

    fun deletePreparedGame(name: String) = repository.preparedGameStore.delete(name)

    /** Load a prepared game's playlist into the editor (in-memory only; no broadcast). */
    fun loadPreparedGameIntoEditor(name: String): PreparedGame? {
        val game = repository.preparedGameStore.load(name) ?: return null
        repository.restoreVideos(game.videos.map { it.toVideo() })
        return game
    }

    /** Clear the editor to start a brand-new prepared game (in-memory only). */
    fun beginNewPreparedGame() {
        repository.restoreVideos(emptyList())
    }

    /**
     * True if the in-editor state (the given [password] plus the current playlist) differs
     * from the stored prepared game [name] — i.e. there are unsaved changes. A brand-new
     * game with no password and no videos is considered "nothing to save" (returns false).
     */
    fun preparedGameHasUnsavedChanges(name: String, password: String): Boolean {
        val saved = repository.preparedGameStore.load(name)
        val currentVideos: List<VideoDto> = (videos.value ?: emptyList()).map { it.toDto() }
        val savedVideos: List<VideoDto> = saved?.videos ?: emptyList()
        return password != (saved?.password ?: "") || currentVideos != savedVideos
    }

    fun setPrepareMode(on: Boolean) { _prepareMode.value = on }

    /**
     * Build the on-device diagnostics report. On the game master this reports whether players
     * can reach *it*; on a player, whether it can find the host.
     */
    suspend fun collectDiagnostics(): DiagnosticsReport {
        val videos = videos.value ?: emptyList()
        val local = videos.count { it.uri.scheme == "file" }
        return repository.collectDiagnostics(
            role = if (isGameMaster()) "game master" else "player",
            connectionState = connectionState.value?.name ?: "none",
            playlistSummary = "${videos.size} video(s), $local on this device",
            hosting = sessionController.hostingState(),
            players = repository.currentPlayers.map { player ->
                "${player.name} — ${player.readyVideoCount}/${player.totalVideoCount} videos" +
                    if (player.batteryLevel >= 0) ", battery ${player.batteryLevel}%" else ""
            },
        )
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
        val videoIndex = videos.value?.indexOf(video) ?: return
        if (videoIndex < 0) return
        playbackController.play(videoIndex)
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
        val current = playbackController.currentIntent()
        return GameStateSnapshot(
            videoList = videoList,
            currentVideoIndex = current.videoIndex,
            playbackPosition = playbackController.observedPosition(),
            isPlaying = current.isPlaying,
            playerAddresses = repository.currentPlayers.map { it.device.deviceAddress },
            // Informational only (snapshot provenance). There is no framework-supplied
            // device identity now that Wi-Fi Direct is gone.
            gameMasterAddress = "",
            timestamp = System.currentTimeMillis()
        )
    }

    fun loadSnapshot(): GameStateSnapshot? = repository.snapshotManager.loadSnapshot()

    fun clearSnapshot() = repository.snapshotManager.clearSnapshot()

    fun restoreFromSnapshot(snapshot: GameStateSnapshot) {
        val restoredVideos = snapshot.videoList.map { it.toVideo() }
        repository.restoreVideos(restoredVideos)
        playbackController.applyFromSnapshot(
            snapshot.currentVideoIndex,
            snapshot.playbackPosition,
            snapshot.isPlaying,
        )
    }

    companion object {
        private const val TAG = "GameNet"
        const val STATUS_BROADCAST_INTERVAL_MS = 10_000L

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return GameViewModel(application) as T
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        periodicSyncJob?.cancel()
        periodicStatusJob?.cancel()
        repository.snapshotManager.stopPeriodicSnapshots()
        repository.gameSyncEvent.removeObserver(gameSyncEventObserver)
        repository.shutdown()
    }
}
