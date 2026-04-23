package com.ismartcoding.plain.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.ismartcoding.lib.logcat.LogCat

/**
 * Device Admin receiver. Once activated by the user (Settings > Security >
 * Device admin apps), Android raises this app's process priority and prevents
 * casual uninstall — both of which contribute to keep-alive on aggressive
 * OEM ROMs (Xiaomi/Realme/Oppo/Vivo/Samsung).
 *
 * No policies are enforced; this is purely for the priority bump.
 */
class PlainDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        LogCat.d("PlainDeviceAdminReceiver enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        LogCat.d("PlainDeviceAdminReceiver disabled")
    }
}
