package com.project01.ui

import android.hardware.camera2.CameraManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.project01.R
import com.project01.databinding.ActivityMainBinding
import com.project01.session.AdvancedCommand
import com.project01.session.AdvancedCommandType

/**
 * Owns the on-device "ambient" state: screen brightness, torch, and the
 * lobby-button labels that reflect that state. The View calls
 * [handleAdvancedCommand] when an [AdvancedCommand] arrives on the wire (or
 * is issued locally), and [resetToLobbyDefaults] when the session ends.
 *
 * The torch/screen state is exposed to GM control code via [isLightsOff] so
 * the single GM "light" button has the right toggle direction.
 */
class LightsAndScreenDelegate(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {
    var isScreenOff: Boolean = false
        private set
    var isTorchOn: Boolean = false
        private set

    /** True iff either the screen is off or the torch is off — i.e. the world is "dark". */
    fun isLightsOff(): Boolean = isScreenOff || !isTorchOn

    fun handleAdvancedCommand(command: AdvancedCommand) {
        when (command.type) {
            AdvancedCommandType.TURN_OFF_SCREEN -> applyScreenOff()
            AdvancedCommandType.TURN_ON_SCREEN -> applyScreenOn()
            AdvancedCommandType.DEACTIVATE_TORCH -> applyTorchOff()
            AdvancedCommandType.ACTIVATE_TORCH -> applyTorchOn()
            AdvancedCommandType.LIGHTS_OFF -> { applyScreenOff(); applyTorchOff() }
            AdvancedCommandType.LIGHTS_ON -> { applyScreenOn(); applyTorchOn() }
        }
        updateGmLightButton()
    }

    /** Restore lobby defaults: screen on at system brightness, torch off, labels reset. */
    fun resetToLobbyDefaults() {
        isScreenOff = false
        isTorchOn = false
        binding.blackOverlay.visibility = View.GONE
        binding.turnOffScreenButton.text = "Screen"
        binding.deactivateTorchButton.text = "Torch"
        updateGmLightButton()
        setScreenBrightness(-1f)
    }

    private fun applyScreenOff() {
        isScreenOff = true
        binding.blackOverlay.visibility = View.VISIBLE
        setScreenBrightness(0f)
        binding.turnOffScreenButton.text = "Screen On"
    }

    private fun applyScreenOn() {
        isScreenOff = false
        binding.blackOverlay.visibility = View.GONE
        setScreenBrightness(-1f)
        binding.turnOffScreenButton.text = "Screen"
    }

    private fun applyTorchOff() {
        isTorchOn = false
        binding.deactivateTorchButton.text = "Torch"
        setTorchMode(false)
    }

    private fun applyTorchOn() {
        isTorchOn = true
        binding.deactivateTorchButton.text = "Torch Off"
        setTorchMode(true)
    }

    private fun updateGmLightButton() {
        val lightsOn = !isLightsOff()
        binding.gmLightButton.setImageResource(
            if (lightsOn) R.drawable.ic_lightbulb_overlay
            else R.drawable.ic_lightbulb_outline_overlay
        )
    }

    private fun setScreenBrightness(brightness: Float) {
        val params = activity.window.attributes
        params.screenBrightness = brightness // 0f = minimum, -1f = system default
        activity.window.attributes = params
    }

    private fun setTorchMode(enabled: Boolean) {
        val cameraManager = activity.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        if (cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, enabled)
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to change torch: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(activity, "No flash available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
