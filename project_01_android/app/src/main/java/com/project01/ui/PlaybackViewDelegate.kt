package com.project01.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
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

    fun isAtEndOfCurrent(): Boolean {
        val player = exoPlayer ?: return false
        val duration = player.duration
        return duration > 0 && player.currentPosition >= duration - END_OF_ITEM_THRESHOLD_MS
    }

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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val player = exoPlayer ?: return
                playbackController.onPlayerTransition(
                    player.currentMediaItemIndex,
                    player.currentPosition,
                    player.playWhenReady,
                )
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
        /** Window before the end of an item that counts as "at end of current". */
        private const val END_OF_ITEM_THRESHOLD_MS = 500L
    }
}
