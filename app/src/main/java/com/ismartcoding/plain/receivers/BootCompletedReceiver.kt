package com.ismartcoding.plain.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.preferences.CloudflareTunnelAutoStartPreference
import com.ismartcoding.plain.preferences.CloudflareTunnelEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveVpnEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveWatchdogEnabledPreference
import com.ismartcoding.plain.services.CloudflareTunnelManager
import com.ismartcoding.plain.services.KeepAliveVpnService

/**
 * Re-arm everything after device reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        LogCat.d("BootCompletedReceiver action=$action")
        val app = context.applicationContext
        val pending = goAsync()
        coIO {
            try {
                if (CloudflareTunnelEnabledPreference.getAsync(app) &&
                    CloudflareTunnelAutoStartPreference.getAsync(app)
                ) {
                    try { CloudflareTunnelManager.start(app) } catch (_: Throwable) {}
                }
                if (KeepAliveVpnEnabledPreference.getAsync(app) && VpnService.prepare(app) == null) {
                    try {
                        ContextCompat.startForegroundService(
                            app, Intent(app, KeepAliveVpnService::class.java),
                        )
                    } catch (_: Throwable) {}
                }
                if (KeepAliveWatchdogEnabledPreference.getAsync(app)) {
                    KeepAliveWatchdogReceiver.schedule(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
