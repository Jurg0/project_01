package com.project01.ui

import androidx.media3.common.Player

/**
 * Whether ExoPlayer is parked at the end of the current video.
 *
 * This exists because **`STATE_ENDED` only happens for the LAST item in the playlist.** With
 * `pauseAtEndOfMediaItems`, every other video ends like this (measured on an S9, media3 1.2.1):
 *
 * ```
 * onPlayWhenReadyChanged(false, END_OF_MEDIA_ITEM)  [index=0 pos=2003 state=READY pwr=false]
 * ```
 *
 * State **READY**, not ENDED, and the index stays on the video that just finished. Asking
 * `playbackState == STATE_ENDED` therefore answered "no" at the end of every video except the
 * last, and the game master's Play button took the *resume* branch instead of *advance*:
 * it commanded "play video 2 again from its end position". Its own ExoPlayer, still parked from
 * its own END_OF_MEDIA_ITEM, rolled forward into video 3 anyway — so the game master looked
 * fine — while the players, which had been given an explicit seek to that end position by the
 * preceding pause command, played out the last few milliseconds, parked again on the same item
 * and sat on a frozen frame of the previous video. Both behaviours measured on an S9.
 *
 * The intent's index then trailed ExoPlayer's by one, so when the last video really did reach
 * STATE_ENDED its end-of-video report was dropped by the index guard in
 * `PlaybackController.onPlayerTransition` and the playlist never returned to the blue
 * safe-screen. All three field symptoms, one cause.
 *
 * Pure logic, no ExoPlayer instance, so the state machine is testable on the JVM.
 */
class EndOfVideoTracker {

    private var parked = false

    /** Feed every `Player.Listener.onPlayWhenReadyChanged`. */
    fun onPlayWhenReadyChanged(reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) parked = true
    }

    /** Feed every `Player.Listener.onPlaybackStateChanged` — this catches the last video. */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) parked = true
    }

    /**
     * Called after the reconciler has applied a commanded intent to ExoPlayer.
     *
     * A seek or a start of playback means we are no longer sitting at the end of a finished
     * video. A commanded *pause* must NOT clear it: the end of a video arrives as a pause
     * (intent flips to paused so the blue safe-screen shows), and the game master's next Play
     * press has to still know that the video was finished — that gap is where the bug lived.
     */
    fun onIntentApplied(seeked: Boolean, isPlaying: Boolean) {
        if (seeked || isPlaying) parked = false
    }

    /** `setMediaItems` resets ExoPlayer's position, so nothing is parked any more. */
    fun onPlaylistReplaced() {
        parked = false
    }

    /** True when the current video has played to its end and is waiting. */
    fun isAtEndOfVideo(playbackState: Int): Boolean =
        parked || playbackState == Player.STATE_ENDED
}
