package com.project01.ui

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

        // Diagnostics is also reachable during a game — that's when the failures worth
        // diagnosing happen (a video that won't start, a device that stopped obeying the
        // host). In-game a phone is being carried, and a palm on the bottom corner would
        // trigger a one-finger long-press, so in-game it requires TWO fingers. On the start
        // screen the phone is being operated deliberately, so one finger is enough.
        var pointersDown = 0
        val diagnosticsDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!isGameStarted() || pointersDown >= 2) onDiagnosticsRequested()
            }
        })
        binding.diagnosticsHotspot.setOnTouchListener { _, event ->
            pointersDown = event.pointerCount
            diagnosticsDetector.onTouchEvent(event)
            true
        }
    }
}
