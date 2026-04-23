package com.ismartcoding.plain.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ismartcoding.plain.Constants

object CloudflareTunnelManager {
    fun start(context: Context) {
        val i = Intent(context, CloudflareTunnelService::class.java).apply {
            action = Constants.ACTION_START_CLOUDFLARE_TUNNEL
        }
        ContextCompat.startForegroundService(context, i)
    }

    fun stop(context: Context) {
        val i = Intent(context, CloudflareTunnelService::class.java).apply {
            action = Constants.ACTION_STOP_CLOUDFLARE_TUNNEL
        }
        try { context.startService(i) } catch (_: Throwable) {}
        try { context.stopService(Intent(context, CloudflareTunnelService::class.java)) } catch (_: Throwable) {}
    }

    fun restart(context: Context) {
        stop(context)
        start(context)
    }
}
