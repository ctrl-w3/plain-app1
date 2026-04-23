package com.ismartcoding.plain.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.Constants
import com.ismartcoding.plain.MainApp
import com.ismartcoding.plain.R
import com.ismartcoding.plain.helpers.NotificationHelper
import com.ismartcoding.plain.preferences.CloudflareTunnelEnabledPreference
import com.ismartcoding.plain.preferences.CloudflareTunnelTokenPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service that runs the bundled `cloudflared` binary to expose the
 * device's local web server through a Cloudflare Tunnel.
 *
 * The binary is shipped as a native library `libcloudflared.so` per ABI, so
 * Android extracts it to `applicationInfo.nativeLibraryDir` with execute
 * permissions (the only way to ship an executable on modern Android).
 */
class CloudflareTunnelService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1738
        @Volatile var instance: CloudflareTunnelService? = null
        @Volatile var lastError: String = ""
        @Volatile var status: Status = Status.STOPPED

        enum class Status { STOPPED, STARTING, RUNNING, ERROR }

        fun isRunning(): Boolean = instance != null && status == Status.RUNNING
    }

    private var process: Process? = null
    private var watcherJob: Job? = null
    private var logJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        NotificationHelper.ensureDefaultChannel()

        val notification = NotificationHelper.createServiceNotification(
            this,
            Constants.ACTION_STOP_CLOUDFLARE_TUNNEL,
            getString(R.string.cloudflare_tunnel_running),
            getString(R.string.cloudflare_tunnel_running_desc),
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        if (intent?.action == Constants.ACTION_STOP_CLOUDFLARE_TUNNEL) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        watcherJob?.cancel()
        watcherJob = coIO {
            runWithRetry()
        }
        return START_STICKY
    }

    private suspend fun runWithRetry() {
        val token = CloudflareTunnelTokenPreference.getAsync(this).trim()
        if (token.isEmpty()) {
            lastError = getString(R.string.cloudflare_tunnel_no_token)
            status = Status.ERROR
            stopSelf()
            return
        }

        var backoffMs = 2000L
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            status = Status.STARTING
            lastError = ""
            try {
                runOnce(token)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                LogCat.e("cloudflared crashed: $lastError")
                status = Status.ERROR
            }

            val stillEnabled = CloudflareTunnelEnabledPreference.getAsync(MainApp.instance)
            if (!stillEnabled) {
                stopSelf()
                return
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60000L)
        }
    }

    private fun runOnce(token: String) {
        val binary = locateBinary()
            ?: throw IllegalStateException(getString(R.string.cloudflare_tunnel_binary_missing))

        val workDir = File(filesDir, "cloudflared").apply { mkdirs() }

        val cmd = listOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--edge-ip-version", "auto",
            "--protocol", "http2",
            "run",
            "--token", token,
        )
        LogCat.d("cloudflared launch: ${binary.absolutePath} tunnel run --token <hidden>")

        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
        pb.environment()["TUNNEL_LOGFILE"] = File(workDir, "cloudflared.log").absolutePath
        pb.environment()["HOME"] = workDir.absolutePath
        pb.environment()["TMPDIR"] = workDir.absolutePath

        val p = pb.start().also { process = it }

        // Stream output to logcat (and trim cloudflared.log to last few hundred lines).
        logJob?.cancel()
        logJob = coIO {
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val s = line!!
                    LogCat.d("cloudflared| $s")
                    if (status == Status.STARTING && (s.contains("Registered tunnel connection") || s.contains("Connection registered"))) {
                        status = Status.RUNNING
                    }
                }
            }
        }

        val exit = p.waitFor()
        LogCat.d("cloudflared exited with code $exit")
        if (exit != 0 && status != Status.RUNNING) {
            throw RuntimeException("cloudflared exit=$exit")
        }
    }

    private fun locateBinary(): File? {
        // Android extracts native libraries into nativeLibraryDir. We ship cloudflared as
        // libcloudflared.so per ABI so it ends up here with execute permission.
        val libDir = applicationInfo.nativeLibraryDir
        val candidates = listOf("libcloudflared.so", "cloudflared")
        for (name in candidates) {
            val f = File(libDir, name)
            if (f.exists() && f.canExecute()) return f
        }
        // Fallback: maybe extractNativeLibs=false. Try filesDir.
        val fallback = File(filesDir, "cloudflared/cloudflared")
        if (fallback.exists() && fallback.canExecute()) return fallback
        return null
    }

    private fun stopTunnel() {
        watcherJob?.cancel(); watcherJob = null
        logJob?.cancel(); logJob = null
        try {
            process?.destroy()
            process?.waitFor()
        } catch (_: Throwable) {}
        process = null
        status = Status.STOPPED
    }

    override fun onDestroy() {
        stopTunnel()
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        super.onDestroy()
    }
}
