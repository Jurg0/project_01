package com.project01.ui

import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.project01.R
import com.project01.databinding.ActivityMainBinding
import com.project01.session.PlaybackController
import com.project01.session.PlaybackIntent
import com.project01.session.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer lifecycle and the intent-reconciliation coroutine.
 * MainActivity calls [onResume]/[onPause] to mirror Activity lifecycle and
 * [updatePlaylist] when the video list changes.
 *
 * GM control code queries [mediaItemCount] and [isAtEndOfCurrent] to feed
 * the [PlaybackController]'s advance/previous calls — those two facts come
 * from ExoPlayer alone.
 */
class PlaybackViewDelegate(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val playbackController: PlaybackController,
    private val currentVideosProvider: () -> List<Video>?,
) {
    private var exoPlayer: ExoPlayer? = null
    private var intentReconcileJob: Job? = null

    fun mediaItemCount(): Int = exoPlayer?.mediaItemCount ?: 0

    /**
     * True when ExoPlayer is parked at the end of the current item.
     *
     * Reads [Player.STATE_ENDED] directly rather than comparing position against duration:
     * `pauseAtEndOfMediaItems` parks the player in STATE_ENDED at exactly that point, so this
     * is the authoritative signal. The old `position >= duration - 500ms` heuristic misfired
     * on short videos (a 2s clip counts as "at the end" for a quarter of its length) and
     * while a seek was still settling (duration/position briefly belong to different items),
     * which made the GM's Play button advance when it should have resumed — skipping a video
     * and desyncing the group.
     */
    fun isAtEndOfCurrent(): Boolean =
        exoPlayer?.playbackState == Player.STATE_ENDED

    fun onResume() {
        initializePlayer()
    }

    fun onPause() {
        releasePlayer()
    }

    fun updatePlaylist(videos: List<Video>) {
        val mediaItems = videos.map { MediaItem.fromUri(it.uri) }
        exoPlayer?.setMediaItems(mediaItems)
        exoPlayer?.prepare()
    }

    private fun initializePlayer() {
        releasePlayer()
        exoPlayer = ExoPlayer.Builder(activity).build().apply {
            // Game master controls pacing — never auto-advance between videos.
            // Each video ends on the blue safe-screen; GM presses Next to play the next one.
            pauseAtEndOfMediaItems = true
        }
        binding.playerView.player = exoPlayer
        binding.playerView.setShutterBackgroundColor(activity.resources.getColor(R.color.safe_blue, activity.theme))
        binding.playerView.videoSurfaceView?.visibility = View.GONE

        currentVideosProvider()?.let { updatePlaylist(it) }

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // pauseAtEndOfMediaItems parks the player in STATE_ENDED at the
                // end of each video WITH playWhenReady still true — so the
                // playWhenReady we report below can't detect the end. Report the
                // end explicitly as a pause at the current position, so the GM
                // commits + broadcasts the end-of-video blue screen and intent
                // flips to paused. Without this the intent stayed "playing", and
                // the next Play press hit advanceOrResume's "playing → advance &
                // pause on blue" branch, so the next video never started (field bug).
                if (playbackState == Player.STATE_ENDED) {
                    val player = exoPlayer ?: return
                    playbackController.onPlayerTransition(
                        player.currentMediaItemIndex,
                        player.currentPosition,
                        isPlaying = false,
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exoPlayer ?: return
                // Report playWhenReady (not `isPlaying`) on purpose: it stays
                // stable across transient STATE_BUFFERING stalls so a mid-video
                // hiccup doesn't pause the whole group. End-of-video is handled
                // by onPlaybackStateChanged above, not here.
                playbackController.onPlayerTransition(
                    player.currentMediaItemIndex,
                    player.currentPosition,
                    player.playWhenReady,
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                // An unreadable item (a video whose transfer failed or hasn't finished, so
                // the entry is still the game master's content:// URI) throws here and parks
                // ExoPlayer in STATE_IDLE. That state IGNORES seekTo and playWhenReady, so
                // without recovery the device stops obeying the game master for the rest of
                // the session — field-observed: a 200MB video failed to start and the phone
                // never responded to another command. Recovery happens in
                // applyIntentToExoPlayer, which re-prepares on the next command; doing it
                // here would spin in a prepare→error loop on a genuinely broken file.
                Log.w(TAG, "playback error on item ${exoPlayer?.currentMediaItemIndex}", error)
            }
        })

        intentReconcileJob?.cancel()
        intentReconcileJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackController.intent.collect { intent ->
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
        // Heal a player that died on an unreadable item. After an error ExoPlayer sits in
        // STATE_IDLE and silently ignores seekTo/playWhenReady, so every later command from
        // the game master would be dropped. Re-preparing here — on a command rather than on
        // the error — revives it without risking a prepare→error loop on a broken file.
        if (player.playbackState == Player.STATE_IDLE) {
            Log.d(TAG, "player idle after an error — re-preparing for the new command")
            player.prepare()
        }
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
        private const val TAG = "GamePlay"
        /** Tolerance for "this is the same position" — avoids re-seeking on every
         *  intent emission when only the position field updated marginally. */
        private const val SEEK_TOLERANCE_MS = 500L
    }
}
