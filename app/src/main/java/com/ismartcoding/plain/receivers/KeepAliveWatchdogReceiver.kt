package com.ismartcoding.plain.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.preferences.CloudflareTunnelEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveVpnEnabledPreference
import com.ismartcoding.plain.preferences.KeepAliveWatchdogEnabledPreference
import com.ismartcoding.plain.services.CloudflareTunnelManager
import com.ismartcoding.plain.services.CloudflareTunnelService
import com.ismartcoding.plain.services.KeepAliveVpnService
import com.ismartcoding.plain.services.TunnelLogger

/**
 * Periodic AlarmManager watchdog. Wakes every ~5 minutes (even in Doze when
 * SCHEDULE_EXACT_ALARM is granted) and re-launches anything that should be
 * running but isn't. Self-reschedules on every fire.
 */
class KeepAliveWatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.ismartcoding.plain.action.KEEPALIVE_WATCHDOG"
        const val INTERVAL_MS = 5 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pending(context)
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
                LogCat.d("KeepAliveWatchdog scheduled in ${INTERVAL_MS}ms")
            } catch (t: Throwable) {
                LogCat.e("KeepAliveWatchdog schedule failed: ${t.message}")
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(pending(context))
        }

        private fun pending(context: Context): PendingIntent {
            val i = Intent(context, KeepAliveWatchdogReceiver::class.java).apply { action = ACTION }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0xA11FE, i, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        LogCat.d("KeepAliveWatchdog fired")
        val pending = goAsync()
        coIO {
            try { run(context.applicationContext) } finally {
                schedule(context.applicationContext)
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context) {
        // 1) Cloudflare Tunnel — restart if it should be on but is dead.
        val tunnelEnabled = CloudflareTunnelEnabledPreference.getAsync(context)
        if (tunnelEnabled && !CloudflareTunnelService.isRunning()) {
            TunnelLogger.init(context)
            TunnelLogger.i("watchdog", "Tunnel was dead — restarting")
            try { CloudflareTunnelManager.start(context) } catch (t: Throwable) {
                TunnelLogger.e("watchdog", "restart failed: ${t.message}")
            }
        }

        // 2) Sink VPN — restart if user has enabled the keep-alive VPN.
        val vpnEnabled = KeepAliveVpnEnabledPreference.getAsync(context)
        if (vpnEnabled && VpnService.prepare(context) == null) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveVpnService::class.java),
                )
            } catch (t: Throwable) {
                LogCat.w("KeepAliveWatchdog vpn start failed: ${t.message}")
            }
        }

        // 3) Reschedule self only if watchdog still enabled.
        if (!KeepAliveWatchdogEnabledPreference.getAsync(context)) {
            cancel(context)
        }
    }
}
