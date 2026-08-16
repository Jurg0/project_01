package com.project01.ui

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.project01.databinding.ActivityMainBinding

/**
 * Wires the two invisible start-screen GM hotspots, mirroring [GmControlsDelegate.bind].
 * The player-facing start screen shows only the JOIN button; these transparent corner
 * targets keep the GM's create/prepare affordances undercover.
 *
 *  - PREPARE = long-press top-left ([ActivityMainBinding.prepareHotspot]) — used at home,
 *    no time pressure; long-press avoids accidental opens while handling the phone.
 *  - CREATE  = double-tap top-right ([ActivityMainBinding.createHotspot]) — on-site; a
 *    double-tap in a corner can't be triggered by a stray single touch.
 *  - DIAGNOSTICS = long-press bottom-left ([ActivityMainBinding.diagnosticsHotspot]).
 *
 * Diagnostics deliberately does NOT share the CREATE corner. GestureDetector arms its
 * long-press timer on ACTION_DOWN, so a first tap held past ~500ms would fire long-press
 * instead of completing the double-tap — putting a page of technical text on screen in front
 * of the players at the exact moment the game master is trying to start the game unnoticed.
 *
 * The hotspots are only VISIBLE on the start screen (gated in MainActivity.showStartScreen);
 * a GONE view receives no touches, so they can't fire in-game or in prepare mode.
 */
class StartScreenControlsDelegate(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onCreateRequested: () -> Unit,
    private val onPrepareRequested: () -> Unit,
    private val onDiagnosticsRequested: () -> Unit = {},
    private val isGameStarted: () -> Boolean = { false },
) {
    fun bind() {
        val createDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onCreateRequested()
                return true
            }
        })
        binding.createHotspot.setOnTouchListener { _, event ->
            createDetector.onTouchEvent(event)
            true
        }

        val prepareDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onPrepareRequested()
            }
        })
        binding.prepareHotspot.setOnTouchListener { _, event ->
            prepareDetector.onTouchEvent(event)
            true
        }

        bindDiagnosticsHold()
    }

    /**
     * Diagnostics: hold the bottom-left corner. In-game it takes TWO fingers, because a phone
     * being carried can rest a palm on a corner; on the start screen the phone is being
     * operated deliberately, so one finger is enough.
     *
     * Hand-rolled rather than GestureDetector: that class **cancels its pending long-press as
     * soon as a second pointer goes down**, so `onLongPress` never fired for a two-finger hold
     * and in-game diagnostics was unreachable (field-reported). Tracking the touch ourselves
     * is the only way to require multiple fingers.
     */
    private fun bindDiagnosticsHold() {
        val handler = Handler(Looper.getMainLooper())
        var maxPointers = 0
        val fire = Runnable {
            if (!isGameStarted() || maxPointers >= 2) onDiagnosticsRequested()
        }
        binding.diagnosticsHotspot.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    maxPointers = event.pointerCount
                    handler.removeCallbacks(fire)
                    handler.postDelayed(fire, HOLD_MS)
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    maxPointers = maxOf(maxPointers, event.pointerCount)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    handler.removeCallbacks(fire)
            }
            true
        }
    }

    private companion object {
        /** Deliberately longer than the system long-press, so a brush against a corner can't
         *  open a page of technical text mid-game. */
        const val HOLD_MS = 900L
    }
}
