package com.project01.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.p2p.WifiDirectBroadcastReceiver
import com.project01.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.IOException

class GameRepository(
    private val application: Application,
    val gameSync: GameSync = GameSync(SocketNetworkManager()),
    val fileTransfer: FileTransfer = FileTransfer(),
    val snapshotManager: SnapshotManager = SnapshotManager(
        java.io.File(application.filesDir, "game_state_snapshot.json")
    ),
    val playlistStore: PlaylistStore = PlaylistStore(
        java.io.File(application.filesDir, "playlists")
    ),
    val preparedGameStore: PreparedGameStore = PreparedGameStore(
        java.io.File(application.filesDir, "prepared")
    ),
) {

    private val _players = MutableLiveData<List<Player>>()
    val players: LiveData<List<Player>> = _players

    private val _videos = MutableLiveData<List<Video>>()
    val videos: LiveData<List<Video>> = _videos

    // Synchronous mirror of the current playlist. LiveData.value lags a postValue
    // (postValue defers setValue to the main thread), so a burst of file-transfer
    // Success events that each read `videos.value`, swap one entry, and post back
    // would build on a stale list and clobber each other. The mirror is read/written
    // synchronously, keeping the per-file URI swap lossless. Updated in restoreVideos
    // alongside the LiveData.
    @Volatile
    private var videosMirror: List<Video> = emptyList()
    val currentVideos: List<Video> get() = videosMirror

    private val _isGameStarted = MutableLiveData<Boolean>()
    val isGameStarted: LiveData<Boolean> = _isGameStarted

    private val _thisDevice = MutableLiveData<WifiP2pDevice>()
    val thisDevice: LiveData<WifiP2pDevice> = _thisDevice

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _fileTransferEvent = MutableLiveData<FileTransferEvent>()
    val fileTransferEvent: LiveData<FileTransferEvent> = _fileTransferEvent

    // Invoked on EVERY FileTransfer Success — drives the player-side content:// →
    // file:// playlist swap. Deliberately NOT routed through fileTransferEvent above:
    // that LiveData is fed by postValue, which coalesces bursts and silently drops
    // intermediate values. During a multi-video pull the flood of Progress events
    // overwrote the Success values, so most videos never got their URI swapped and
    // only the first video ever played on players (field bug). Set by GameViewModel.
    var onFileReceived: ((String) -> Unit)? = null

    private val _gameSyncEvent = MutableLiveData<NetworkEvent>()
    val gameSyncEvent: LiveData<NetworkEvent> = _gameSyncEvent

    var isWifiP2pEnabled = false

    val wifiP2pManager: WifiP2pManager by lazy {
        application.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private val wifiManager: android.net.wifi.WifiManager by lazy {
        application.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    fun openWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
    }

    /**
     * Whether Wi-Fi Direct scanning can work. On API 33+ we hold NEARBY_WIFI_DEVICES with
     * `neverForLocation`, so the system Location toggle is irrelevant → always true. On
     * API < 33, P2P discovery silently returns nothing unless Location is on.
     */
    fun isLocationEnabled(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) return true
        val lm = application.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return true
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
    }

    fun openLocationSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val _connectionInfo = MutableLiveData<WifiP2pInfo>()
    val connectionInfo: LiveData<WifiP2pInfo> = _connectionInfo

    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        _connectionInfo.postValue(info)
    }

    val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(application, application.mainLooper, null)
    internal val broadcastReceiver: BroadcastReceiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel, this)

    init {
        observeGameSyncEvents()
        observeFileTransferEvents()
    }

    private fun observeGameSyncEvents() {
        gameSync.events.onEach { event ->
            _gameSyncEvent.postValue(event)
        }.launchIn(coroutineScope)
    }

    private fun observeFileTransferEvents() {
        fileTransfer.events.onEach { event ->
            // Drive the lossless swap first so no Success is ever dropped, THEN post
            // to the coalescing LiveData that only feeds cosmetic progress/toasts.
            if (event is FileTransferEvent.Success) onFileReceived?.invoke(event.fileName)
            _fileTransferEvent.postValue(event)
        }.launchIn(coroutineScope)
    }

    fun setThisDevice(device: WifiP2pDevice) {
        _thisDevice.postValue(device)
    }

    fun setGameStarted(started: Boolean) {
        _isGameStarted.postValue(started)
    }

    fun updatePlayers(players: List<Player>) {
        _players.postValue(players)
    }

    fun restoreVideos(videos: List<Video>) {
        videosMirror = videos
        _videos.postValue(videos)
    }

    fun showToast(message: String) {
        _toastMessage.postValue(message)
    }

    fun getFileName(uri: Uri): String? {
        var name: String? = null
        application.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    name = it.getString(displayNameIndex)
                }
            }
        }
        return name
    }

    suspend fun findFreePort(): Int = withContext(Dispatchers.IO) {
        try {
            val socket = java.net.ServerSocket(0)
            val port = socket.localPort
            socket.close()
            port
        } catch (e: IOException) {
            android.util.Log.e("GameRepository", "Failed to find free port", e)
            -1
        }
    }

    fun shutdown() {
        coroutineScope.cancel()
        gameSync.shutdown()
        fileTransfer.shutdown()
        try {
            wifiP2pManager.clearLocalServices(channel, null)
            wifiP2pManager.removeGroup(channel, null)
            channel.close()
        } catch (e: Exception) {
            android.util.Log.w("GameRepository", "Wi-Fi P2P cleanup failed", e)
        }
    }
}


