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
 *
 * The hotspots are only VISIBLE on the start screen (gated in MainActivity.showStartScreen);
 * a GONE view receives no touches, so they can't fire in-game or in prepare mode.
 */
class StartScreenControlsDelegate(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val onCreateRequested: () -> Unit,
    private val onPrepareRequested: () -> Unit,
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
    }
}
