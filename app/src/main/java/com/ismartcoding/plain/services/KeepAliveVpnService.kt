package com.ismartcoding.plain.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.ismartcoding.lib.logcat.LogCat

/**
 * Sink VpnService used purely as an "always-on VPN" trick to keep the PlainApp
 * process alive. Routes nothing — it just establishes a local TUN interface so
 * the OS treats this app as a critical always-on VPN provider and stops the
 * OEM background killers from terminating us.
 *
 * Enable via Settings > Network > VPN > PlainApp Keep-Alive > Always-on VPN.
 */
class KeepAliveVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun == null) {
            try {
                tun = Builder()
                    .setSession("PlainApp Keep-Alive")
                    .addAddress("10.111.222.1", 32)
                    // Don't add any routes — we don't actually carry traffic.
                    // Exclude our own package so PlainApp's own networking is unaffected.
                    .addDisallowedApplication(packageName)
                    .setBlocking(false)
                    .establish()
                LogCat.d("KeepAliveVpnService established sink TUN, fd=${tun?.fd}")
            } catch (t: Throwable) {
                LogCat.e("KeepAliveVpnService establish failed: ${t.message}")
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        LogCat.d("KeepAliveVpnService revoked by user / system")
        closeTun()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
    }
}
