package com.ilyes.wifiextender

/** Shared state between the service and the screen. The UI polls it once per second. */
object ExtenderState {
    data class Snapshot(
        val running: Boolean = false,
        val status: String = "Stopped",
        val error: String? = null,
        val note: String? = null,
        val ssid: String? = null,
        val passphrase: String? = null,
        val proxyIp: String? = null,
        val proxyPort: Int = ExtenderService.PROXY_PORT,
        val connections: Int = 0,
        val bytes: Long = 0,
        val homeWifiConnected: Boolean = false,
        // Live speed (megabits per second)
        val downMbps: Double = 0.0,
        val upMbps: Double = 0.0,
        val peakDownMbps: Double = 0.0,
        // This phone's link to the router
        val routerFreqMhz: Int = 0,
        val routerRssi: Int = 0,
        val routerLinkMbps: Int = 0,
        // The extender network the iPhone joins
        val groupFreqMhz: Int = 0,
    )

    @Volatile
    var current = Snapshot()
        private set

    @Synchronized
    fun update(block: (Snapshot) -> Snapshot) {
        current = block(current)
    }

    fun reset() = update { Snapshot() }

    fun bandName(freqMhz: Int): String = when {
        freqMhz <= 0 -> "?"
        freqMhz < 3000 -> "2.4 GHz"
        freqMhz < 5925 -> "5 GHz"
        else -> "6 GHz"
    }
}
