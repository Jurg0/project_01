package com.project01.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.project01.session.*
import kotlinx.coroutines.launch
import java.io.File

class GameViewModel(application: Application, val repository: GameRepository = GameRepository(application)) : AndroidViewModel(application) {

    val players: LiveData<List<Player>> = repository.players
    val videos: LiveData<List<Video>> = repository.videos
    val isGameStarted: LiveData<Boolean> = repository.isGameStarted
    val thisDevice: LiveData<WifiP2pDevice> = repository.thisDevice
    val toastMessage: LiveData<String> = repository.toastMessage
    val fileTransferEvent: LiveData<FileTransferEvent> = repository.fileTransferEvent
    private val _connectivityStatus = MutableLiveData<String>()
    val connectivityStatus: LiveData<String> = _connectivityStatus

    private val _showVideo = MutableLiveData<Unit>()
    val showVideo: LiveData<Unit> = _showVideo

    private val _playbackCommand = MutableLiveData<PlaybackCommand>()
    val playbackCommand: LiveData<PlaybackCommand> = _playbackCommand


    private val _advancedCommand = MutableLiveData<AdvancedCommand>()
    val advancedCommand: LiveData<AdvancedCommand> = _advancedCommand

    private val _bluetoothDevices = MutableLiveData<List<BluetoothDevice>>()
    val bluetoothDevices: LiveData<List<BluetoothDevice>> = _bluetoothDevices

    private var player: Player? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothRemoteControl: BluetoothRemoteControl? = null
    private var isBluetoothReceiverRegistered = false

