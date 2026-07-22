package com.project01.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {

    private fun buildController(
        isGameMaster: Boolean = true,
        clock: () -> Long = { 0L },
        captureBroadcasts: MutableList<GameMessage> = mutableListOf(),
    ): Pair<PlaybackController, MutableList<GameMessage>> {
        val network = TestNetworkManager().apply {
            onBroadcast = { msg -> captureBroadcasts.add(msg) }
        }
        val sync = GameSync(network)
        val scope = TestScope(UnconfinedTestDispatcher())
        val controller = PlaybackController(
            gameSync = sync,
            scope = scope,
            isGameMaster = { isGameMaster },
            clock = clock,
        )
        return controller to captureBroadcasts
    }

    @Test
    fun `play updates intent to playing at given index`() = runTest {
        val (controller, broadcasts) = buildController()

        controller.play(2, positionMs = 1000L)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(1000L, intent.positionMs)
        assertTrue(intent.isPlaying)
        // GM should broadcast a PlaybackCommand with the same intent
        assertEquals(1, broadcasts.size)
        val cmd = broadcasts[0] as PlaybackCommand
        assertEquals(PlaybackCommandType.PLAY_PAUSE, cmd.type)
        assertEquals(2, cmd.videoIndex)
        assertEquals(1000L, cmd.playbackPosition)
        assertTrue(cmd.playWhenReady)
    }

    @Test
    fun `pause flips intent isPlaying and broadcasts`() = runTest {
        val (controller, broadcasts) = buildController()
        controller.play(0)
        broadcasts.clear()

        controller.pause()

        val intent = controller.currentIntent()
        assertFalse(intent.isPlaying)
        assertEquals(1, broadcasts.size)
        val cmd = broadcasts[0] as PlaybackCommand
        assertFalse(cmd.playWhenReady)
    }

    @Test
    fun `previous decrements index and starts at zero position`() = runTest {
        val (controller, _) = buildController()
        controller.play(3, positionMs = 5000L)

        controller.previous(playlistSize = 5)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `previous clamps at zero`() = runTest {
        val (controller, _) = buildController()
        controller.play(0)

        controller.previous(playlistSize = 5)

        assertEquals(0, controller.currentIntent().videoIndex)
    }

    @Test
    fun `previous is no-op for empty playlist`() = runTest {
        val (controller, broadcasts) = buildController()
        broadcasts.clear()

        controller.previous(playlistSize = 0)

        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `advanceOrResume while playing advances and pauses on blue`() = runTest {
        val (controller, _) = buildController()
        controller.play(1, positionMs = 4000L)

        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = false)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertFalse(intent.isPlaying)
    }

    @Test
    fun `advanceOrResume while paused resumes current item at observed position`() = runTest {
        val (controller, _) = buildController()
        controller.play(1, positionMs = 2000L)
        controller.onPlayerTransition(1, 4500L, true)  // position drifted forward
        controller.pause()

        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = false)

        val intent = controller.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertEquals(4500L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `advanceOrResume while paused at end-of-current advances to next at zero`() = runTest {
        val (controller, _) = buildController()
        controller.play(1, positionMs = 0L)
        controller.pause()

        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = true)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `advanceOrResume while playing clamps at last index`() = runTest {
        val (controller, _) = buildController()
        controller.play(4)  // last index in a 5-item playlist

        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = false)

        assertEquals(4, controller.currentIntent().videoIndex)
    }

    @Test
    fun `applyFromWire updates intent without broadcasting`() = runTest {
        val (controller, broadcasts) = buildController()
        broadcasts.clear()

        controller.applyFromWire(
            PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 3, playbackPosition = 6000L, playWhenReady = true)
        )

        val intent = controller.currentIntent()
        assertEquals(3, intent.videoIndex)
        assertEquals(6000L, intent.positionMs)
        assertTrue(intent.isPlaying)
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `applyFromWire with default videoIndex preserves index and updates play flag`() = runTest {
        val (controller, _) = buildController()
        controller.applyFromWire(
            PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 2, playbackPosition = 1000L, playWhenReady = true)
        )
        // Now receive a sentinel command (PlaybackCommand's videoIndex defaults to -1)
        controller.applyFromWire(PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, playWhenReady = false))

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertFalse(intent.isPlaying)
    }

    @Test
    fun `applyDriftCorrection within threshold returns null and leaves intent unchanged`() = runTest {
        val (controller, _) = buildController()
        controller.play(0, positionMs = 5000L)
        controller.onPlayerTransition(0, 5500L, true)
        val intentBefore = controller.currentIntent()

        // Drift of 200ms is well below threshold
        val seek = controller.applyDriftCorrection(
            PlaybackState(videoIndex = 0, playbackPosition = 5700L, playWhenReady = true)
        )

        assertNull(seek)
        assertEquals(intentBefore, controller.currentIntent())
    }

    @Test
    fun `applyDriftCorrection over threshold updates intent positionMs so reconciler seeks`() = runTest {
        val (controller, _) = buildController()
        controller.play(0, positionMs = 5000L)

        val seek = controller.applyDriftCorrection(
            PlaybackState(videoIndex = 0, playbackPosition = 10_000L, playWhenReady = true)
        )

        // Return value still indicates the corrected position for callers/tests
        // that want to introspect the decision.
        assertEquals(10_000L, seek)
        // The real-world fix: intent must emit so PlaybackViewDelegate's
        // reconciler picks up the seek. Index and isPlaying are untouched.
        val intent = controller.currentIntent()
        assertEquals(0, intent.videoIndex)
        assertEquals(10_000L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `applyDriftCorrection ignores state that disagrees with current intent`() = runTest {
        val (controller, _) = buildController()
        controller.play(0)

        val seek = controller.applyDriftCorrection(
            PlaybackState(videoIndex = 2, playbackPosition = 1000L, playWhenReady = false)
        )

        // PlaybackCommand is the only authority on videoIndex/isPlaying; a
        // disagreeing drift state means a stale broadcast raced a newer
        // command and must be ignored.
        assertNull(seek)
        val intent = controller.currentIntent()
        assertEquals(0, intent.videoIndex)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `stale drift state arriving after a play-pause command does not revert intent`() = runTest {
        // Defends against a class of wire-order race: a stale drift state
        // arriving after a fresh play-pause command must not revert intent.
        // The single-threaded send dispatcher in SocketNetworkManager now
        // makes the on-wire order match caller-order, but ignoring the
        // disagreement is a cheap second line of defense.
        val (controller, _) = buildController(isGameMaster = false)
        controller.applyFromWire(
            PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 1, playbackPosition = 0L, playWhenReady = false)
        )

        val seek = controller.applyDriftCorrection(
            PlaybackState(videoIndex = 0, playbackPosition = 5000L, playWhenReady = true)
        )

        assertNull(seek)
        val intent = controller.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertFalse(intent.isPlaying)
    }

    @Test
    fun `broadcastDriftSync sends PlaybackState only when game master and playing`() = runTest {
        val (controller, broadcasts) = buildController(isGameMaster = true)
        controller.play(1, positionMs = 1000L)
        controller.onPlayerTransition(1, 1500L, true)
        broadcasts.clear()

        controller.broadcastDriftSync()

        assertEquals(1, broadcasts.size)
        val state = broadcasts[0] as PlaybackState
        assertEquals(1, state.videoIndex)
        assertEquals(1500L, state.playbackPosition)
        assertTrue(state.playWhenReady)
    }

    @Test
    fun `broadcastDriftSync skipped when not playing`() = runTest {
        val (controller, broadcasts) = buildController(isGameMaster = true)
        controller.play(0)
        controller.pause()
        broadcasts.clear()

        controller.broadcastDriftSync()

        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `broadcastDriftSync skipped when not game master`() = runTest {
        val (controller, broadcasts) = buildController(isGameMaster = false)
        controller.play(0)
        // Note: play() also skipped broadcasting since isGameMaster=false
        broadcasts.clear()

        controller.broadcastDriftSync()

        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `onPlayerTransition matching intent does not broadcast`() = runTest {
        var now = 0L
        val (controller, broadcasts) = buildController(clock = { now })
        controller.play(1)
        // Advance clock past the grace window so we're not suppressed by it
        now = PlaybackController.COMMAND_GRACE_MS + 1
        broadcasts.clear()

        controller.onPlayerTransition(1, 2000L, true)

        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `onPlayerTransition divergent from intent broadcasts and updates intent`() = runTest {
        var now = 0L
        val (controller, broadcasts) = buildController(clock = { now })
        controller.play(1)
        now = PlaybackController.COMMAND_GRACE_MS + 1
        broadcasts.clear()

        // Natural end-of-video pause: ExoPlayer transitions to !isPlaying while intent says playing
        controller.onPlayerTransition(1, 10_000L, false)

        val intent = controller.currentIntent()
        assertFalse(intent.isPlaying)
        assertEquals(10_000L, intent.positionMs)
        assertEquals(1, broadcasts.size)
        val cmd = broadcasts[0] as PlaybackCommand
        assertFalse(cmd.playWhenReady)
    }

    @Test
    fun `onPlayerTransition never starts playback when intent is paused`() = runTest {
        // Field repro (GM+player desync): GM presses Play/Next mid-video.
        // advanceOrResume commits (2, 0, isPlaying=false) — advance to the next
        // item, pause on the blue safe-screen. The reconciler's seek then makes
        // ExoPlayer briefly report the next item as *playing*, and that listener
        // fire can land OUTSIDE the grace window. It must not flip intent back to
        // playing — doing so made the GM play the next video while the player,
        // which suppressed the same transient inside its later-started grace
        // window, stayed on the blue safe-screen.
        var now = 0L
        val (controller, broadcasts) = buildController(clock = { now })
        controller.play(1)
        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = false) // -> (2, 0, false)
        now = PlaybackController.COMMAND_GRACE_MS + 1 // past the grace window
        broadcasts.clear()

        controller.onPlayerTransition(index = 2, positionMs = 300L, isPlaying = true)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertFalse("listener must never start playback from a paused intent", intent.isPlaying)
        assertTrue("no re-broadcast for a spurious listener-driven play", broadcasts.isEmpty())
    }

    @Test
    fun `end-of-video pause then play advances to and plays the next video`() = runTest {
        // Field regression: at a video's end, pauseAtEndOfMediaItems leaves
        // ExoPlayer in STATE_ENDED with playWhenReady still true. The view now
        // reports that end as a pause (isPlaying=false) via onPlaybackStateChanged,
        // which must flip intent to paused — otherwise the next Play press reads
        // current.isPlaying==true and takes advanceOrResume's "advance & pause on
        // blue" branch, leaving the next video stuck (blue) on both devices.
        var now = 0L
        val (controller, broadcasts) = buildController(clock = { now })
        controller.play(0)
        now = PlaybackController.COMMAND_GRACE_MS + 1 // video 0 plays past the grace window
        broadcasts.clear()

        // Video 0 reaches its end -> reported as a pause at the end position.
        controller.onPlayerTransition(index = 0, positionMs = 30_000L, isPlaying = false)
        assertFalse("end-of-video must flip intent to paused", controller.currentIntent().isPlaying)

        // Press Play (screen control or remote): must advance to video 1 AND play it.
        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = true)
        val intent = controller.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertTrue("the next video must actually start", intent.isPlaying)
    }

    @Test
    fun `player ExoPlayer listener never mutates intent - it follows the wire only`() = runTest {
        // Field regression: on a player device, GM playback did not start at all.
        // A transient "paused" listener callback during a seek/buffer out of a
        // parked item flipped the player's intent to paused (blue), and the
        // never-start-playback guard then blocked the recovering "playing"
        // callback — stranding the player on the blue safe-screen. A player must
        // follow wire intent only; its ExoPlayer listener must not touch intent.
        var now = 1000L
        val (controller, broadcasts) = buildController(isGameMaster = false, clock = { now })
        controller.applyFromWire(
            PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 0, playbackPosition = 0L, playWhenReady = true)
        )
        now += PlaybackController.COMMAND_GRACE_MS + 1 // move past the grace window

        // Transient mid-buffer "paused" report must NOT pause the player.
        controller.onPlayerTransition(index = 0, positionMs = 500L, isPlaying = false)
        assertTrue("player must stay playing; its listener must not pause it", controller.currentIntent().isPlaying)

        // A later "playing" report must also leave the (already-playing) intent be, and never broadcast.
        controller.onPlayerTransition(index = 0, positionMs = 800L, isPlaying = true)
        assertTrue(controller.currentIntent().isPlaying)
        assertTrue("a player never broadcasts", broadcasts.isEmpty())
    }

    @Test
    fun `onPlayerTransition within grace window is suppressed even when divergent`() = runTest {
        var now = 0L
        val (controller, broadcasts) = buildController(clock = { now })
        controller.play(1)
        // Stay inside the grace window
        now = PlaybackController.COMMAND_GRACE_MS - 1
        broadcasts.clear()

        controller.onPlayerTransition(1, 1000L, false)

        assertTrue("Listener-driven broadcast should be suppressed within grace window", broadcasts.isEmpty())
        // Intent should NOT have been updated either — the listener fire is treated as
        // a residual after-effect of the just-issued command.
        assertTrue(controller.currentIntent().isPlaying)
    }

    @Test
    fun `applyFromWire stamps the grace window so a listener fire within it does not revert intent`() = runTest {
        // Player-side: a wire PLAY_PAUSE command arrives, intent updates to
        // (1, 0, true). The reconciler then drives ExoPlayer through a
        // seek-across-items transition out of a pauseAtEndOfMediaItems parked
        // state, and one of the resulting Player.Listener callbacks reads a
        // stale snapshot — e.g., currentMediaItemIndex still reporting 0,
        // playWhenReady still showing the pre-seek false. Without the grace
        // stamp, onPlayerTransition would treat the stale read as a real
        // divergence and revert intent. With it, the window suppresses the
        // feedback so intent stays put and ExoPlayer settles on (1, 0, true).
        var now = 100L
        val (controller, _) = buildController(isGameMaster = false, clock = { now })

        controller.applyFromWire(
            PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 1, playbackPosition = 0L, playWhenReady = true)
        )

        // A listener callback fires inside the grace window with stale state
        // (the player hasn't finished applying the seek yet).
        now += PlaybackController.COMMAND_GRACE_MS - 1
        controller.onPlayerTransition(index = 0, positionMs = 9_000L, isPlaying = false)

        val intent = controller.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `applyFromSnapshot stamps the grace window`() = runTest {
        var now = 100L
        val (controller, _) = buildController(isGameMaster = false, clock = { now })

        controller.applyFromSnapshot(index = 2, positionMs = 5000L, isPlaying = true)
        now += PlaybackController.COMMAND_GRACE_MS - 1
        controller.onPlayerTransition(index = 0, positionMs = 0L, isPlaying = false)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(5000L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `reset clears intent and bookkeeping so a new session starts fresh`() = runTest {
        // Reproduces the cross-session leak: a previous session left intent
        // mid-playback (isPlaying = true), so the next session's first Play
        // press would land in advanceOrResume's "playing → advance to next"
        // branch and skip the first video. reset() must put everything back
        // to the defaults a fresh PlaybackController would have.
        var now = 1000L
        val (controller, _) = buildController(clock = { now })
        controller.play(2, positionMs = 5000L)
        now = 2000L
        controller.onPlayerTransition(2, 7000L, true)

        controller.reset()

        val intent = controller.currentIntent()
        assertEquals(0, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertFalse(intent.isPlaying)
        assertEquals(0L, controller.observedPosition())

        // And the first Play press of the new session must play video 0,
        // not advance to video 1.
        controller.advanceOrResume(playlistSize = 5, atEndOfCurrent = false)
        val afterPlay = controller.currentIntent()
        assertEquals(0, afterPlay.videoIndex)
        assertEquals(0L, afterPlay.positionMs)
        assertTrue(afterPlay.isPlaying)
    }

    @Test
    fun `applyFromSnapshot updates intent without broadcasting`() = runTest {
        val (controller, broadcasts) = buildController()
        broadcasts.clear()

        controller.applyFromSnapshot(index = 2, positionMs = 8000L, isPlaying = true)

        val intent = controller.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(8000L, intent.positionMs)
        assertTrue(intent.isPlaying)
        assertTrue(broadcasts.isEmpty())
    }

    @Test
    fun `non-game-master mutators update intent locally but do not broadcast`() = runTest {
        val (controller, broadcasts) = buildController(isGameMaster = false)
        broadcasts.clear()

        controller.play(1, positionMs = 1000L)

        val intent = controller.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertTrue(intent.isPlaying)
        assertTrue(broadcasts.isEmpty())
    }
}
