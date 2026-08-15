package com.project01.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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

    private val wifiManager: android.net.wifi.WifiManager by lazy {
        application.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    }
    private val connectivityManager: ConnectivityManager by lazy {
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    fun openWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
    }

    /**
     * The game host's IP, or null if this device isn't on the game's network.
     *
     * The game master hosts the hotspot every device is already connected to, so the GM is
     * this network's gateway — no discovery protocol needed. Three sources are tried in
     * order because the fleet spans many Android versions (minSdk 24):
     *  1. `dhcpServerAddress` — API 30+ only (the A20e is exactly API 30); the most precise,
     *     since for a hotspot the DHCP server *is* the access point.
     *  2. the default route's gateway — available on every supported version.
     *  3. the legacy `DhcpInfo.gateway` int — deprecated in API 31 but still functional, and
     *     the last resort for the oldest phones.
     * The chosen source is logged so a field failure can be diagnosed from one log line.
     */
    fun resolveHostAddress(): String? {
        val linkProperties = try {
            connectivityManager.activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getLinkProperties failed", e)
            null
        }

        if (linkProperties != null && android.os.Build.VERSION.SDK_INT >= 30) {
            val dhcpServer = linkProperties.dhcpServerAddress?.hostAddress
            if (HostAddressResolver.isUsableHost(dhcpServer)) {
                android.util.Log.d(TAG, "host = $dhcpServer (dhcpServerAddress)")
                return dhcpServer
            }
        }

        // Prefer the default route, but accept any route that names a gateway — a hotspot
        // with no upstream internet doesn't always publish a default route.
        val routeGateway = linkProperties?.routes
            ?.sortedByDescending { it.isDefaultRoute }
            ?.firstNotNullOfOrNull { it.gateway?.hostAddress }
        if (HostAddressResolver.isUsableHost(routeGateway)) {
            android.util.Log.d(TAG, "host = $routeGateway (route gateway)")
            return routeGateway
        }

        @Suppress("DEPRECATION")
        val dhcpGateway = try {
            HostAddressResolver.formatDhcpGateway(wifiManager.dhcpInfo?.gateway ?: 0)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dhcpInfo failed", e)
            null
        }
        if (HostAddressResolver.isUsableHost(dhcpGateway)) {
            android.util.Log.d(TAG, "host = $dhcpGateway (dhcpInfo)")
            return dhcpGateway
        }

        android.util.Log.w(TAG, "could not resolve a host address — not connected to the game's Wi-Fi?")
        return null
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    }

    companion object {
        /** Logcat tag for host-address resolution: `adb logcat -s GameNet`. */
        private const val TAG = "GameNet"
    }
}


