package com.project01.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Holds the runtime-permissions plumbing for [com.project01.MainActivity].
 *
 * The ActivityResultLauncher itself must be created in the Activity (Android
 * requires it to be registered before STARTED), so this helper takes a
 * `launchPermissions` callback instead of owning the launcher. The Activity
 * calls [onPermissionsResult] from its result callback to dispatch the
 * pending action.
 */
class PermissionHelper(
    private val activity: Activity,
    private val launchPermissions: (Array<String>) -> Unit,
) {
    private var pendingActionAfterPermission: (() -> Unit)? = null

    fun onPermissionsResult(results: Map<String, Boolean>) {
        if (results.values.all { it }) {
            pendingActionAfterPermission?.invoke()
        } else {
            Toast.makeText(activity, "Permissions required for this feature", Toast.LENGTH_SHORT).show()
        }
        pendingActionAfterPermission = null
    }

    fun hasPermissions(vararg permissions: String): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requirePermissions(permissions: Array<String>, action: () -> Unit) {
        if (hasPermissions(*permissions)) {
            action()
        } else {
            pendingActionAfterPermission = action
            launchPermissions(permissions)
        }
    }

    fun wifiP2pPermissions(): Array<String> {
        // Android 13+ uses NEARBY_WIFI_DEVICES for Wi-Fi Direct; older versions
        // gate P2P scanning behind ACCESS_FINE_LOCATION (capped at API 32 in the
        // manifest). The app itself never reads physical location.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
