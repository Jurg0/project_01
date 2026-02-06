package com.project01

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.project01.session.CreateGameDialogFragment
import com.project01.session.JoinGameDialogFragment
import com.project01.session.Video
import com.project01.viewmodel.GameViewModel
import java.io.File

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private val gameViewModel: GameViewModel by viewModels()

    private lateinit var playerView: PlayerView
    private lateinit var playerList: RecyclerView
    private lateinit var videoPlaylist: RecyclerView
    private lateinit var createGameButton: Button
    private lateinit var joinGameButton: Button
    private lateinit var addVideoButton: Button
    private lateinit var playPauseButton: Button
    private lateinit var nextButton: Button
    private lateinit var previousButton: Button
    private lateinit var turnOffScreenButton: Button
    private lateinit var deactivateTorchButton: Button
    private lateinit var playerAdapter: PlayerAdapter
    private lateinit var videoAdapter: VideoAdapter
    private var exoPlayer: ExoPlayer? = null
    private lateinit var blackOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player_view)
        playerList = findViewById(R.id.player_list)
        videoPlaylist = findViewById(R.id.video_playlist)
        createGameButton = findViewById(R.id.create_game_button)
        joinGameButton = findViewById(R.id.join_game_button)
        addVideoButton = findViewById(R.id.add_video_button)
        playPauseButton = findViewById(R.id.play_pause_button)
        nextButton = findViewById(R.id.next_button)
        previousButton = findViewById(R.id.previous_button)
        turnOffScreenButton = findViewById(R.id.turn_off_screen_button)
        deactivateTorchButton = findViewById(R.id.deactivate_torch_button)
        blackOverlay = findViewById(R.id.black_overlay)

        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        playerAdapter = PlayerAdapter(emptyList()) { player ->
            gameViewModel.connectToPlayer(player)
        }
        playerList.layoutManager = LinearLayoutManager(this)
        playerList.adapter = playerAdapter

        videoAdapter = VideoAdapter(
            emptyList(),
            false,
            { position -> gameViewModel.moveVideoUp(position) },
            { position -> gameViewModel.moveVideoDown(position) },
            { position -> gameViewModel.removeVideo(position) },
            { video -> gameViewModel.onVideoSelected(video) }
        )
        videoPlaylist.layoutManager = LinearLayoutManager(this)
        videoPlaylist.adapter = videoAdapter
    }

    private fun setupClickListeners() {
        createGameButton.setOnClickListener {
            CreateGameDialogFragment { password ->
                gameViewModel.createGame(password)
            }.show(supportFragmentManager, "CreateGameDialogFragment")
        }

        joinGameButton.setOnClickListener {
            gameViewModel.discoverPeers()
            JoinGameDialogFragment { password ->
                gameViewModel.joinGame(password)
            }.show(supportFragmentManager, "JoinGameDialogFragment")
        }

        addVideoButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, 1)
        }

        playPauseButton.setOnClickListener {
            exoPlayer?.playWhenReady = exoPlayer?.playWhenReady == false
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        nextButton.setOnClickListener {
            exoPlayer?.seekToNextMediaItem()
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        previousButton.setOnClickListener {
            exoPlayer?.seekToPreviousMediaItem()
            exoPlayer?.let {
                gameViewModel.broadcastPlaybackState(it.currentPosition, it.playWhenReady, it.currentMediaItemIndex)
            }
        }

        turnOffScreenButton.setOnClickListener {
            gameViewModel.turnOffScreen()
        }

        deactivateTorchButton.setOnClickListener {
            gameViewModel.deactivateTorch()
        }
    }

    private fun observeViewModel() {
        gameViewModel.players.observe(this, Observer { players ->
            playerAdapter.players = players
            playerAdapter.notifyDataSetChanged()
        })

        gameViewModel.videos.observe(this, Observer { videos ->
            videoAdapter.videos = videos
            videoAdapter.notifyDataSetChanged()
            updatePlayerPlaylist(videos)
        })

        gameViewModel.isGameStarted.observe(this, Observer { isStarted ->
            updateUi(isStarted)
        })

        gameViewModel.toastMessage.observe(this, Observer { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        })

        gameViewModel.thisDevice.observe(this, Observer { device ->
            // Do something with the device info if needed
        })

        gameViewModel.playbackCommand.observe(this, Observer { command ->
            handlePlaybackCommand(command)
        })

        gameViewModel.passwordVerified.observe(this, Observer { isVerified ->
            if (isVerified) {
                // TODO: isGameStarted is already being set in the repository, this is redundant
            } else {
                Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
            }
        })

        gameViewModel.connectivityStatus.observe(this, Observer { status ->
            val connectivityIndicator: android.widget.TextView = findViewById(R.id.connectivity_indicator)
            connectivityIndicator.text = status
        })

        gameViewModel.showVideo.observe(this, Observer {
            playerView.videoSurfaceView?.visibility = View.VISIBLE
        })

        gameViewModel.requestEnableBluetooth.observe(this, Observer {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
        })
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

            videoAdapter.notifyDataSetChanged()

    

            val invisibleResumeButton: View = findViewById(R.id.invisible_resume_button)

            val playbackControls: View = findViewById(R.id.playback_controls)

            val listsContainer: View = findViewById(R.id.lists_container)

    

            playerView.visibility = View.GONE

            playerList.visibility = View.VISIBLE

            videoPlaylist.visibility = View.VISIBLE

            createGameButton.visibility = View.VISIBLE

            joinGameButton.visibility = View.VISIBLE

            addVideoButton.visibility = View.VISIBLE

            turnOffScreenButton.visibility = View.VISIBLE

            deactivateTorchButton.visibility = View.VISIBLE

            playbackControls.visibility = View.VISIBLE

            listsContainer.visibility = View.VISIBLE

            invisibleResumeButton.visibility = View.GONE

        }

    

        private fun showGame() {

            val isGameMaster = gameViewModel.isGameMaster()

            val invisibleResumeButton: View = findViewById(R.id.invisible_resume_button)

            val playbackControls: View = findViewById(R.id.playback_controls)

            val listsContainer: View = findViewById(R.id.lists_container)

    

            playerView.visibility = View.VISIBLE

            playerView.useController = false

            playerView.videoSurfaceView?.visibility = View.GONE

            playerList.visibility = View.GONE

            videoPlaylist.visibility = View.GONE

            createGameButton.visibility = View.GONE

            joinGameButton.visibility = View.GONE

            addVideoButton.visibility = View.GONE

            turnOffScreenButton.visibility = View.GONE

            deactivateTorchButton.visibility = View.GONE

            playbackControls.visibility = View.GONE

            listsContainer.visibility = View.GONE

            invisibleResumeButton.visibility = if (isGameMaster) View.VISIBLE else View.GONE

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            data?.data?.also { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                gameViewModel.addVideo(uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        initializePlayer()
        gameViewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
        gameViewModel.onPause()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView.player = exoPlayer

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

    companion object {
        private const val REQUEST_ENABLE_BT = 2
    }
}

