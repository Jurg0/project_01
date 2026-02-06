package com.project01.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.p2p.ConnectionService
import com.project01.p2p.WifiDirectBroadcastReceiver
import com.project01.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.InetAddress

@SuppressLint("MissingPermission")
class GameRepository(private val application: Application) {

    private val _players = MutableLiveData<List<Player>>()
    val players: LiveData<List<Player>> = _players

    private val _videos = MutableLiveData<List<Video>>()
    val videos: LiveData<List<Video>> = _videos

    internal val _isGameStarted = MutableLiveData<Boolean>()
    val isGameStarted: LiveData<Boolean> = _isGameStarted

    private val _thisDevice = MutableLiveData<WifiP2pDevice>()
    val thisDevice: LiveData<WifiP2pDevice> = _thisDevice

    internal val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _fileTransferEvent = MutableLiveData<FileTransferEvent>()
    val fileTransferEvent: LiveData<FileTransferEvent> = _fileTransferEvent

    private val _gameSyncEvent = MutableLiveData<Pair<Any, String>>()
    val gameSyncEvent: LiveData<Pair<Any, String>> = _gameSyncEvent

    var isWifiP2pEnabled = false

    val wifiP2pManager: WifiP2pManager by lazy {
        application.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    lateinit var channel: WifiP2pManager.Channel
    internal lateinit var broadcastReceiver: BroadcastReceiver

    val gameSync = GameSync(SocketNetworkManager())
    val fileTransfer = FileTransfer()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val intentFilter = IntentFilter().apply {
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

    init {
        channel = wifiP2pManager.initialize(application, application.mainLooper, null)
        broadcastReceiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel, this)
        application.registerReceiver(broadcastReceiver, intentFilter)
        observeGameSyncEvents()
        observeFileTransferEvents()
    }

    private fun observeGameSyncEvents() {
        gameSync.events.onEach {
            _gameSyncEvent.postValue(it)
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

    fun onPause() {
        application.unregisterReceiver(broadcastReceiver)
        application.stopService(Intent(application, ConnectionService::class.java))
    }

    fun onResume() {
        application.registerReceiver(broadcastReceiver, intentFilter)
    }

    fun shutdown() {
        coroutineScope.cancel()
        gameSync.shutdown()
        fileTransfer.shutdown()
        wifiP2pManager.removeGroup(channel, null)
        channel.close()
        application.stopService(Intent(application, ConnectionService::class.java))
    }
}


