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

class GameRepository(private val application: Application) {

    private val _players = MutableLiveData<List<Player>>()
    val players: LiveData<List<Player>> = _players

    private val _videos = MutableLiveData<List<Video>>()
    val videos: LiveData<List<Video>> = _videos

    private val _isGameStarted = MutableLiveData<Boolean>()
    val isGameStarted: LiveData<Boolean> = _isGameStarted

    private val _thisDevice = MutableLiveData<WifiP2pDevice>()
    val thisDevice: LiveData<WifiP2pDevice> = _thisDevice

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _fileTransferEvent = MutableLiveData<FileTransferEvent>()
    val fileTransferEvent: LiveData<FileTransferEvent> = _fileTransferEvent

    private val _gameSyncEvent = MutableLiveData<NetworkEvent>()
    val gameSyncEvent: LiveData<NetworkEvent> = _gameSyncEvent

    var isWifiP2pEnabled = false

    val wifiP2pManager: WifiP2pManager by lazy {
        application.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    val gameSync = GameSync(SocketNetworkManager())
    val fileTransfer = FileTransfer()
    val snapshotManager = SnapshotManager(java.io.File(application.filesDir, "game_state_snapshot.json"))
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        val refreshedPeers = peerList.deviceList.map { Player(it, it.deviceName, false) }
        _players.postValue(refreshedPeers)
        if (refreshedPeers.isEmpty()) {
            _toastMessage.postValue("No devices found")
        }
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
        fileTransfer.events.onEach {
            _fileTransferEvent.postValue(it)
        }.launchIn(coroutineScope)
    }

    fun setThisDevice(device: WifiP2pDevice) {
        _thisDevice.postValue(device)
    }

    fun setGameStarted(started: Boolean) {
        _isGameStarted.postValue(started)
    }

    fun restoreVideos(videos: List<Video>) {
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
        wifiP2pManager.removeGroup(channel, null)
        channel.close()
    }
}


