package com.ilyes.wifiextender

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.security.SecureRandom

/**
 * Foreground service that:
 *  1. creates a Wi-Fi Direct group (looks like a normal WPA2 Wi-Fi network to the iPhone)
 *     while this phone stays connected to the home router, and
 *  2. runs an HTTP/HTTPS proxy that forwards the iPhone's traffic to the home Wi-Fi.
 *
 * Speed: by default the extender network is put on exactly the same channel as the router
 * connection. With one radio, two different channels mean constant switching back and forth
 * ("multi-channel concurrency"), which can cut speed a lot.
 */
class ExtenderService : Service() {

    companion object {
        const val ACTION_START = "com.ilyes.wifiextender.START"
        const val ACTION_STOP = "com.ilyes.wifiextender.STOP"
        const val PROXY_PORT = 8282
        const val PREFS = "extender"
        const val KEY_BAND = "band"          // "match" (default) | "2g" | "5g" | "auto"
        private const val NETWORK_NAME = "DIRECT-WX-Extender" // must start with "DIRECT-xy"
        private const val CHANNEL_ID = "extender"
        private const val NOTIF_ID = 1
        private const val TAG = "ExtenderService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var proxy: ProxyServer? = null
    private var wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var busyRetries = 0

    // Home Wi-Fi network, tracked live so the proxy doesn't look it up for every connection
    @Volatile private var homeNetwork: Network? = null
    private var netCallbackRegistered = false
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { homeNetwork = network }
        override fun onLost(network: Network) { if (homeNetwork == network) homeNetwork = null }
    }

