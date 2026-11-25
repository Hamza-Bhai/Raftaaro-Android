package io.github.hamzabhai.raftaaro.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Asks for notification permission (Android 13+) the first time a download starts, then carries on
 * whatever the answer. Create it as a field of the activity (it registers a result callback).
 */
class NotificationPermission(private val activity: ComponentActivity) {
    private var then: (() -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        then?.invoke()
        then = null
    }

    fun askThen(action: () -> Unit) {
        val prefs = activity.getSharedPreferences("permissions", Context.MODE_PRIVATE)
        val needed = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needed || prefs.getBoolean("askedNotifications", false)) return action()
        prefs.edit().putBoolean("askedNotifications", true).apply()
        then = action
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
