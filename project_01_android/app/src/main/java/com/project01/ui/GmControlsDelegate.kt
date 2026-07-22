package com.project01.ui

import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.project01.databinding.ActivityMainBinding
import com.project01.session.PlaybackController

/**
 * Owns everything the game master interacts with: the hidden double-tap /
 * long-press gesture on the "invisible resume button", the four GM overlay
 * buttons (Prev / Next / Light / Playlist), the GM overlay's own
 * visibility, and the bluetooth-presenter HID key mapping.
 *
 * The activity's `dispatchKeyEvent` override forwards to [dispatchKeyEvent]
 * and falls through to super only when this returns false. Mid-session
 * non-GM devices (or the lobby) always fall through.
 */
class GmControlsDelegate(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val playbackController: PlaybackController,
    private val playbackView: PlaybackViewDelegate,
    private val lightsAndScreen: LightsAndScreenDelegate,
    private val isGameMaster: () -> Boolean,
    private val isGameStarted: () -> Boolean,
    private val setLights: (Boolean) -> Unit,
    private val onLongPress: () -> Unit,
) {
    var isGmOverlayVisible: Boolean = false
        private set

    /**
     * Wire the GM buttons and the hidden gesture region. Called from
     * MainActivity.onCreate after the binding is inflated.
     */
    fun bind() {
        // invisible_resume_button hides two GM-only gestures so the player UI stays clean:
        //  - double-tap: toggle the GM control overlay
        //  - long-press: confirm End Game (the activity owns the dialog)
        val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleGmOverlay()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }
        })
        binding.invisibleResumeButton.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.gmPreviousButton.setOnClickListener { onGmPrevious() }
        binding.gmNextButton.setOnClickListener { onGmPlayNext() }
        binding.gmLightButton.setOnClickListener { onGmToggleLight() }
        binding.gmPlaylistButton.setOnClickListener {
            val isVisible = binding.listsContainer.visibility == View.VISIBLE
            binding.listsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }

    /** Hide the GM overlay (called from MainActivity.showLobby / showGame). */
    fun hideOverlay() {
        isGmOverlayVisible = false
        binding.gmOverlay.visibility = View.GONE
    }

    private fun toggleGmOverlay() {
        isGmOverlayVisible = !isGmOverlayVisible
        binding.gmOverlay.visibility = if (isGmOverlayVisible) View.VISIBLE else View.GONE
    }

    private fun onGmPrevious() {
        val count = playbackView.mediaItemCount()
        if (count == 0) return
        // Target index comes from the controller's intent (the source of
        // truth), not ExoPlayer's currentMediaItemIndex —
        // seekToPreviousMediaItem() interacts unpredictably with
        // pauseAtEndOfMediaItems when the player is parked at the end of an
        // item, so we never lean on ExoPlayer for the target.
        playbackController.previous(count)
    }

    private fun onGmPlayNext() {
        val count = playbackView.mediaItemCount()
        if (count == 0) return
        // ExoPlayer is the only authority on whether we're parked at the end
        // of the current item (the controller doesn't track durations), so
        // PlaybackViewDelegate samples it and hands us the flag.
        playbackController.advanceOrResume(count, playbackView.isAtEndOfCurrent())
    }

    private fun onGmToggleLight() {
        // "Light off" = total darkness (torch off + screen off). "Light on" = normal.
        // The two outputs are linked so the GM has a single ambient-state switch.
        setLights(lightsAndScreen.isLightsOff())
    }

    /**
     * Bluetooth presenters expose themselves as HID keyboards. Android delivers
     * their key events to the focused window, so the GM just pairs the remote in
     * system settings and the events land here. [presenterActionFor] maps the
     * presenter keys to the GM's Prev / Play-Next / Light actions.
     *
     * Norwii N21 BLE (field-verified via `adb logcat`): page back/forward arrive
     * as DPAD_LEFT / DPAD_RIGHT (its default arrow-key mode) → Prev / Next, and
     * the "Mark" button sends Ctrl+P → toggle Light. Volume +/- are intentionally
     * NOT mapped — they'd fight the GM phone's own media volume during a session.
     *
     * Returns true if the event was consumed; the activity falls back to super
     * dispatch when this returns false.
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isGameMaster() || !isGameStarted()) return false
        val action = presenterActionFor(event.keyCode, event.isCtrlPressed) ?: return false
        // Act on the DOWN edge only; still consume UP/auto-repeat so the OS doesn't act on them.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (action) {
                PresenterAction.NEXT -> onGmPlayNext()
                PresenterAction.PREVIOUS -> onGmPrevious()
                PresenterAction.TOGGLE_LIGHT -> onGmToggleLight()
            }
        }
        return true
    }
}

/** The GM action a presenter key maps to, or null if it isn't a presenter control. */
internal enum class PresenterAction { NEXT, PREVIOUS, TOGGLE_LIGHT }

/**
 * Pure key → action mapping for bluetooth-presenter HID keys. Extracted from
 * [GmControlsDelegate.dispatchKeyEvent] so it's unit-testable without a
 * Robolectric activity/binding. `KeyEvent.KEYCODE_*` are compile-time constants
 * and get inlined, so this runs on a plain JVM.
 *
 * `KEYCODE_P` toggles the light ONLY with Ctrl held — that's the Norwii N21
 * "Mark" button (Ctrl+P). A bare P is left for the system. The lone Ctrl-down
 * event the Mark button fires first falls through to null (not a control).
 */
internal fun presenterActionFor(keyCode: Int, ctrlPressed: Boolean): PresenterAction? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_PAGE_DOWN,
    KeyEvent.KEYCODE_MEDIA_NEXT -> PresenterAction.NEXT
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_PAGE_UP,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PresenterAction.PREVIOUS
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_F5,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PresenterAction.TOGGLE_LIGHT
    KeyEvent.KEYCODE_P -> if (ctrlPressed) PresenterAction.TOGGLE_LIGHT else null
    else -> null
}