    // Speed meter
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastTick = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything { stopSelf() }
            return START_NOT_STICKY
        }
        goForeground("Starting…")
        if (channel == null) begin()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopEverything(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start-up

    private fun begin() {
        stopping = false
        acquireLocks()
        trackHomeNetwork()
        ExtenderState.update { it.copy(running = true, status = "Creating network…", error = null, note = null) }

        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            fail("This phone doesn't support Wi-Fi Direct.")
            return
        }
        p2p = manager
        val ch = manager.initialize(this, mainLooper) {
            if (!stopping) fail("Wi-Fi Direct stopped unexpectedly. Press Start again.")
        }
        channel = ch

        // Remove any leftover group (from a crash or another app), then create ours
        manager.removeGroup(ch, listener(onOk = { createGroup(true) }, onFail = { createGroup(true) }))
    }

    @SuppressLint("MissingPermission") // permissions are requested in MainActivity before starting
    private fun createGroup(useBandSetting: Boolean) {
        val manager = p2p ?: return
        val ch = channel ?: return
        val l = listener(
            onOk = {
                busyRetries = 0
                ExtenderState.update { it.copy(status = "Network created, starting proxy…") }
                fetchGroupInfo(0)
            },
            onFail = { reason ->
                when {
                    reason == WifiP2pManager.BUSY && busyRetries < 3 -> {
                        busyRetries++
                        handler.postDelayed({ createGroup(useBandSetting) }, 2000)
                    }
                    useBandSetting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        // This phone refused the chosen channel/band — fall back to automatic
                        ExtenderState.update {
                            it.copy(note = "Your phone didn't accept the chosen band, so it's using automatic.")
                        }
                        createGroup(false)
                    }
                    else -> fail(
                        when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "This phone doesn't support Wi-Fi Direct."
                            WifiP2pManager.BUSY -> "Wi-Fi is busy. Make sure Wi-Fi is ON (and Location on Android 12 or older), then try again."
                            else -> "Couldn't create the network (error $reason). Turn Wi-Fi off and on, then try again."
                        }
                    )
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: fixed name + password (the iPhone remembers it) and channel control
            val builder = WifiP2pConfig.Builder()
                .setNetworkName(NETWORK_NAME)
                .setPassphrase(passphrase())
            if (useBandSetting) {
                val band = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_BAND, "match")
                val routerFreq = routerWifiInfo()?.frequency ?: 0
                when (band) {
                    "2g" -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    "5g" -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    "match" -> if (routerFreq > 0) builder.setGroupOperatingFrequency(routerFreq)
                    else -> {} // "auto": let the phone decide
                }
            }
            manager.createGroup(ch, builder.build(), l)
        } else {
            // Older Android picks name, password and channel itself (usually the router's channel)
            manager.createGroup(ch, l)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupInfo(attempt: Int) {
        val manager = p2p ?: return
        val ch = channel ?: return
        manager.requestGroupInfo(ch) { group ->
            if (group == null || !group.isGroupOwner) {
                if (attempt < 20) handler.postDelayed({ fetchGroupInfo(attempt + 1) }, 500)
                else fail("The network was created but didn't come up. Try again.")
                return@requestGroupInfo
            }
            val groupFreq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency else 0
            manager.requestConnectionInfo(ch) { info ->
                val ip = info?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                startProxy(ip, group.networkName, group.passphrase, groupFreq)
            }
        }
    }

    private fun startProxy(ip: String, ssid: String?, pass: String?, groupFreq: Int) {
        proxy?.stop()
        val prefix = ip.substringBeforeLast('.') + "."
        val server = ProxyServer(
            port = PROXY_PORT,
            allowedPrefix = prefix,
            upstream = { homeNetwork },
            onError = { msg -> handler.post { fail(msg) } },
            credentials = {
                val cur = ExtenderState.current
                if (cur.ssid != null && cur.passphrase != null) cur.ssid to cur.passphrase else null
            },
        )
        server.start()
        proxy = server
        lastDown = 0; lastUp = 0; lastTick = SystemClock.elapsedRealtime()

        ExtenderState.update {
            it.copy(
                running = true,
                status = "Running",
                ssid = ssid,
                passphrase = pass,
                proxyIp = ip,
                proxyPort = PROXY_PORT,
                groupFreqMhz = groupFreq,
            )
        }
        goForeground("$ssid  •  Proxy $ip:$PROXY_PORT")
        tickStats()
    }

    /** Updates speed, router signal and counters once per second. */
    private fun tickStats() {
        val server = proxy ?: return
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastTick).coerceAtLeast(1)) / 1000.0
        val down = server.bytesDown.get()
        val up = server.bytesUp.get()
        val downMbps = (down - lastDown) * 8 / 1_000_000.0 / dt
        val upMbps = (up - lastUp) * 8 / 1_000_000.0 / dt
        lastDown = down; lastUp = up; lastTick = now

        val wi = routerWifiInfo()
        ExtenderState.update {
            it.copy(
                connections = server.activeConnections.get(),
                bytes = down + up,
                // light smoothing so the number doesn't jump around
                downMbps = it.downMbps * 0.4 + downMbps * 0.6,
                upMbps = it.upMbps * 0.4 + upMbps * 0.6,
                peakDownMbps = maxOf(it.peakDownMbps, downMbps),
                homeWifiConnected = homeNetwork != null,
                routerFreqMhz = wi?.frequency ?: 0,
                routerRssi = wi?.rssi ?: 0,
                routerLinkMbps = wi?.linkSpeed ?: 0,
            )
        }
        handler.postDelayed({ tickStats() }, 1000)
    }

    /** This phone's current connection to the router, or null if not connected. */
    private fun routerWifiInfo(): WifiInfo? {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        return if (info.networkId == -1 && info.rssi <= -127) null else info
    }

    private fun trackHomeNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        @Suppress("DEPRECATION")
        homeNetwork = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (!netCallbackRegistered) {
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    netCallback
                )
                netCallbackRegistered = true
            }
        }
    }

    // ---------------------------------------------------------------- shutdown

    private fun fail(message: String) {
        Log.w(TAG, message)
        stopEverything(null)
        ExtenderState.update { ExtenderState.Snapshot(error = message, status = "Stopped") }
        stopSelf()
    }

    private fun stopEverything(then: (() -> Unit)?) {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        proxy?.stop()
        proxy = null
        releaseLocks()
        if (netCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
            netCallbackRegistered = false
        }
        val manager = p2p
        val ch = channel
        p2p = null
        channel = null

        val finish = {
            if (ch != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { ch.close() }
            val err = ExtenderState.current.error
            ExtenderState.update { ExtenderState.Snapshot(error = err) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            then?.invoke()
            Unit
        }
        if (manager != null && ch != null) {
            manager.removeGroup(ch, listener(onOk = { finish() }, onFail = { finish() }))
        } else {
            finish()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun listener(onOk: () -> Unit, onFail: (Int) -> Unit) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onOk()
            override fun onFailure(reason: Int) = onFail(reason)
        }

    /** Random password generated once and kept, so the iPhone only has to enter it once. */
    private fun passphrase(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString("pass", null)?.let { return it }
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val pass = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        prefs.edit().putString("pass", pass).apply()
        return pass
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wifiLocks.isEmpty()) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Turns off Wi-Fi power saving, which otherwise adds delay and lowers throughput
            @Suppress("DEPRECATION")
            wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiExtender:perf")
            // Android 10+: lowest-latency mode (active while the screen is on and the app is
            // visible — that's what Turbo mode in the app is for)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WifiExtender:lowlat")
            }
            wifiLocks.forEach { runCatching { it.setReferenceCounted(false); it.acquire() } }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiExtender:cpu")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wifiLocks.forEach { runCatching { it.release() } }
        wifiLocks.clear()
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Extender running", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), piFlags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ExtenderService::class.java).setAction(ACTION_STOP), piFlags
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle("Wi-Fi Extender")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
    }
}
