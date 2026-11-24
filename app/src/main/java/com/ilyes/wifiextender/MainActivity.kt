package com.ilyes.wifiextender

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(ExtenderService.PREFS, MODE_PRIVATE) }

    // Views
    private lateinit var statusChip: TextView
    private lateinit var power: View
    private lateinit var powerIcon: ImageView
    private lateinit var pulse: View
    private lateinit var pulse2: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var warning: TextView
    private lateinit var note: TextView
    private lateinit var speedCard: View
    private lateinit var speedValue: TextView
    private lateinit var down: TextView
    private lateinit var up: TextView
    private lateinit var spark: SparklineView
    private lateinit var peak: TextView
    private lateinit var signalCard: View
    private lateinit var bars: SignalBarsView
    private lateinit var signalTitle: TextView
    private lateinit var signalDetail: TextView
    private lateinit var hint: TextView
    private lateinit var channelInfo: TextView
    private lateinit var connectCard: View
    private lateinit var qrWifi: ImageView
    private lateinit var qrProfile: ImageView
    private lateinit var ssidValue: TextView
    private lateinit var passValue: TextView
    private lateinit var serverValue: TextView
    private lateinit var portValue: TextView
    private lateinit var statsRow: View
    private lateinit var connections: TextView
    private lateinit var dataShared: TextView
    private lateinit var turbo: MaterialSwitch
    private lateinit var band: ChipGroup

    // UI state
    private enum class Phase { OFF, STARTING, RUNNING }
    private var phase: Phase? = null
    private var pulseAnim: Animator? = null
    private var speedAnim: ValueAnimator? = null
    private var shownSpeed = 0f
    private var qrKey: String? = null
    private var turboApplied: Boolean? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val wifiPermission = if (Build.VERSION.SDK_INT >= 33)
                Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, wifiPermission) == PackageManager.PERMISSION_GRANTED) {
                tryStart()
            } else {
                Toast.makeText(
                    this,
                    "Android requires this permission to create a Wi-Fi Direct network.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // Edge-to-edge: keep content clear of the status and navigation bars
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        bindViews()

        power.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            it.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
            if (ExtenderState.current.running) stopExtender() else tryStart()
        }

        turbo.isChecked = prefs.getBoolean("turbo", true)
        turbo.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("turbo", on).apply() }

        // Channel choice needs Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            band.check(
                when (prefs.getString(ExtenderService.KEY_BAND, "match")) {
                    "5g" -> R.id.band5g
                    "2g" -> R.id.band2g
                    "auto" -> R.id.bandAuto
                    else -> R.id.bandMatch
                }
            )
            band.setOnCheckedStateChangeListener { _, ids ->
                val value = when (ids.firstOrNull()) {
                    R.id.band5g -> "5g"
                    R.id.band2g -> "2g"
                    R.id.bandAuto -> "auto"
                    else -> "match"
                }
                prefs.edit().putString(ExtenderService.KEY_BAND, value).apply()
            }
        } else {
            findViewById<View>(R.id.bandSection).visibility = View.GONE
        }
    }

    private fun bindViews() {
        statusChip = findViewById(R.id.statusChip)
        power = findViewById(R.id.power)
        powerIcon = findViewById(R.id.powerIcon)
        pulse = findViewById(R.id.pulse)
        pulse2 = findViewById(R.id.pulse2)
        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)
        warning = findViewById(R.id.warning)
        note = findViewById(R.id.note)
        speedCard = findViewById(R.id.speedCard)
        speedValue = findViewById(R.id.speedValue)
        down = findViewById(R.id.down)
        up = findViewById(R.id.up)
        spark = findViewById(R.id.spark)
        peak = findViewById(R.id.peak)
        signalCard = findViewById(R.id.signalCard)
        bars = findViewById(R.id.bars)
        signalTitle = findViewById(R.id.signalTitle)
        signalDetail = findViewById(R.id.signalDetail)
        hint = findViewById(R.id.hint)
        channelInfo = findViewById(R.id.channelInfo)
        connectCard = findViewById(R.id.connectCard)
        qrWifi = findViewById(R.id.qrWifi)
        qrProfile = findViewById(R.id.qrProfile)
        statsRow = findViewById(R.id.statsRow)
        connections = findViewById(R.id.connections)
        dataShared = findViewById(R.id.dataShared)
        turbo = findViewById(R.id.turbo)
        band = findViewById(R.id.band)

        fun row(id: Int, label: String): TextView {
            val r = findViewById<View>(id)
            r.findViewById<TextView>(R.id.label).text = label
            return r.findViewById(R.id.value)
        }
        ssidValue = row(R.id.rowSsid, "Wi-Fi")
        passValue = row(R.id.rowPass, "Password")
        serverValue = row(R.id.rowServer, "Server")
        portValue = row(R.id.rowPort, "Port")
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ---------------------------------------------------------------- start / stop

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun tryStart() {
        val missing = neededPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
            return
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            Toast.makeText(this, "Turn on Wi-Fi and connect to your home router first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            return
        }
        askToIgnoreBatteryOptimizations()
        ExtenderState.update { it.copy(error = null, running = true, status = "Starting…") }
        spark.clear()
        ContextCompat.startForegroundService(
            this, Intent(this, ExtenderService::class.java).setAction(ExtenderService.ACTION_START)
        )
        render()
    }

    private fun stopExtender() {
        startService(Intent(this, ExtenderService::class.java).setAction(ExtenderService.ACTION_STOP))
    }

    /** Stops Android from killing or throttling the extender. Asked once. */
    @SuppressLint("BatteryLife")
    private fun askToIgnoreBatteryOptimizations() {
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    /**
     * Turbo mode: screen stays on at minimum brightness while the extender runs. That keeps the
     * app in the foreground, which enables Android's low-latency Wi-Fi mode and prevents
     * Doze / app-standby from slowing down the phone.
     */
    private fun applyTurbo(on: Boolean) {
        if (turboApplied == on) return
        turboApplied = on
        val lp = window.attributes
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = 0.02f
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        val s = ExtenderState.current
        val newPhase = when {
            !s.running -> Phase.OFF
            s.proxyIp == null -> Phase.STARTING
            else -> Phase.RUNNING
        }
        if (newPhase != phase) {
            phase = newPhase
            applyPhase(newPhase)
        }
        applyTurbo(s.running && turbo.isChecked)
        for (i in 0 until band.childCount) band.getChildAt(i).isEnabled = !s.running

        // Banners
        val warn = when {
            s.error != null -> s.error
            newPhase == Phase.RUNNING && !s.homeWifiConnected ->
                "This phone isn't connected to your home Wi-Fi. Move it closer to the router."
            else -> null
        }
        showBanner(warning, warn)
        showBanner(note, if (s.running) s.note else null)

        when (newPhase) {
            Phase.OFF -> {
                statusTitle.text = "Tap to start"
                statusSub.text = "Turns this phone into a Wi-Fi extender"
            }
            Phase.STARTING -> {
                statusTitle.text = "Starting…"
                statusSub.text = s.status
            }
            Phase.RUNNING -> {
                statusTitle.text = "Extending your Wi-Fi"
                statusSub.text = if (s.bytes == 0L) "Waiting for your iPhone to connect…"
                else "${s.connections} active connection${if (s.connections == 1) "" else "s"}"
                renderRunning(s)
            }
        }
    }

    private fun renderRunning(s: ExtenderState.Snapshot) {
        // Speed
        animateSpeed(s.downMbps.toFloat())
        down.text = mbps(s.downMbps)
        up.text = mbps(s.upMbps)
        spark.push(s.downMbps.toFloat())
        peak.text = "Peak ${mbps(s.peakDownMbps)}  ·  last 60 seconds"

        // Router signal
        if (s.routerFreqMhz > 0) {
            val r = s.routerRssi
            val (level, title, color) = when {
                r >= -55 -> Triple(4, "Excellent", R.color.green)
                r >= -65 -> Triple(3, "Good", R.color.green)
                r >= -72 -> Triple(2, "Fair", R.color.amber)
                else -> Triple(1, "Weak", R.color.red)
            }
            bars.level = level
            bars.activeColor = getColor(color)
            signalTitle.text = title
            signalDetail.text = "${ExtenderState.bandName(s.routerFreqMhz)}  ·  $r dBm  ·  ${s.routerLinkMbps} Mbps link"
            hint.text = placementHint(s)
        } else {
            bars.level = 0
            signalTitle.text = "Not connected"
            signalDetail.text = "Connect this phone to your home Wi-Fi"
            hint.text = "Open Wi-Fi settings on this phone and join your router."
        }
        channelInfo.text = when {
            s.groupFreqMhz <= 0 -> ""
            s.groupFreqMhz == s.routerFreqMhz ->
                "✓  Extender runs on the same channel as your router (${ExtenderState.bandName(s.groupFreqMhz)})"
            else ->
                "Extender: ${ExtenderState.bandName(s.groupFreqMhz)} on a different channel from the router, which may be slower"
        }
        channelInfo.visibility = if (channelInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Connect details + QR codes
        val ssid = s.ssid ?: "?"
        val pass = s.passphrase ?: "?"
        val ip = s.proxyIp ?: "192.168.49.1"
        ssidValue.text = ssid
        passValue.text = pass
        serverValue.text = ip
        portValue.text = s.proxyPort.toString()
        val key = "$ssid|$pass|$ip|${s.proxyPort}"
        if (key != qrKey) {
            qrKey = key
            qrWifi.setImageBitmap(qrBitmap("WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(pass)};;"))
            qrProfile.setImageBitmap(qrBitmap("http://$ip:${s.proxyPort}/extender.mobileconfig"))
        }

        // Stats
        connections.text = s.connections.toString()
        dataShared.text = formatBytes(s.bytes)
    }

    private fun applyPhase(p: Phase) {
        val running = p == Phase.RUNNING
        power.setBackgroundResource(if (p == Phase.OFF) R.drawable.bg_power_off else R.drawable.bg_power_on)
        powerIcon.imageTintList = ColorStateList.valueOf(
            if (p == Phase.OFF) getColor(R.color.text_dim) else Color.WHITE
        )

        val (chipText, chipColor) = when (p) {
            Phase.OFF -> "●  Off" to getColor(R.color.text_dim)
            Phase.STARTING -> "●  Starting" to getColor(R.color.amber)
            Phase.RUNNING -> "●  Live" to getColor(R.color.green)
        }
        statusChip.text = chipText
        statusChip.setTextColor(chipColor)
        statusChip.backgroundTintList = ColorStateList.valueOf((chipColor and 0x00FFFFFF) or 0x26000000)

        // Pulse rings: slow when live, fast while starting
        pulseAnim?.cancel()
        pulse.alpha = 0f
        pulse2.alpha = 0f
        if (p != Phase.OFF) {
            val duration = if (running) 2400L else 1100L
            pulseAnim = AnimatorSet().apply {
                playTogether(ring(pulse, duration, 0), ring(pulse2, duration, duration / 2))
                start()
            }
        }

        // Cards appear with a gentle slide-up
        listOf(speedCard, signalCard, connectCard, statsRow).forEachIndexed { i, v ->
            if (running) reveal(v, i * 70L) else v.visibility = View.GONE
        }
        if (!running) {
            spark.clear()
            shownSpeed = 0f
            speedValue.text = "0"
        }
    }

    private fun ring(v: View, duration: Long, delay: Long): Animator =
        ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.55f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.55f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
        ).apply {
            this.duration = duration
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }

    private fun reveal(v: View, delay: Long) {
        if (v.visibility == View.VISIBLE) return
        v.alpha = 0f
        v.translationY = 40f * resources.displayMetrics.density
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun showBanner(v: TextView, text: String?) {
        if (text == null) {
            v.visibility = View.GONE
        } else {
            v.text = text
            if (v.visibility != View.VISIBLE) reveal(v, 0)
        }
    }

    /** Counts the big speed number smoothly to the new value. */
    private fun animateSpeed(target: Float) {
        speedAnim?.cancel()
        speedAnim = ValueAnimator.ofFloat(shownSpeed, target).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                shownSpeed = it.animatedValue as Float
                speedValue.text = if (shownSpeed < 10f)
                    String.format(Locale.US, "%.1f", shownSpeed)
                else shownSpeed.toInt().toString()
            }
            start()
        }
    }

    /** Tells the user where to move the phone, based on its signal from the router. */
    private fun placementHint(s: ExtenderState.Snapshot): String {
        val r = s.routerRssi
        val base = when {
            r >= -55 -> "📍 Great spot. You can move it a bit further toward your room."
            r >= -65 -> "📍 Good spot for full speed."
            r >= -72 -> "📍 Move it a little closer to the router for more speed."
            else -> "📍 Too far from the router. Move it closer."
        }
        val bandTip = if (s.routerFreqMhz < 3000 && r >= -62)
            "\n💡 If your router has a 5 GHz network, connect this phone to it. It's usually much faster."
        else ""
        return base + bandTip
    }

    // ---------------------------------------------------------------- helpers

    private fun qrBitmap(text: String, size: Int = 512): Bitmap {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0)
        )
        val px = IntArray(size * size)
        for (y in 0 until size) {
            val off = y * size
            for (x in 0 until size) px[off + x] = if (m.get(x, y)) 0xFF0B1020.toInt() else Color.WHITE
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Escapes special characters for the WIFI: QR format. */
    private fun qrEscape(s: String) = s
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace(":", "\\:").replace("\"", "\\\"")

    private fun mbps(v: Double) = String.format(Locale.US, if (v < 10) "%.1f Mbps" else "%.0f Mbps", v)

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
        else -> "$b B"
    }
}
