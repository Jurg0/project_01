package com.project01

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.hardware.camera2.CameraManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.project01.databinding.ActivityMainBinding
import com.project01.p2p.ConnectionService
import com.project01.session.CreateGameDialogFragment
import com.project01.session.JoinGameDialogFragment
import com.project01.session.SnapshotManager
import com.project01.session.Video
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import com.project01.viewmodel.GameViewModel

class MainActivity : AppCompatActivity() {

    private val gameViewModel: GameViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var playerAdapter: PlayerAdapter
    private lateinit var videoAdapter: VideoAdapter
    private var exoPlayer: ExoPlayer? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            gameViewModel.addVideo(it)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Bluetooth enable result handled — no action needed
    }

    private var pendingActionAfterPermission: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            pendingActionAfterPermission?.invoke()
        } else {
            Toast.makeText(this, "Permissions required for this feature", Toast.LENGTH_SHORT).show()
        }
        pendingActionAfterPermission = null
    }

    private fun hasPermissions(vararg permissions: String): Boolean {
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requirePermissions(permissions: Array<String>, action: () -> Unit) {
        if (hasPermissions(*permissions)) {
            action()
        } else {
            pendingActionAfterPermission = action
            permissionLauncher.launch(permissions)
        }
    }

    private fun wifiP2pPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun bluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        checkForResumeSnapshot()
    }

    private fun setupRecyclerViews() {
        playerAdapter = PlayerAdapter { player ->
            gameViewModel.connectToPlayer(player)
        }
        binding.playerList.layoutManager = LinearLayoutManager(this)
        binding.playerList.adapter = playerAdapter

        videoAdapter = VideoAdapter(
            false,
            { position -> gameViewModel.moveVideoUp(position) },
            { position -> gameViewModel.moveVideoDown(position) },
            { position -> gameViewModel.removeVideo(position) },
            { video -> gameViewModel.onVideoSelected(video) }
        )
        binding.videoPlaylist.layoutManager = LinearLayoutManager(this)
        binding.videoPlaylist.adapter = videoAdapter
    }

    private fun setupClickListeners() {
        binding.createGameButton.setOnClickListener {
            requirePermissions(wifiP2pPermissions()) {
                CreateGameDialogFragment { password ->
                    gameViewModel.createGame(password)
                }.show(supportFragmentManager, "CreateGameDialogFragment")
            }
        }

        binding.joinGameButton.setOnClickListener {
            requirePermissions(wifiP2pPermissions()) {
                gameViewModel.discoverPeers()
                JoinGameDialogFragment { password ->
                    gameViewModel.joinGame(password)
                }.show(supportFragmentManager, "JoinGameDialogFragment")
            }
        }

        binding.addVideoButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*"))
        }

        binding.playPauseButton.setOnClickListener {
            exoPlayer?.playWhenReady = exoPlayer?.playWhenReady == false
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        binding.nextButton.setOnClickListener {
            exoPlayer?.seekToNextMediaItem()
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        binding.previousButton.setOnClickListener {
            exoPlayer?.seekToPreviousMediaItem()
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        binding.turnOffScreenButton.setOnClickListener {
            gameViewModel.turnOffScreen()
        }

        binding.deactivateTorchButton.setOnClickListener {
            gameViewModel.deactivateTorch()
        }
    }

    private fun observeViewModel() {
        gameViewModel.players.observe(this, Observer { players ->
            playerAdapter.submitList(players)
        })

        gameViewModel.videos.observe(this, Observer { videos ->
            videoAdapter.submitList(videos)
            updatePlayerPlaylist(videos)
        })

        gameViewModel.isGameStarted.observe(this, Observer { isStarted ->
            updateUi(isStarted)
            if (isStarted) {
                ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
            } else {
                stopService(Intent(this, ConnectionService::class.java))
            }
        })

        gameViewModel.toastMessage.observe(this, Observer { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })

        gameViewModel.thisDevice.observe(this, Observer { _ ->
            // Do something with the device info if needed
        })

        gameViewModel.playbackCommand.observe(this, Observer { command ->
            handlePlaybackCommand(command)
        })

        gameViewModel.passwordVerified.observe(this, Observer { isVerified ->
            if (!isVerified) {
                Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
            }
        })

        gameViewModel.connectionState.observe(this, Observer { state ->
            updateConnectivityIndicator(state)
        })

        gameViewModel.uiError.observe(this, Observer { error ->
            handleUiError(error)
        })

        gameViewModel.showVideo.observe(this, Observer {
            binding.playerView.videoSurfaceView?.visibility = View.VISIBLE
        })

        gameViewModel.requestEnableBluetooth.observe(this, Observer {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        })

        gameViewModel.advancedCommand.observe(this, Observer { command ->
            handleAdvancedCommand(command)
        })

        gameViewModel.fileTransferEvent.observe(this, Observer { event ->
            when (event) {
                is com.project01.session.FileTransferEvent.Progress -> {
                    videoAdapter.updateProgress(event.fileName, event.progress)
                }
                is com.project01.session.FileTransferEvent.Success -> {
                    Toast.makeText(this, "Transfer complete: ${event.fileName}", Toast.LENGTH_SHORT).show()
                }
                is com.project01.session.FileTransferEvent.Failure -> {
                    videoAdapter.markFailed(event.fileName)
                    Snackbar.make(binding.root, "Transfer failed: ${event.fileName}", Snackbar.LENGTH_LONG).show()
                }
                is com.project01.session.FileTransferEvent.RetryAttempt -> {
                    Toast.makeText(this, "Retrying transfer (${event.attempt}/${event.maxRetries}): ${event.fileName}", Toast.LENGTH_SHORT).show()
                }
                is com.project01.session.FileTransferEvent.ChecksumFailed -> {
                    videoAdapter.markFailed(event.fileName)
                    Snackbar.make(binding.root, "File corrupted during transfer: ${event.fileName}", Snackbar.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun updateConnectivityIndicator(state: ConnectionStatus) {
        val (text, color) = when (state) {
            ConnectionStatus.CONNECTED -> "Connected" to Color.parseColor("#4CAF50")
            ConnectionStatus.HOST -> "Host" to Color.parseColor("#4CAF50")
            ConnectionStatus.RECONNECTING -> "Reconnecting..." to Color.parseColor("#FF9800")
            ConnectionStatus.CONNECTING -> "Connecting..." to Color.parseColor("#FF9800")
            ConnectionStatus.DISCONNECTED -> "Disconnected" to Color.parseColor("#F44336")
        }
        binding.connectivityIndicator.text = text
        binding.connectivityIndicator.setTextColor(color)
    }

    private fun handleUiError(error: UiError) {
        when (error) {
            is UiError.Informational -> {
                Snackbar.make(binding.root, error.message, Snackbar.LENGTH_SHORT).show()
            }
            is UiError.Recoverable -> {
                val snackbar = Snackbar.make(binding.root, error.message, Snackbar.LENGTH_LONG)
                if (error.actionLabel != null && error.action != null) {
                    snackbar.setAction(error.actionLabel) { error.action.invoke() }
                }
                snackbar.show()
            }
            is UiError.Critical -> {
                binding.errorBannerMessage.text = error.message
                if (error.actionLabel != null && error.action != null) {
                    binding.errorBannerAction.text = error.actionLabel
                    binding.errorBannerAction.visibility = View.VISIBLE
                    binding.errorBannerAction.setOnClickListener {
                        error.action.invoke()
                        binding.errorBanner.visibility = View.GONE
                    }
                } else {
                    binding.errorBannerAction.visibility = View.GONE
                }
                binding.errorBannerDismiss.setOnClickListener {
                    binding.errorBanner.visibility = View.GONE
                }
                binding.errorBanner.visibility = View.VISIBLE
            }
        }
    }

    private fun checkForResumeSnapshot() {
        val snapshot = gameViewModel.loadSnapshot()
        if (snapshot != null) {
            val age = System.currentTimeMillis() - snapshot.timestamp
            if (age < SnapshotManager.MAX_SNAPSHOT_AGE_MS) {
                val timeAgo = android.text.format.DateUtils.getRelativeTimeSpanString(
                    snapshot.timestamp,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Resume Game?")
                    .setMessage("A game session from $timeAgo was found. Resume?")
                    .setPositiveButton("Resume") { _, _ ->
                        gameViewModel.restoreFromSnapshot(snapshot)
                    }
                    .setNegativeButton("Discard") { _, _ ->
                        gameViewModel.clearSnapshot()
                    }
                    .show()
            } else {
                gameViewModel.clearSnapshot()
            }
        }
    }

    private fun handleAdvancedCommand(command: com.project01.session.AdvancedCommand) {
        when (command.type) {
            com.project01.session.AdvancedCommandType.TURN_OFF_SCREEN -> {
                binding.blackOverlay.visibility = View.VISIBLE
            }
            com.project01.session.AdvancedCommandType.DEACTIVATE_TORCH -> {
                val cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    try {
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to deactivate torch: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "No flash available on this device", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePlaybackCommand(command: com.project01.session.PlaybackCommand) {
        when (command.type) {
            com.project01.session.PlaybackCommandType.PLAY_PAUSE -> {
                if (command.videoIndex != -1) {
                    exoPlayer?.seekTo(command.videoIndex, command.playbackPosition)
                }
                exoPlayer?.playWhenReady = command.playWhenReady
            }
            com.project01.session.PlaybackCommandType.NEXT -> {
                exoPlayer?.seekToNextMediaItem()
            }
            com.project01.session.PlaybackCommandType.PREVIOUS -> {
                exoPlayer?.seekToPreviousMediaItem()
            }
        }
    }

    private fun showLobby() {
        val isGameMaster = gameViewModel.isGameMaster()
        videoAdapter.isGameMaster = isGameMaster
        videoAdapter.notifyDataSetChanged() // Needed to refresh game master button visibility

        binding.errorBanner.visibility = View.GONE
        binding.playerView.visibility = View.GONE
        binding.playerList.visibility = View.VISIBLE
        binding.videoPlaylist.visibility = View.VISIBLE
        binding.createGameButton.visibility = View.VISIBLE
        binding.joinGameButton.visibility = View.VISIBLE
        binding.addVideoButton.visibility = View.VISIBLE
        binding.turnOffScreenButton.visibility = View.VISIBLE
        binding.deactivateTorchButton.visibility = View.VISIBLE
        binding.playbackControls.visibility = View.VISIBLE
        binding.listsContainer.visibility = View.VISIBLE
        binding.invisibleResumeButton.visibility = View.GONE
    }

    private fun showGame() {
        val isGameMaster = gameViewModel.isGameMaster()

        binding.playerView.visibility = View.VISIBLE
        binding.playerView.useController = false
        binding.playerView.videoSurfaceView?.visibility = View.GONE
        binding.playerList.visibility = View.GONE
        binding.videoPlaylist.visibility = View.GONE
        binding.createGameButton.visibility = View.GONE
        binding.joinGameButton.visibility = View.GONE
        binding.addVideoButton.visibility = View.GONE
        binding.turnOffScreenButton.visibility = View.GONE
        binding.deactivateTorchButton.visibility = View.GONE
        binding.playbackControls.visibility = View.GONE
        binding.listsContainer.visibility = View.GONE
        binding.invisibleResumeButton.visibility = if (isGameMaster) View.VISIBLE else View.GONE
    }

    private fun updateUi(isGameStarted: Boolean) {
        if (isGameStarted) {
            showGame()
        } else {
            showLobby()
        }
    }

    private fun updatePlayerPlaylist(videos: List<Video>) {
        val mediaItems = videos.map { video ->
            MediaItem.fromUri(video.uri)
        }
        exoPlayer?.setMediaItems(mediaItems)
        exoPlayer?.prepare()
    }

    override fun onResume() {
        super.onResume()
        initializePlayer()
        registerReceiver(gameViewModel.repository.broadcastReceiver, gameViewModel.repository.intentFilter)
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
        gameViewModel.onPause()
        unregisterReceiver(gameViewModel.repository.broadcastReceiver)
    }

    private fun initializePlayer() {
        releasePlayer()
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    exoPlayer?.currentMediaItemIndex?.let { gameViewModel.playNextVideo(it) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                exoPlayer?.let {
                    gameViewModel.broadcastPlaybackState(
                        it.currentPosition,
                        it.playWhenReady,
                        it.currentMediaItemIndex
                    )
                }
            }
        })
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
