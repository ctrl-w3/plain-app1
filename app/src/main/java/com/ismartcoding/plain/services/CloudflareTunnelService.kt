package com.ismartcoding.plain.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process as OsProcess
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.plain.Constants
import com.ismartcoding.plain.MainApp
import com.ismartcoding.plain.R
import com.ismartcoding.plain.helpers.NotificationHelper
import com.ismartcoding.plain.preferences.CloudflareTunnelEnabledPreference
import com.ismartcoding.plain.preferences.CloudflareTunnelTokenPreference
import com.ismartcoding.plain.preferences.KeepAliveWatchdogEnabledPreference
import com.ismartcoding.plain.receivers.KeepAliveWatchdogReceiver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service that runs the bundled `cloudflared` binary. Logs *every*
 * step (paths, permissions, device info, env, exit code, stderr, network state)
 * to TunnelLogger so the user can diagnose problems from the in-app log viewer.
 */
class CloudflareTunnelService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1738
        private const val TAG = "tunnel"
        @Volatile var instance: CloudflareTunnelService? = null
        @Volatile var lastError: String = ""
        @Volatile var status: Status = Status.STOPPED

        enum class Status { STOPPED, STARTING, RUNNING, ERROR }

        fun isRunning(): Boolean = instance != null && status == Status.RUNNING
    }

    private var process: Process? = null
    private var watcherJob: Job? = null
    private var logJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TunnelLogger.init(this)
        TunnelLogger.i(TAG, "Service onCreate (pid=${OsProcess.myPid()}, uid=${OsProcess.myUid()})")
        acquireLocks()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlainApp:CloudflareTunnel")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            TunnelLogger.i(TAG, "PARTIAL_WAKE_LOCK acquired = ${wakeLock?.isHeld}")
        } catch (t: Throwable) { TunnelLogger.w(TAG, "wakeLock acquire failed", t) }
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PlainApp:CloudflareTunnel")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            TunnelLogger.i(TAG, "WifiLock(HIGH_PERF) acquired = ${wifiLock?.isHeld}")
        } catch (t: Throwable) { TunnelLogger.w(TAG, "wifiLock acquire failed", t) }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
        wakeLock = null; wifiLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TunnelLogger.i(TAG, "onTaskRemoved — user swiped app from recents; re-arming service")
        try {
            val restart = Intent(applicationContext, CloudflareTunnelService::class.java).apply {
                action = Constants.ACTION_START_CLOUDFLARE_TUNNEL
            }
            ContextCompat.startForegroundService(applicationContext, restart)
        } catch (t: Throwable) {
            TunnelLogger.w(TAG, "self re-start in onTaskRemoved failed", t)
        }
        try {
            val watchdog = runBlocking { KeepAliveWatchdogEnabledPreference.getAsync(applicationContext) }
            if (watchdog) KeepAliveWatchdogReceiver.schedule(applicationContext)
        } catch (_: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        TunnelLogger.init(this)
        TunnelLogger.i(TAG, "onStartCommand action=${intent?.action} flags=$flags startId=$startId")

        NotificationHelper.ensureDefaultChannel()
        val notification = NotificationHelper.createServiceNotification(
            this,
            Constants.ACTION_STOP_CLOUDFLARE_TUNNEL,
            getString(R.string.cloudflare_tunnel_running),
            getString(R.string.cloudflare_tunnel_running_desc),
        )

        var fgOk = false
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            fgOk = true
            TunnelLogger.i(TAG, "Foreground started (type=SPECIAL_USE)")
        } catch (e: Throwable) {
            TunnelLogger.w(TAG, "startForeground SPECIAL_USE failed, falling back", e)
        }
        if (!fgOk) try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            fgOk = true
            TunnelLogger.i(TAG, "Foreground started (type=DATA_SYNC)")
        } catch (e: Throwable) {
            TunnelLogger.w(TAG, "startForeground DATA_SYNC failed", e)
        }
        if (!fgOk) try {
            startForeground(NOTIFICATION_ID, notification)
            TunnelLogger.i(TAG, "Foreground started (type=none)")
        } catch (e: Throwable) {
            TunnelLogger.e(TAG, "startForeground all attempts failed", e)
        }

        if (intent?.action == Constants.ACTION_STOP_CLOUDFLARE_TUNNEL) {
            TunnelLogger.i(TAG, "Stop intent received")
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        logEnvironment()

        // Schedule the keep-alive watchdog if the user enabled it.
        try {
            val watchdog = runBlocking { KeepAliveWatchdogEnabledPreference.getAsync(applicationContext) }
            if (watchdog) {
                KeepAliveWatchdogReceiver.schedule(applicationContext)
                TunnelLogger.i(TAG, "Watchdog alarm scheduled")
            }
        } catch (t: Throwable) { TunnelLogger.w(TAG, "watchdog schedule failed", t) }

        watcherJob?.cancel()
        watcherJob = coIO { runWithRetry() }
        return START_STICKY
    }

    private fun logEnvironment() {
        try {
            TunnelLogger.i(TAG, "Environment snapshot:\n" + TunnelLogger.deviceSnapshot(this))

            // Notification permission (required Android 13+ for the foreground notif).
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                TunnelLogger.i(TAG, "POST_NOTIFICATIONS granted = $granted")
            }

            // Battery optimization status.
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
            TunnelLogger.i(TAG, "isIgnoringBatteryOptimizations = $ignoring (false = OS may kill the tunnel)")

            // Network reachability.
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val transport = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            TunnelLogger.i(TAG, "network: hasInternet=$hasNet validated=$validated transport=$transport")
        } catch (t: Throwable) {
            TunnelLogger.w(TAG, "logEnvironment failed", t)
        }
    }

    private suspend fun runWithRetry() {
        val token = CloudflareTunnelTokenPreference.getAsync(this).trim()
        if (token.isEmpty()) {
            lastError = getString(R.string.cloudflare_tunnel_no_token)
            TunnelLogger.e(TAG, "No tunnel token configured. Aborting.")
            status = Status.ERROR
            stopSelf()
            return
        }
        TunnelLogger.i(TAG, "Token present (length=${token.length}, first6=${token.take(6)}…)")

        var backoffMs = 2000L
        var attempt = 0
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            attempt += 1
            status = Status.STARTING
            lastError = ""
            TunnelLogger.i(TAG, "=== launch attempt #$attempt ===")
            try {
                runOnce(token)
                TunnelLogger.i(TAG, "cloudflared process exited cleanly")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                TunnelLogger.e(TAG, "cloudflared run failed: $lastError", t)
                status = Status.ERROR
            }
            val stillEnabled = CloudflareTunnelEnabledPreference.getAsync(MainApp.instance)
            if (!stillEnabled) {
                TunnelLogger.i(TAG, "Tunnel preference disabled — stopping service")
                stopSelf()
                return
            }
            TunnelLogger.i(TAG, "Retrying in ${backoffMs}ms")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60000L)
        }
        TunnelLogger.i(TAG, "runWithRetry loop exited")
    }

    private fun runOnce(token: String) {
        val binary = locateBinary()
            ?: throw IllegalStateException(getString(R.string.cloudflare_tunnel_binary_missing))
        TunnelLogger.i(TAG, "Using binary: ${binary.absolutePath}  size=${binary.length()}  exec=${binary.canExecute()}")

        val workDir = File(filesDir, "cloudflared").apply { mkdirs() }
        TunnelLogger.i(TAG, "workDir=${workDir.absolutePath}  exists=${workDir.exists()} writable=${workDir.canWrite()}")

        TunnelPreflight.run(this)

        val cmd = listOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--edge-ip-version", "auto",
            "--protocol", "http2",
            "--loglevel", "debug",
            "--transport-loglevel", "debug",
            "run",
            "--token", token,
        )
        TunnelLogger.i(TAG, "exec: ${cmd.dropLast(1).joinToString(" ")} <token-hidden>")

        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
        pb.environment()["TUNNEL_LOGFILE"] = File(workDir, "cloudflared.log").absolutePath
        pb.environment()["HOME"] = workDir.absolutePath
        pb.environment()["TMPDIR"] = workDir.absolutePath

        val p: Process = try {
            pb.start()
        } catch (t: Throwable) {
            TunnelLogger.e(TAG, "ProcessBuilder.start() failed — most often a SELinux/exec restriction or wrong ABI for this device", t)
            throw t
        }
        process = p
        TunnelLogger.i(TAG, "process started")

        logJob?.cancel()
        logJob = coIO {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val s = line!!
                        TunnelLogger.d("cloudflared", s)
                        TunnelDiagnostics.classify(s)?.let { reason ->
                            TunnelLogger.e(TAG, "DIAGNOSIS → $reason")
                            lastError = reason
                        }
                        if (status == Status.STARTING && (
                                s.contains("Registered tunnel connection") ||
                                s.contains("Connection registered") ||
                                s.contains("Connection ") && s.contains(" registered")
                            )) {
                            status = Status.RUNNING
                            TunnelLogger.i(TAG, "Tunnel is now LIVE — your domain should now reach this phone")
                        }
                    }
                }
            } catch (t: Throwable) {
                TunnelLogger.w(TAG, "stdout reader ended", t)
            }
        }

        val exit = p.waitFor()
        TunnelLogger.i(TAG, "cloudflared exited with code $exit")
        if (exit != 0 && status != Status.RUNNING) {
            throw RuntimeException("cloudflared exit=$exit (see log lines above for cause)")
        }
    }

    private fun locateBinary(): File? {
        val libDir = applicationInfo.nativeLibraryDir
        TunnelLogger.i(TAG, "Searching for cloudflared in $libDir")
        val candidates = listOf("libcloudflared.so", "cloudflared")
        for (name in candidates) {
            val f = File(libDir, name)
            TunnelLogger.d(TAG, "  candidate $name: exists=${f.exists()} size=${if (f.exists()) f.length() else 0} exec=${f.canExecute()}")
            if (f.exists() && f.length() > 1000 && f.canExecute()) return f
        }
        val fallback = File(filesDir, "cloudflared/cloudflared")
        TunnelLogger.d(TAG, "  fallback ${fallback.absolutePath}: exists=${fallback.exists()} exec=${fallback.canExecute()}")
        if (fallback.exists() && fallback.canExecute()) return fallback
        TunnelLogger.e(TAG, "cloudflared binary not found! The build did not include it (download likely failed at CI). Rebuild the APK with network access to github.com.")
        return null
    }

    private fun stopTunnel() {
        TunnelLogger.i(TAG, "stopTunnel()")
        watcherJob?.cancel(); watcherJob = null
        logJob?.cancel(); logJob = null
        try {
            process?.destroy()
            process?.waitFor()
        } catch (t: Throwable) {
            TunnelLogger.w(TAG, "error destroying process", t)
        }
        process = null
        status = Status.STOPPED
    }

    override fun onDestroy() {
        TunnelLogger.i(TAG, "Service onDestroy")
        stopTunnel()
        releaseLocks()
        instance = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        super.onDestroy()
    }
}
