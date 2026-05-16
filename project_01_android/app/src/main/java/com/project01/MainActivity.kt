package com.project01

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import com.project01.databinding.ActivityMainBinding
import com.project01.p2p.ConnectionService
import com.project01.session.CreateGameDialogFragment
import com.project01.session.JoinGameDialogFragment
import com.project01.session.SnapshotManager
import com.project01.ui.ConnectionStatus
import com.project01.ui.GmControlsDelegate
import com.project01.ui.LightsAndScreenDelegate
import com.project01.ui.PermissionHelper
import com.project01.ui.PlaybackViewDelegate
import com.project01.ui.UiError
import com.project01.viewmodel.GameViewModel

class MainActivity : AppCompatActivity() {

    private val gameViewModel: GameViewModel by viewModels { GameViewModel.Factory }
    private lateinit var binding: ActivityMainBinding
    private lateinit var playerAdapter: PlayerAdapter
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var lightsAndScreen: LightsAndScreenDelegate
    private lateinit var playbackView: PlaybackViewDelegate
    private lateinit var gmControls: GmControlsDelegate

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            gameViewModel.addVideo(it)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Bluetooth enable result handled — no action needed
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        permissions.onPermissionsResult(results)
    }

    private val permissions: PermissionHelper = PermissionHelper(this) { perms -> permissionLauncher.launch(perms) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lightsAndScreen = LightsAndScreenDelegate(this, binding)
        playbackView = PlaybackViewDelegate(this, binding, gameViewModel.playbackController) {
            gameViewModel.videos.value
        }
        gmControls = GmControlsDelegate(
            activity = this,
            binding = binding,
            playbackController = gameViewModel.playbackController,
            playbackView = playbackView,
            lightsAndScreen = lightsAndScreen,
            isGameMaster = { gameViewModel.isGameMaster() },
            isGameStarted = { gameViewModel.isGameStarted.value == true },
            setLights = { gameViewModel.setLights(it) },
            onLongPress = { showEndGameDialog() },
        )
        gmControls.bind()

        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        checkForResumeSnapshot()
    }

    private fun setupRecyclerViews() {
        playerAdapter = PlayerAdapter { player ->
            permissions.requirePermissions(permissions.wifiP2pPermissions()) {
                gameViewModel.connectToPlayer(player)
            }
        }
        binding.playerList.layoutManager = LinearLayoutManager(this)
        binding.playerList.adapter = playerAdapter

        videoAdapter = VideoAdapter(
            true,
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
            permissions.requirePermissions(permissions.wifiP2pPermissions()) {
                CreateGameDialogFragment { password ->
                    gameViewModel.createGame(password)
                }.show(supportFragmentManager, "CreateGameDialogFragment")
            }
        }

        binding.joinGameButton.setOnClickListener {
            permissions.requirePermissions(permissions.wifiP2pPermissions()) {
                gameViewModel.discoverPeers()
                JoinGameDialogFragment { name, password ->
                    gameViewModel.joinGame(name, password)
                }.show(supportFragmentManager, "JoinGameDialogFragment")
            }
        }

        binding.addVideoButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*"))
        }

        binding.savePlaylistButton.setOnClickListener { showSavePlaylistDialog() }
        binding.loadPlaylistButton.setOnClickListener { showLoadPlaylistDialog() }

        binding.turnOffScreenButton.setOnClickListener {
            if (lightsAndScreen.isScreenOff) {
                gameViewModel.turnOnScreen()
            } else {
                gameViewModel.turnOffScreen()
            }
        }

        binding.deactivateTorchButton.setOnClickListener {
            if (lightsAndScreen.isTorchOn) {
                gameViewModel.deactivateTorch()
            } else {
                gameViewModel.activateTorch()
            }
        }

    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gmControls.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun showSavePlaylistDialog() {
        val current = gameViewModel.videos.value
        if (current.isNullOrEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Playlist name"
            setSingleLine()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save Playlist As")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    gameViewModel.savePlaylistAs(name)
                    Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoadPlaylistDialog() {
        val names = gameViewModel.listSavedPlaylists()
        if (names.isEmpty()) {
            Toast.makeText(this, "No saved playlists", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Load Playlist")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                gameViewModel.loadNamedPlaylist(name)
                Toast.makeText(this, "Loaded \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Delete…") { _, _ -> showDeletePlaylistDialog(names) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePlaylistDialog(names: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Playlist")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                gameViewModel.deleteSavedPlaylist(name)
                Toast.makeText(this, "Deleted \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEndGameDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("End Game?")
            .setMessage("This will end the game for all connected players.")
            .setPositiveButton("End Game") { _, _ ->
                gameViewModel.endGame()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        gameViewModel.players.observe(this, Observer { players ->
            playerAdapter.submitList(players)
        })

        gameViewModel.videos.observe(this, Observer { videos ->
            videoAdapter.submitList(videos)
            playbackView.updatePlaylist(videos)
        })

        gameViewModel.isGameStarted.observe(this, Observer { isStarted ->
            updateUi(isStarted)
            if (isStarted) {
                ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
                val current = gameViewModel.playbackController.currentIntent()
                videoAdapter.setCurrent(current.videoIndex, current.isPlaying)
            } else {
                stopService(Intent(this, ConnectionService::class.java))
                videoAdapter.setCurrent(-1, false)
            }
        })

        // Mirror the playback intent onto the playlist now-playing indicator.
        // Only meaningful during an active session; the isGameStarted observer
        // above clears it on lobby return.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gameViewModel.playbackController.intent.collect { intent ->
                    if (gameViewModel.isGameStarted.value == true) {
                        videoAdapter.setCurrent(intent.videoIndex, intent.isPlaying)
                    }
                }
            }
        }

        gameViewModel.toastMessage.observe(this, Observer { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })

        gameViewModel.thisDevice.observe(this, Observer { _ ->
            // Do something with the device info if needed
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

        gameViewModel.requestEnableBluetooth.observe(this, Observer {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        })

        gameViewModel.advancedCommand.observe(this, Observer { command ->
            lightsAndScreen.handleAdvancedCommand(command)
        })

        gameViewModel.fileTransferEvent.observe(this, Observer { event ->
            when (event) {
                is com.project01.session.FileTransferEvent.Progress -> {
                    videoAdapter.updateProgress(event.fileName, event.progress)
                }
                is com.project01.session.FileTransferEvent.Success -> {
                    gameViewModel.onFileTransferSuccess(event.fileName)
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

    private fun showLobby() {
        // In the lobby nobody has a role yet — show playlist edit controls so the
        // user can prepare a playlist for the next session (host or guest).
        videoAdapter.editable = true
        videoAdapter.notifyDataSetChanged()

        binding.errorBanner.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.GONE
        binding.sectionLabels.visibility = View.VISIBLE
        binding.listsContainer.visibility = View.VISIBLE
        binding.buttonBar.visibility = View.VISIBLE
        binding.connectivityIndicator.visibility = View.VISIBLE
        binding.invisibleResumeButton.visibility = View.GONE
        binding.playerView.useController = true
        gmControls.hideOverlay()
        lightsAndScreen.resetToLobbyDefaults()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Restore action bar and system bars
        supportActionBar?.show()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())

        binding.createGameButton.announceForAccessibility("Returned to lobby.")
    }

    private fun showGame() {
        val isGameMaster = gameViewModel.isGameMaster()
        // Playlist edits (move up/down/delete) are lobby-only — during a session
        // the GM can toggle the playlist visible to monitor connection/battery
        // status, but the edit controls stay hidden.
        videoAdapter.editable = false
        videoAdapter.notifyDataSetChanged()

        // Hide lobby UI, show full-screen player
        binding.playerContainer.visibility = View.VISIBLE
        binding.sectionLabels.visibility = View.GONE
        binding.listsContainer.visibility = View.GONE
        binding.buttonBar.visibility = View.GONE
        binding.connectivityIndicator.visibility = View.GONE
        binding.playerView.useController = false
        binding.playerView.videoSurfaceView?.visibility = View.GONE
        gmControls.hideOverlay()
        binding.invisibleResumeButton.visibility = if (isGameMaster) View.VISIBLE else View.GONE
        binding.playerView.announceForAccessibility("Game started. Video player is active.")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive fullscreen: hide action bar, status bar, navigation bar
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun updateUi(isGameStarted: Boolean) {
        if (isGameStarted) {
            showGame()
        } else {
            showLobby()
        }
    }

    override fun onResume() {
        super.onResume()
        playbackView.onResume()
        registerReceiver(gameViewModel.repository.broadcastReceiver, gameViewModel.repository.intentFilter)
    }

    override fun onPause() {
        super.onPause()
        playbackView.onPause()
        unregisterReceiver(gameViewModel.repository.broadcastReceiver)
    }
}