    private val connectionInfoObserver = Observer<android.net.wifi.p2p.WifiP2pInfo> { info ->
        handleConnectionInfo(info)
    }
    private val gameSyncEventObserver = Observer<NetworkEvent> { event ->
        handleGameSyncEvent(event)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        val currentDevices = _bluetoothDevices.value?.toMutableList() ?: mutableListOf()
                        if (!currentDevices.contains(it)) {
                            currentDevices.add(it)
                            _bluetoothDevices.postValue(currentDevices)
                        }
                    }
                }
            }
        }
    }

    private val _requestEnableBluetooth = MutableLiveData<Boolean>()
    val requestEnableBluetooth: LiveData<Boolean> = _requestEnableBluetooth

    init {
        repository.gameSyncEvent.observeForever(gameSyncEventObserver)
        repository.connectionInfo.observeForever(connectionInfoObserver)
        initializeBluetooth()
    }

    private fun handleConnectionInfo(info: android.net.wifi.p2p.WifiP2pInfo) {
        if (info.groupFormed) {
            repository.setGameStarted(true)
            if (info.isGroupOwner) {
                player = thisDevice.value?.let { Player(it, it.deviceName, true) }
                repository.gameSync.startServer()
                _connectivityStatus.postValue("Host")
            } else {
                player = thisDevice.value?.let { Player(it, it.deviceName, false) }
                repository.gameSync.connectTo(info.groupOwnerAddress.hostAddress, repository.gameSync.port)
                _connectivityStatus.postValue("Connected")
            }
        }
    }

    private fun initializeBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // Device doesn't support Bluetooth
        } else {
            if (bluetoothAdapter?.isEnabled == false) {
                _requestEnableBluetooth.postValue(true)
            }
        }
        bluetoothRemoteControl = BluetoothRemoteControl { message ->
            handleRemoteControlMessage(message)
        }
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun startBluetoothDiscovery() {
        getApplication<Application>().registerReceiver(
            bluetoothReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        isBluetoothReceiverRegistered = true
        bluetoothAdapter?.startDiscovery()
    }

    fun connectToBluetoothDevice(device: BluetoothDevice) {
        bluetoothRemoteControl?.connect(device)
    }

    private fun handleGameSyncEvent(event: NetworkEvent) {
        when (event) {
            is NetworkEvent.DataReceived -> {
                val (data, address) = event.data to event.sender
                when (data) {
                    is List<*> -> handleVideoList(data.filterIsInstance<Video>(), address)
                    is FileTransferRequest -> handleFileTransferRequest(data)
                    is PlaybackCommand -> _playbackCommand.postValue(data)
                    is PlaybackState -> applyPlaybackState(data)
                    is AdvancedCommand -> _advancedCommand.postValue(data)
                    is PasswordMessage -> handlePasswordMessage(data)
                    is PasswordResponseMessage -> handlePasswordResponseMessage(data)
                }
            }
            is NetworkEvent.Error -> {
                repository.showToast(event.exception.message ?: "Unknown error")
            }
            is NetworkEvent.ClientDisconnected -> {
                repository.showToast("Client disconnected: ${event.address}")
            }
        }
    }

    private fun handlePasswordMessage(message: PasswordMessage) {
        if (isGameMaster()) {
            val success = message.password == gamePassword
            viewModelScope.launch {
                repository.gameSync.broadcast(PasswordResponseMessage(success))
            }
            if (!success) {
                // TODO: Disconnect the player
            }
        }
    }

    private fun handlePasswordResponseMessage(message: PasswordResponseMessage) {
        _passwordVerified.postValue(message.success)
    }

    private fun handleVideoList(newVideos: List<Video>, senderAddress: String) {
        if (isGameMaster() == false) {
            newVideos.forEach { video ->
                thisDevice.value?.let {
                    requestFileTransfer(video.title, it.deviceAddress, senderAddress)
                }
            }
        }
    }

    private fun handleFileTransferRequest(request: FileTransferRequest) {
        if (thisDevice.value?.deviceAddress == request.targetAddress) {
            if (isGameMaster()) {
                val video = videos.value?.find { it.title == request.fileName }
                if (video != null) {
                    viewModelScope.launch {
                        repository.fileTransfer.sendFile(request.senderAddress, request.port, video.uri, getApplication<Application>().contentResolver)
                    }
                }
            } else {
                val outputFile = File(getApplication<Application>().filesDir, request.fileName)
                viewModelScope.launch {
                    repository.fileTransfer.startReceiving(request.port, outputFile)
                }
            }
        }
    }

    private var gamePassword: String? = null
    private val _passwordVerified = MutableLiveData<Boolean>()
    val passwordVerified: LiveData<Boolean> = _passwordVerified

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun createGame(password: String) {
        this.gamePassword = password
        repository.wifiP2pManager.createGroup(
            repository.channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Handled by connectionInfoListener in repository
                }

                override fun onFailure(reason: Int) {
                    repository.showToast("Group creation failed: $reason")
                }
            })
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun discoverPeers() {
        repository.wifiP2pManager.discoverPeers(
            repository.channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    repository.showToast("Discovery initiated")
                }

                override fun onFailure(reason: Int) {
                    repository.showToast("Discovery failed: $reason")
                }
            })
    }

    fun joinGame(password: String) {
        viewModelScope.launch {
            repository.gameSync.broadcast(PasswordMessage(password))
        }
    }

    @SuppressLint("MissingPermission") // Permission checked in MainActivity before calling
    fun connectToPlayer(player: Player) {
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
                    repository.showToast("Connection failed: $reason")
                }
            })
    }

    fun addVideo(uri: Uri) {
        viewModelScope.launch {
            val fileName = repository.getFileName(uri) ?: "Video ${videos.value?.size?.plus(1)}"
            val video = Video(uri, fileName)
            val currentVideos = videos.value?.toMutableList() ?: mutableListOf()
            currentVideos.add(video)
            repository.gameSync.broadcast(currentVideos)
        }
    }

    fun turnOffScreen() {
        viewModelScope.launch {
            repository.gameSync.broadcast(AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN))
        }
    }

    fun deactivateTorch() {
        viewModelScope.launch {
            repository.gameSync.broadcast(AdvancedCommand(AdvancedCommandType.DEACTIVATE_TORCH))
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
                repository.gameSync.broadcast(currentVideos)
            }
        }
    }

    fun moveVideoDown(position: Int) {
        viewModelScope.launch {
            val currentVideos = videos.value?.toMutableList() ?: return@launch
            if (position in 0 until currentVideos.lastIndex) {
                val video = currentVideos.removeAt(position)
                currentVideos.add(position + 1, video)
                repository.gameSync.broadcast(currentVideos)
            }
        }
    }

    fun removeVideo(position: Int) {
        viewModelScope.launch {
            val currentVideos = videos.value?.toMutableList() ?: return@launch
            if (position in currentVideos.indices) {
                currentVideos.removeAt(position)
                repository.gameSync.broadcast(currentVideos)
            }
        }
    }

    fun playNextVideo(currentVideoIndex: Int) {
        val videos = videos.value ?: return
        if (currentVideoIndex < videos.size - 1) {
            _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.NEXT))
        } else {
            _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE))
        }
    }

    fun onVideoSelected(video: Video) {
        val videoIndex = videos.value?.indexOf(video)
        if (videoIndex != null) {
            _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex))
        }
    }

    private fun handleRemoteControlMessage(message: String) {
        when (message) {
            "next" -> _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.NEXT))
            "previous" -> _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PREVIOUS))
        }
    }


    fun broadcastPlaybackState(position: Long, isPlaying: Boolean, videoIndex: Int) {
        if (isGameMaster()) {
            viewModelScope.launch {
                repository.gameSync.broadcast(PlaybackState(videoIndex, position, isPlaying))
            }
        }
    }

    private fun applyPlaybackState(state: PlaybackState) {
        if (state.playWhenReady) {
            _showVideo.postValue(Unit)
        }
        _playbackCommand.postValue(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, state.videoIndex, state.playbackPosition, state.playWhenReady))
    }

    private fun requestFileTransfer(
        fileName: String,
        targetAddress: String,
        senderAddress: String
    ) {
        viewModelScope.launch {
            val port = repository.findFreePort()
            repository.gameSync.broadcast(
                FileTransferRequest(
                    fileName,
                    port,
                    senderAddress,
                    targetAddress
                )
            )
        }
    }



    fun onPause() {
        if (isBluetoothReceiverRegistered) {
            getApplication<Application>().unregisterReceiver(bluetoothReceiver)
            isBluetoothReceiverRegistered = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.connectionInfo.removeObserver(connectionInfoObserver)
        repository.gameSyncEvent.removeObserver(gameSyncEventObserver)
        repository.shutdown()
    }
}



