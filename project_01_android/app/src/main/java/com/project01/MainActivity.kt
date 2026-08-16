package com.project01

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.project01.session.PasswordPromptDialogFragment
import com.project01.session.SnapshotManager
import com.project01.ui.ConnectionStatus
import com.project01.ui.GmControlsDelegate
import com.project01.ui.LightsAndScreenDelegate
import com.project01.ui.PlaybackViewDelegate
import com.project01.ui.StartScreenControlsDelegate
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
    private lateinit var startScreenControls: StartScreenControlsDelegate

    /** The prepared game the GM is currently editing (prepare mode); null otherwise. */
    private var editingPreparedName: String? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            gameViewModel.addVideo(it)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Bluetooth enable result handled — no action needed
    }

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

        startScreenControls = StartScreenControlsDelegate(
            activity = this,
            binding = binding,
            onCreateRequested = { onCreateRequested() },
            onPrepareRequested = { onPrepareRequested() },
            onDiagnosticsRequested = { showDiagnosticsDialog() },
        )
        startScreenControls.bind()

        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        checkForResumeSnapshot()
    }

    private fun setupRecyclerViews() {
        // The player list is now the GM's read-only roster of authenticated players.
        // Joining is auto-connect (JOIN button → password → DNS-SD), so tapping a row
        // no longer connects — that path used to bypass password entry entirely.
        playerAdapter = PlayerAdapter { /* roster is display-only */ }
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
        // JOIN: password-only prompt → dial the host on the hotspot we're already joined to.
        // No runtime permission needed: we don't scan, we just open a TCP socket.
        binding.joinGameButton.setOnClickListener {
            PasswordPromptDialogFragment { password ->
                gameViewModel.join(password)
            }.show(supportFragmentManager, "JoinDialog")
        }

        binding.addVideoButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*"))
        }

        // Prepare mode: persist the current (playlist + password) pair, or exit.
        binding.prepareSaveButton.setOnClickListener { savePreparedGame() }
        binding.prepareDoneButton.setOnClickListener { onPrepareDone() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gmControls.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // --- Undercover GM hotspots (start screen) ---

    /** CREATE (double-tap top-right): password prompt identical to JOIN, then start
     *  the game matching that password. See GameViewModel.createGameForPassword. */
    private fun onCreateRequested() {
        PasswordPromptDialogFragment { password ->
            gameViewModel.createGameForPassword(password)
        }.show(supportFragmentManager, "CreateDialog")
    }

    /**
     * DIAGNOSTICS (long-press bottom-left): show what this device sees on the network.
     *
     * The fleet is open-ended and the real game master is a phone we can't test beforehand,
     * so the app has to explain its own failures on site. The text is selectable and can be
     * copied, which turns "it wouldn't join" into a report a tester can send back without adb.
     */
    private fun showDiagnosticsDialog() {
        val output = android.widget.TextView(this).apply {
            text = "Collecting…"
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(48, 32, 48, 32)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(android.widget.ScrollView(this).apply { addView(output) })
            .setPositiveButton("Copy", null)   // set below so it doesn't dismiss
            .setNegativeButton("Close", null)
            .show()

        lifecycleScope.launch {
            val report = try {
                gameViewModel.collectDiagnostics().format()
            } catch (e: Exception) {
                "Diagnostics failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            output.text = report
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", report))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** PREPARE (long-press top-left): manage prepared (playlist + password) pairs. */
    private fun onPrepareRequested() {
        val names = gameViewModel.listPreparedGames()
        val items = (names + "New game…").toTypedArray()
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Prepared games")
            .setItems(items) { _, which ->
                if (which == names.size) {
                    promptNewPreparedGame()
                } else {
                    val name = names[which]
                    val game = gameViewModel.loadPreparedGameIntoEditor(name)
                    editingPreparedName = name
                    binding.preparePassword.setText(game?.password ?: "")
                    gameViewModel.setPrepareMode(true)
                }
            }
            .setNegativeButton("Cancel", null)
        if (names.isNotEmpty()) {
            builder.setNeutralButton("Delete…") { _, _ -> showDeletePreparedGameDialog(names) }
        }
        builder.show()
    }

    private fun promptNewPreparedGame() {
        val input = android.widget.EditText(this).apply {
            hint = "Game name"
            setSingleLine()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New prepared game")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    editingPreparedName = name
                    gameViewModel.beginNewPreparedGame()
                    binding.preparePassword.setText("")
                    gameViewModel.setPrepareMode(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePreparedGameDialog(names: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete prepared game")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                gameViewModel.deletePreparedGame(name)
                Toast.makeText(this, "Deleted \"$name\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Save the prepared game being edited. Returns true on success, false if it couldn't
     *  save (no game in progress, or no password) — the caller uses this to decide whether
     *  it's safe to leave prepare mode. */
    private fun savePreparedGame(): Boolean {
        val name = editingPreparedName
        if (name.isNullOrEmpty()) {
            Toast.makeText(this, "No game being edited", Toast.LENGTH_SHORT).show()
            return false
        }
        val password = binding.preparePassword.text.toString().trim()
        if (password.isEmpty()) {
            Toast.makeText(this, "Set a password first", Toast.LENGTH_SHORT).show()
            return false
        }
        gameViewModel.prepareGame(name, password, gameViewModel.videos.value ?: emptyList())
        Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
        return true
    }

    /** "Done": leave prepare mode, but never silently drop unsaved work. */
    private fun onPrepareDone() {
        val name = editingPreparedName
        val password = binding.preparePassword.text.toString().trim()
        if (name == null || !gameViewModel.preparedGameHasUnsavedChanges(name, password)) {
            exitPrepareMode()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Save this game before leaving?")
            // Only leave if the save actually succeeds (e.g. a password is set).
            .setPositiveButton("Save") { _, _ -> if (savePreparedGame()) exitPrepareMode() }
            .setNegativeButton("Discard") { _, _ -> exitPrepareMode() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun exitPrepareMode() {
        editingPreparedName = null
        gameViewModel.setPrepareMode(false)
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
            updateUi()
            if (isStarted) {
                ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
                val current = gameViewModel.playbackController.currentIntent()
                videoAdapter.setCurrent(current.videoIndex, current.isPlaying)
            } else {
                stopService(Intent(this, ConnectionService::class.java))
                videoAdapter.setCurrent(-1, false)
            }
        })

        // prepareMode has an initial value (false), so this observer also fires once on
        // registration — that is what drives showStartScreen() on a cold start, since
        // isGameStarted never emits at launch.
        gameViewModel.prepareMode.observe(this, Observer { updateUi() })

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
            // Launching ACTION_REQUEST_ENABLE throws SecurityException on Android 12+ unless
            // BLUETOOTH_CONNECT is *granted*, and the app never requests it — that crashed
            // the app on first run whenever Bluetooth happened to be off.
            //
            // We deliberately don't ask for the permission: this prompt is only a courtesy
            // reminder for the game master's presenter remote. A presenter is paired in
            // system settings and delivers HID key events straight to the focused window, so
            // the app needs no Bluetooth permission for it to work. Skipping the reminder
            // costs nothing, and it keeps players from seeing a permission dialog they'd
            // never need.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) return@Observer
            try {
                enableBluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                Log.w("MainActivity", "Could not prompt to enable Bluetooth", e)
            }
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
                    // The playlist URI swap is driven losslessly from the repository's
                    // onFileReceived callback — NOT here. This LiveData coalesces bursts
                    // and would drop Success events, so this branch is cosmetic only.
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

    /** Player-facing start screen: only JOIN visible, plus the invisible GM hotspots. */
    private fun showStartScreen() {
        binding.errorBanner.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.GONE
        binding.prepareHeader.visibility = View.GONE
        binding.listsContainer.visibility = View.GONE
        binding.buttonBar.visibility = View.GONE
        binding.joinPanel.visibility = View.VISIBLE            // centred JOIN
        binding.connectivityIndicator.visibility = View.VISIBLE
        binding.invisibleResumeButton.visibility = View.GONE
        // Undercover GM affordances live only here.
        binding.prepareHotspot.visibility = View.VISIBLE
        binding.createHotspot.visibility = View.VISIBLE
        binding.diagnosticsHotspot.visibility = View.VISIBLE
        binding.playerView.useController = false
        gmControls.hideOverlay()
        lightsAndScreen.resetToLobbyDefaults()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Restore action bar and system bars
        supportActionBar?.show()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())

        binding.joinGameButton.announceForAccessibility("Start screen.")
    }

    /** GM-only prepare editor (opened via the invisible PREPARE hotspot, ahead of time):
     *  edit the playlist + password of a prepared game. */
    private fun showPrepareScreen() {
        videoAdapter.editable = true
        videoAdapter.notifyDataSetChanged()

        binding.errorBanner.visibility = View.GONE
        binding.mainContent.visibility = View.VISIBLE
        binding.playerContainer.visibility = View.GONE
        binding.prepareHeader.text = editingPreparedName?.let { "Prepare: $it" } ?: "Prepare game"
        binding.prepareHeader.visibility = View.VISIBLE
        binding.listsContainer.visibility = View.VISIBLE
        // Prepare mode edits one playlist — hide the (empty) player column so the
        // playlist spans full width. player_list/divider are restored for the in-game roster.
        binding.playerList.visibility = View.GONE
        binding.listsDivider.visibility = View.GONE
        binding.videoPlaylist.visibility = View.VISIBLE
        binding.joinPanel.visibility = View.GONE              // no JOIN while preparing
        binding.buttonBar.visibility = View.VISIBLE
        binding.gmToolsRow.visibility = View.VISIBLE          // + Video
        binding.prepareRow.visibility = View.VISIBLE          // password + Save game + Done
        binding.connectivityIndicator.visibility = View.GONE
        binding.invisibleResumeButton.visibility = View.GONE
        // Hotspots off in prepare mode so a corner tap can't re-trigger create/prepare.
        binding.prepareHotspot.visibility = View.GONE
        binding.createHotspot.visibility = View.GONE
        binding.diagnosticsHotspot.visibility = View.GONE
        binding.playerView.useController = false
        gmControls.hideOverlay()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        supportActionBar?.show()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())
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
        binding.prepareHeader.visibility = View.GONE
        binding.joinPanel.visibility = View.GONE
        binding.listsContainer.visibility = View.GONE
        // Restore the player column so the GM's in-game roster toggle (gm_playlist_button)
        // shows both players and playlist — prepare mode hides player_list.
        binding.playerList.visibility = View.VISIBLE
        binding.listsDivider.visibility = View.VISIBLE
        binding.buttonBar.visibility = View.GONE
        binding.connectivityIndicator.visibility = View.GONE
        binding.playerView.useController = false
        binding.playerView.videoSurfaceView?.visibility = View.GONE
        gmControls.hideOverlay()
        binding.invisibleResumeButton.visibility = if (isGameMaster) View.VISIBLE else View.GONE
        // Start-screen hotspots must not intercept in-game touches (a corner double-tap
        // would otherwise pop the create dialog mid-game).
        binding.prepareHotspot.visibility = View.GONE
        binding.createHotspot.visibility = View.GONE
        binding.diagnosticsHotspot.visibility = View.GONE
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

    private fun updateUi() {
        when {
            gameViewModel.isGameStarted.value == true -> showGame()
            gameViewModel.prepareMode.value == true -> showPrepareScreen()
            else -> showStartScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        playbackView.onResume()
    }

    override fun onPause() {
        super.onPause()
        playbackView.onPause()
    }
}
