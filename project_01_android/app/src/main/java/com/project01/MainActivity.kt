package com.project01

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import android.hardware.camera2.CameraManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.project01.databinding.ActivityMainBinding
import com.project01.p2p.ConnectionService
import com.project01.session.CreateGameDialogFragment
import com.project01.session.JoinGameDialogFragment
import com.project01.session.PlaybackIntent
import com.project01.session.SnapshotManager
import com.project01.session.Video
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import com.project01.viewmodel.GameViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val gameViewModel: GameViewModel by viewModels { GameViewModel.Factory }
    private lateinit var binding: ActivityMainBinding
    private lateinit var playerAdapter: PlayerAdapter
    private lateinit var videoAdapter: VideoAdapter
    private var exoPlayer: ExoPlayer? = null
    private var intentReconcileJob: Job? = null
    private var isScreenOff = false
    private var isTorchOn = false
    private var isGmOverlayVisible = false

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
            requirePermissions(wifiP2pPermissions()) {
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
            requirePermissions(wifiP2pPermissions()) {
                CreateGameDialogFragment { password ->
                    gameViewModel.createGame(password)
                }.show(supportFragmentManager, "CreateGameDialogFragment")
            }
        }

        binding.joinGameButton.setOnClickListener {
            requirePermissions(wifiP2pPermissions()) {
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
            if (isScreenOff) {
                gameViewModel.turnOnScreen()
            } else {
                gameViewModel.turnOffScreen()
            }
        }

        binding.deactivateTorchButton.setOnClickListener {
            if (isTorchOn) {
                gameViewModel.deactivateTorch()
            } else {
                gameViewModel.activateTorch()
            }
        }

        // invisible_resume_button hides three GM-only gestures so the player UI stays clean:
        //  - double-tap: toggle the GM control overlay
        //  - long-press: confirm End Game
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleGmOverlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showEndGameDialog()
            }
        })
        binding.invisibleResumeButton.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // GM overlay controls.
        binding.gmPreviousButton.setOnClickListener { onGmPrevious() }
        binding.gmNextButton.setOnClickListener { onGmPlayNext() }
        binding.gmLightButton.setOnClickListener { onGmToggleLight() }
        binding.gmPlaylistButton.setOnClickListener {
            val isVisible = binding.listsContainer.visibility == View.VISIBLE
            binding.listsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }

    private fun onGmPrevious() {
        val player = exoPlayer ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        // Compute the target index from the controller's intent (the source of
        // truth) rather than ExoPlayer's currentMediaItemIndex —
        // seekToPreviousMediaItem() interacts unpredictably with
        // pauseAtEndOfMediaItems when the player is parked at the end of an
        // item, so we never lean on ExoPlayer for the target.
        gameViewModel.playbackController.previous(count)
    }

    private fun onGmPlayNext() {
        val player = exoPlayer ?: return
        val count = player.mediaItemCount
        if (count == 0) return
        // ExoPlayer is the only authority on whether we're parked at the end
        // of the current item (the controller doesn't track durations), so we
        // sample it here and hand the flag to the controller.
        val duration = player.duration
        val atEnd = duration > 0 && player.currentPosition >= duration - 500
        gameViewModel.playbackController.advanceOrResume(count, atEnd)
    }

    private fun onGmToggleLight() {
        // "Light off" = total darkness (torch off + screen off). "Light on" = normal.
        // The two outputs are linked so the GM has a single ambient-state switch.
        val lightsOff = isScreenOff || !isTorchOn
        gameViewModel.setLights(lightsOff)
    }

    /**
     * Bluetooth presenters expose themselves as HID keyboards. Android delivers their key
     * events to the focused window — so the GM just needs to pair the remote in system
     * settings and the events land here. We map the common presenter keys to the GM's
     * Prev / Play-Next / Light actions.
     *
     * Volume keys are intentionally NOT mapped. They'd conflict with the GM phone's own
     * media volume during a session.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGameMaster = gameViewModel.isGameMaster()
        val isGameStarted = gameViewModel.isGameStarted.value == true
        if (!isGameMaster || !isGameStarted) {
            return super.dispatchKeyEvent(event)
        }
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT -> true
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_F5,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        // Run the action on the DOWN edge only; still consume UP so the OS doesn't act on it.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT -> onGmPlayNext()
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> onGmPrevious()
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_F5,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> onGmToggleLight()
            }
        }
        return true
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
            handleAdvancedCommand(command)
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

    private fun toggleGmOverlay() {
        isGmOverlayVisible = !isGmOverlayVisible
        binding.gmOverlay.visibility = if (isGmOverlayVisible) View.VISIBLE else View.GONE
    }

    private fun handleAdvancedCommand(command: com.project01.session.AdvancedCommand) {
        when (command.type) {
            com.project01.session.AdvancedCommandType.TURN_OFF_SCREEN -> applyScreenOff()
            com.project01.session.AdvancedCommandType.TURN_ON_SCREEN -> applyScreenOn()
            com.project01.session.AdvancedCommandType.DEACTIVATE_TORCH -> applyTorchOff()
            com.project01.session.AdvancedCommandType.ACTIVATE_TORCH -> applyTorchOn()
            com.project01.session.AdvancedCommandType.LIGHTS_OFF -> {
                applyScreenOff()
                applyTorchOff()
            }
            com.project01.session.AdvancedCommandType.LIGHTS_ON -> {
                applyScreenOn()
                applyTorchOn()
            }
        }
        updateGmLightButton()
    }

    private fun applyScreenOff() {
        isScreenOff = true
        binding.blackOverlay.visibility = View.VISIBLE
        setScreenBrightness(0f)
        binding.turnOffScreenButton.text = "Screen On"
    }

    private fun applyScreenOn() {
        isScreenOff = false
        binding.blackOverlay.visibility = View.GONE
        setScreenBrightness(-1f)
        binding.turnOffScreenButton.text = "Screen"
    }

    private fun applyTorchOff() {
        isTorchOn = false
        binding.deactivateTorchButton.text = "Torch"
        setTorchMode(false)
    }

    private fun applyTorchOn() {
        isTorchOn = true
        binding.deactivateTorchButton.text = "Torch Off"
        setTorchMode(true)
    }

    private fun updateGmLightButton() {
        val lightsOn = !isScreenOff && isTorchOn
        binding.gmLightButton.setImageResource(
            if (lightsOn) R.drawable.ic_lightbulb_overlay
            else R.drawable.ic_lightbulb_outline_overlay
        )
    }

    private fun setScreenBrightness(brightness: Float) {
        val params = window.attributes
        params.screenBrightness = brightness // 0f = minimum, -1f = system default
        window.attributes = params
    }

    private fun setTorchMode(enabled: Boolean) {
        val cameraManager = getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        if (cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, enabled)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to change torch: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No flash available on this device", Toast.LENGTH_SHORT).show()
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
        binding.blackOverlay.visibility = View.GONE
        binding.gmOverlay.visibility = View.GONE
        binding.playerView.useController = true
        isGmOverlayVisible = false
        isScreenOff = false
        isTorchOn = false
        binding.turnOffScreenButton.text = "Screen"
        binding.deactivateTorchButton.text = "Torch"
        updateGmLightButton()
        setScreenBrightness(-1f)
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
        binding.gmOverlay.visibility = View.GONE
        isGmOverlayVisible = false
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
        unregisterReceiver(gameViewModel.repository.broadcastReceiver)
    }

    private fun initializePlayer() {
        releasePlayer()
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            // Game master controls pacing — never auto-advance between videos.
            // Each video ends on the blue safe-screen; GM presses Next to play the next one.
            pauseAtEndOfMediaItems = true
        }
        binding.playerView.player = exoPlayer
        binding.playerView.setShutterBackgroundColor(resources.getColor(R.color.safe_blue, theme))
        binding.playerView.videoSurfaceView?.visibility = View.GONE

        // Reload media items if videos were already added
        gameViewModel.videos.value?.let { updatePlayerPlaylist(it) }

        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exoPlayer ?: return
                gameViewModel.playbackController.onPlayerTransition(
                    player.currentMediaItemIndex,
                    player.currentPosition,
                    player.playWhenReady,
                )
            }
        })

        intentReconcileJob?.cancel()
        intentReconcileJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                gameViewModel.playbackController.intent.collect { intent ->
                    applyIntentToExoPlayer(intent)
                }
            }
        }
    }

    /**
     * Reconcile ExoPlayer against the commanded intent. The collector fires on
     * any intent change — from GM button presses, wire commands, or snapshot
     * restore — so this is the single code path that drives ExoPlayer.
     * StateFlow's structural-equality dedup collapses no-op re-emissions.
     */
    private fun applyIntentToExoPlayer(intent: PlaybackIntent) {
        val player = exoPlayer ?: return
        if (intent.videoIndex in 0 until player.mediaItemCount) {
            // Seek only when index changed or position diverges materially.
            // Avoids fighting ExoPlayer's natural advance within an item.
            val indexChanged = player.currentMediaItemIndex != intent.videoIndex
            val positionDelta = Math.abs(player.currentPosition - intent.positionMs)
            if (indexChanged || positionDelta > SEEK_TOLERANCE_MS) {
                player.seekTo(intent.videoIndex, intent.positionMs)
            }
        }
        player.playWhenReady = intent.isPlaying
        binding.playerView.videoSurfaceView?.visibility =
            if (intent.isPlaying) View.VISIBLE else View.GONE
    }

    private fun releasePlayer() {
        intentReconcileJob?.cancel()
        intentReconcileJob = null
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        /** Tolerance for "this is the same position" — avoids re-seeking on every
         *  intent emission when only the position field updated marginally. */
        private const val SEEK_TOLERANCE_MS = 500L
    }
}
