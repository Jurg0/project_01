package com.project01.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The commanded playback state. ExoPlayer (on both GM and player devices) is
 * reconciled downstream from this — there is no other source of truth.
 */
data class PlaybackIntent(
    val videoIndex: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/**
 * Single source of truth for playback state across GM and players.
 *
 * GM-side mutators (`play`, `pause`, `previous`, `advanceOrResume`) update
 * [intent] and broadcast a [PlaybackCommand]. Player-side, [applyFromWire]
 * updates [intent] from received commands. Both paths converge on the same
 * intent flow, which the View layer collects to drive ExoPlayer.
 *
 * The [Player.Listener] feeds [onPlayerTransition] for natural transitions
 * (end-of-video pauses via `pauseAtEndOfMediaItems`). Within
 * [COMMAND_GRACE_MS] of an external intent change (GM mutator OR
 * wire-received command OR snapshot restore OR drift seek), listener-driven
 * updates are ignored. ExoPlayer fires multiple listener callbacks during a
 * seek-across-items transition out of a `pauseAtEndOfMediaItems` parked
 * state, and any one of them can read a momentarily-stale player state and
 * overwrite the just-applied intent. The grace window suppresses those
 * transient feedback events long enough for ExoPlayer to settle.
 *
 * [PlaybackState] on the wire carries only position for drift correction.
 * It never re-commits index or play/pause to intent.
 */
class PlaybackController(
    private val gameSync: GameSync,
    private val scope: CoroutineScope,
    private val isGameMaster: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _intent = MutableStateFlow(PlaybackIntent())
    val intent: StateFlow<PlaybackIntent> = _intent

    private var lastObservedPositionMs: Long = 0L
    private var lastExplicitCommandAtMs: Long = 0L

    fun currentIntent(): PlaybackIntent = _intent.value
    fun observedPosition(): Long = lastObservedPositionMs

    fun play(index: Int, positionMs: Long = 0L) {
        commitAndBroadcast(PlaybackIntent(index, positionMs, true))
    }

    fun pause() {
        // Pause at the observed position, not the last commanded position —
        // ExoPlayer has been playing through the item, so the commanded
        // position is stale. Clients reconcile to wherever the GM actually
        // is when the pause hits the wire.
        commitAndBroadcast(_intent.value.copy(isPlaying = false, positionMs = lastObservedPositionMs))
    }

    fun previous(playlistSize: Int) {
        if (playlistSize == 0) return
        val target = (_intent.value.videoIndex - 1).coerceAtLeast(0)
        play(target, 0L)
    }

    /**
     * Combined Play/Next button. When playing, advance to the next item start
     * but pause there (blue safe-screen between videos). When paused, resume;
     * if we're parked at the end of the current item, advance first.
     */
    fun advanceOrResume(playlistSize: Int, atEndOfCurrent: Boolean) {
        if (playlistSize == 0) return
        val current = _intent.value
        if (current.isPlaying) {
            val next = (current.videoIndex + 1).coerceAtMost(playlistSize - 1)
            commitAndBroadcast(PlaybackIntent(next, 0L, false))
        } else {
            val target = if (atEndOfCurrent) {
                (current.videoIndex + 1).coerceAtMost(playlistSize - 1)
            } else {
                current.videoIndex
            }
            val position = if (atEndOfCurrent || target != current.videoIndex) 0L else lastObservedPositionMs
            commitAndBroadcast(PlaybackIntent(target, position, true))
        }
    }

    /** Player-side: apply a received command. Updates intent without re-broadcasting. */
    fun applyFromWire(command: PlaybackCommand) {
        if (command.type != PlaybackCommandType.PLAY_PAUSE) return
        val next = if (command.videoIndex == -1) {
            _intent.value.copy(isPlaying = command.playWhenReady)
        } else {
            PlaybackIntent(command.videoIndex, command.playbackPosition, command.playWhenReady)
        }
        _intent.value = next
        lastObservedPositionMs = next.positionMs
        // Stamp the grace timestamp so listener callbacks fired by the
        // reconciler's seek+playWhenReady can't revert intent during the
        // ExoPlayer state transition (especially when crossing out of a
        // pauseAtEndOfMediaItems parked state into a new media item).
        lastExplicitCommandAtMs = clock()
    }

    /**
     * Player-side drift correction. PlaybackState carries only position for
     * routine drift sync. When drift exceeds the threshold, update
     * `intent.positionMs` — the StateFlow emission drives the reconciler's
     * seek. Returns the corrected position (or null if no correction needed)
     * for callers/tests that want to introspect the decision; production
     * callers can discard it since the seek happens via the intent flow.
     *
     * If the state disagrees with intent on videoIndex or playing flag, ignore
     * it. PlaybackCommand is the only authority on those fields, and a
     * disagreeing state on the wire means a stale drift broadcast raced a
     * newer command (the GM's drift-sync and command broadcasts are queued
     * via separate scope.launches and can be reordered by the IO mutex).
     */
    fun applyDriftCorrection(state: PlaybackState): Long? {
        val current = _intent.value
        if (state.videoIndex != current.videoIndex || state.playWhenReady != current.isPlaying) {
            return null
        }
        val drift = Math.abs(state.playbackPosition - lastObservedPositionMs)
        if (drift <= DRIFT_THRESHOLD_MS) return null
        lastObservedPositionMs = state.playbackPosition
        _intent.value = current.copy(positionMs = state.playbackPosition)
        lastExplicitCommandAtMs = clock()
        return state.playbackPosition
    }

    /** Restore from snapshot. Does not broadcast — caller orchestrates that. */
    fun applyFromSnapshot(index: Int, positionMs: Long, isPlaying: Boolean) {
        _intent.value = PlaybackIntent(index, positionMs, isPlaying)
        lastObservedPositionMs = positionMs
        lastExplicitCommandAtMs = clock()
    }

    /**
     * Reset every piece of state back to its initial value. Called at
     * session-end so the next session starts at PlaybackIntent() rather
     * than inheriting `isPlaying = true` (or a stale position) from the
     * previous session — otherwise the first Play press lands in the
     * "playing → advance to next" branch of [advanceOrResume] and skips
     * over the first video.
     */
    fun reset() {
        _intent.value = PlaybackIntent()
        lastObservedPositionMs = 0L
        lastExplicitCommandAtMs = 0L
    }

    /**
     * Called from ExoPlayer's Player.Listener. If the listener-observed state
     * disagrees with intent (natural end-of-video pause, etc.), and we're
     * outside the post-command grace window, update intent and broadcast.
     * Otherwise this call is purely informational — just record the position.
     */
    fun onPlayerTransition(index: Int, positionMs: Long, isPlaying: Boolean) {
        lastObservedPositionMs = positionMs
        val current = _intent.value
        val sinceExplicit = clock() - lastExplicitCommandAtMs
        if (sinceExplicit < COMMAND_GRACE_MS) return
        if (isPlaying == current.isPlaying && index == current.videoIndex) return
        val next = PlaybackIntent(index, positionMs, isPlaying)
        _intent.value = next
        if (isGameMaster()) {
            scope.launch {
                gameSync.broadcast(
                    PlaybackCommand(
                        PlaybackCommandType.PLAY_PAUSE,
                        next.videoIndex,
                        next.positionMs,
                        next.isPlaying,
                    )
                )
            }
        }
    }

    /**
     * GM-only periodic drift broadcast. Sends PlaybackState carrying position
     * only — clients use it solely for drift correction.
     */
    fun broadcastDriftSync() {
        if (!isGameMaster()) return
        val current = _intent.value
        if (!current.isPlaying) return
        // Snapshot before scope.launch so the broadcast reflects one atomic
        // point-in-time. Reading lastObservedPositionMs inside the launch
        // body lets an explicit mutator slip in between and produce a
        // PlaybackState that mixes the old videoIndex with the new position.
        val message = PlaybackState(current.videoIndex, lastObservedPositionMs, true)
        scope.launch {
            gameSync.broadcast(message)
        }
    }

    private fun commitAndBroadcast(next: PlaybackIntent) {
        _intent.value = next
        lastObservedPositionMs = next.positionMs
        lastExplicitCommandAtMs = clock()
        if (isGameMaster()) {
            scope.launch {
                gameSync.broadcast(
                    PlaybackCommand(
                        PlaybackCommandType.PLAY_PAUSE,
                        next.videoIndex,
                        next.positionMs,
                        next.isPlaying,
                    )
                )
            }
        }
    }

    companion object {
        const val DRIFT_THRESHOLD_MS = 2_000L
        const val COMMAND_GRACE_MS = 500L
        const val PLAYBACK_SYNC_INTERVAL_MS = 5_000L
    }
}
