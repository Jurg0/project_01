package com.project01.ui

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM — the media3 constants are compile-time ints, so no Robolectric and no ExoPlayer.
 *
 * Every case here is a step of the field failure of 2026-08-17: let video 2 of 3 run to its end,
 * press Play, and the players froze on a still frame of video 2 while the game master played
 * video 3 — then video 3 never returned to the blue safe-screen.
 */
class EndOfVideoTrackerTest {

    private val tracker = EndOfVideoTracker()

    @Test
    fun `a fresh tracker is not at the end of anything`() {
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `mid-video playback is not the end of a video`() {
        tracker.onPlaybackStateChanged(Player.STATE_BUFFERING)
        tracker.onPlaybackStateChanged(Player.STATE_READY)
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `the end of a non-last video is recognised even though the state stays READY`() {
        // THE BUG. Measured on an S9: at the end of a non-last item ExoPlayer reports
        // playWhenReady=false with END_OF_MEDIA_ITEM and stays in STATE_READY. Asking for
        // STATE_ENDED answered "no", so the game master's Play press resumed the finished
        // video instead of advancing to the next one.
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertTrue(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `the end of the last video is recognised from STATE_ENDED`() {
        tracker.onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue(tracker.isAtEndOfVideo(Player.STATE_ENDED))
    }

    @Test
    fun `the commanded pause that shows the blue screen does not erase the end of the video`() {
        // The exact gap the bug lived in: a finished video reports a pause, intent flips to
        // paused so the safe-screen shows, and the reconciler applies that pause. If that
        // cleared the flag, the following Play press would resume the finished video again.
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        tracker.onIntentApplied(seeked = false, isPlaying = false)
        assertTrue(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `advancing to the next video clears it`() {
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        tracker.onIntentApplied(seeked = true, isPlaying = true)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `resuming playback without a seek clears it`() {
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        tracker.onIntentApplied(seeked = false, isPlaying = true)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `a seek away while still paused clears it`() {
        // Previous, or a drift correction, moves off the finished video without playing.
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        tracker.onIntentApplied(seeked = true, isPlaying = false)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `a new playlist clears it`() {
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        tracker.onPlaylistReplaced()
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `the whole failing sequence ends up advancing`() {
        // video 2 plays...
        tracker.onPlaybackStateChanged(Player.STATE_READY)
        tracker.onIntentApplied(seeked = true, isPlaying = true)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
        // ...runs out on its own...
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        // ...the blue safe-screen is commanded...
        tracker.onIntentApplied(seeked = false, isPlaying = false)
        // ...and the game master presses Play: this must say "advance", not "resume".
        assertTrue(tracker.isAtEndOfVideo(Player.STATE_READY))
        // ...which seeks to video 3 and plays it.
        tracker.onIntentApplied(seeked = true, isPlaying = true)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }

    @Test
    fun `an audio focus pause is not the end of a video`() {
        // Only END_OF_MEDIA_ITEM means finished; a phone call or a notification must not make
        // the next Play press skip the rest of the video.
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
        tracker.onPlayWhenReadyChanged(Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG)
        assertFalse(tracker.isAtEndOfVideo(Player.STATE_READY))
    }
}
