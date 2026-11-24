package com.ilyes.wifiextender

import android.net.Network
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast HTTP/HTTPS proxy.
 *
 * - HTTPS (and anything else) goes through "CONNECT host:port" tunnels, so traffic stays
 *   end-to-end encrypted between the iPhone and the website; this phone only forwards bytes.
 * - Plain HTTP requests are forwarded with the request line rewritten to origin form.
 * - DNS is resolved here (with a short cache), on the phone's home Wi-Fi.
 *
 * Speed-related choices:
 * - 64 KB copy buffers → fewer system calls per megabyte
 * - high-priority relay threads → forwarding isn't delayed by background work on old CPUs
 * - TCP_NODELAY on both sides → no added latency on small packets
 * - TCP buffer sizes left to the kernel's auto-tuning (setting them by hand disables it)
 * - DNS cache + fast fallback between IPv4/IPv6 addresses → pages start loading sooner
 *
 * It also serves a status page with a speed test at http://<proxy-ip>:<port>/
 */
class ProxyServer(
    private val port: Int,
    private val allowedPrefix: String,
    private val upstream: () -> Network?,
    private val onError: (String) -> Unit,
    /** Wi-Fi name + password, used for the iPhone setup profile. */
    private val credentials: () -> Pair<String, String>? = { null },
) {
    private val threadIds = AtomicInteger(0)
    private val pool: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "proxy-${threadIds.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
    })
    private val openSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()
    @Volatile private var server: ServerSocket? = null

    val activeConnections = AtomicInteger(0)
    /** Bytes sent to the client (download) and from the client (upload). */
    val bytesDown = AtomicLong(0)
    val bytesUp = AtomicLong(0)

    private class DnsEntry(val addresses: Array<InetAddress>, val expiresAt: Long)

    fun start() {
        pool.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port), 256)
                server = ss
                Log.i(TAG, "Proxy listening on port $port")
                while (!ss.isClosed) {
                    val client = try { ss.accept() } catch (e: IOException) { break }
                    val ip = client.inetAddress?.hostAddress ?: ""
                    if (!ip.startsWith(allowedPrefix) && !ip.startsWith("127.")) {
                        Log.w(TAG, "Rejected client $ip")
                        closeQuietly(client)
                        continue
                    }
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy failed", e)
                onError("Proxy could not start: ${e.message}")
            }
        }
    }

    fun stop() {
        closeQuietly(server)
        server = null
        openSockets.toList().forEach { closeQuietly(it) }
        openSockets.clear()
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------------------------------

    private fun handle(client: Socket) {
        openSockets += client
        activeConnections.incrementAndGet()
        var remote: Socket? = null
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val cin = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
            val cout = client.getOutputStream()
            val myIp = client.localAddress?.hostAddress ?: "192.168.49.1"

            val head = readHead(cin) ?: return
            val lines = head.split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 3) { sendError(cout, 400, "Bad Request"); return }
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts[2]

            if (method == "CONNECT") {
                // HTTPS tunnel
                val (host, port) = splitHostPort(target, 443)
                remote = connect(host, port) ?: run { sendError(cout, 502, "Bad Gateway"); return }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                cout.flush()
                relay(cin, client, remote)
                return
            }

            // Plain HTTP
            val headers = lines.drop(1).filter { it.isNotEmpty() }
            if (!target.startsWith("http://", ignoreCase = true)) {
                // Opened directly (not through the proxy setting)
                serveLocal(cout, target, myIp)
                return
            }
            val rest = target.substring(7)
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else "/"
            val (host, port) = splitHostPort(authority, 80)

            if (port == this.port && (host == myIp || host == "localhost")) {
                // Request for this proxy itself, sent through the proxy setting
                serveLocal(cout, path, myIp)
                return
            }

            val out = StringBuilder("$method $path $version\r\n")
            for (h in headers) {
                val name = h.substringBefore(':').trim().lowercase()
                if (name in HOP_BY_HOP) continue
                out.append(h).append("\r\n")
            }
            out.append("Connection: close\r\n\r\n")

            remote = connect(host, port) ?: run { sendError(cout, 502, "Bad Gateway"); return }
            val rout = remote.getOutputStream()
            rout.write(out.toString().toByteArray(Charsets.ISO_8859_1))
            rout.flush()
            relay(cin, client, remote)
        } catch (e: Exception) {
            Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
            closeQuietly(client)
            closeQuietly(remote)
            openSockets -= client
            remote?.let { openSockets -= it }
            activeConnections.decrementAndGet()
        }
    }

    private fun resolve(host: String, net: Network?): Array<InetAddress> {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (it.expiresAt > now) return it.addresses }
        val addresses = net?.getAllByName(host) ?: InetAddress.getAllByName(host)
        if (dnsCache.size > 2000) dnsCache.clear()
        dnsCache[host] = DnsEntry(addresses, now + DNS_TTL_MS)
        return addresses
    }

    /** Opens a TCP connection through the home Wi-Fi network. */
    private fun connect(host: String, port: Int): Socket? {
        val net = upstream()
        val addresses = try {
            resolve(host, net)
        } catch (e: Exception) {
            Log.w(TAG, "DNS failed for $host: ${e.message}")
            return null
        }
        // Prefer IPv4 (more reliable on home routers). Give each address a short timeout
        // so one dead address doesn't stall the page; the last one gets longer.
        val ordered = addresses.sortedBy { if (it.address.size == 4) 0 else 1 }
        for ((i, addr) in ordered.withIndex()) {
            val s = net?.socketFactory?.createSocket() ?: Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(addr, port), if (i == ordered.lastIndex) 10_000 else 3_000)
                openSockets += s
                return s
            } catch (e: IOException) {
                closeQuietly(s)
            }
        }
        dnsCache.remove(host) // addresses may be stale
        return null
    }

    /** Copies bytes both ways until both sides are done. */
    private fun relay(cin: InputStream, client: Socket, remote: Socket) {
        client.soTimeout = 0
        remote.soTimeout = 0
        val rin = remote.getInputStream()
        val rout = remote.getOutputStream()
        val cout = client.getOutputStream()

        val download = pool.submit(Runnable {
            pump(rin, cout, bytesDown)
            runCatching { client.shutdownOutput() }
        })
        pump(cin, rout, bytesUp)
        runCatching {
            remote.shutdownOutput()
            // Client is done sending; don't let a half-open connection hang around forever
            remote.soTimeout = 60_000
        }
        runCatching { download.get() }
    }

    private fun pump(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
        }
    }

    /** Reads the request line + headers (up to the blank line) without consuming the body. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var state = 0 // counts \r \n \r \n
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) return buf.toString("ISO-8859-1").trimEnd('\r', '\n')
            if (buf.size() > 64 * 1024) return null
        }
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        if (s.startsWith("[")) { // IPv6 literal
            val end = s.indexOf(']')
            if (end > 0) {
                val p = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return s.substring(1, end) to p
            }
        }
        val i = s.lastIndexOf(':')
        return if (i > 0 && s.indexOf(':') == i) {
            s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: defaultPort)
        } else {
            s to defaultPort
        }
    }

    // ---------------------------------------------------------------- local pages

    private fun serveLocal(out: OutputStream, path: String, myIp: String) {
        when {
            path.startsWith("/speedtest-data") -> {
                // Streams zeros so the iPhone can measure the iPhone ↔ extender link alone
                val mb = Regex("mb=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull()
                    ?.coerceIn(1, 500) ?: 25
                val total = mb.toLong() * 1024 * 1024
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: $total\r\nCache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                val zeros = ByteArray(BUFFER_SIZE)
                var left = total
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n)
                    left -= n
                }
                out.flush()
            }
            path.startsWith("/extender.mobileconfig") -> {
                val c = credentials()
                if (c == null) sendError(out, 404, "Not Found")
                else respond(out, "application/x-apple-aspen-config", mobileConfig(c.first, c.second, myIp))
            }
            path.startsWith("/proxy.pac") -> respond(
                out, "application/x-ns-proxy-autoconfig",
                "function FindProxyForURL(url, host) { return \"PROXY $myIp:$port\"; }\n"
            )
            else -> respond(out, "text/html; charset=utf-8", statusPage(myIp))
        }
    }

    private fun statusPage(myIp: String) = """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Wi-Fi Extender</title>
        <style>body{font-family:-apple-system,sans-serif;padding:24px;max-width:480px;margin:auto}
        button{font-size:18px;padding:14px;width:100%;border-radius:12px;border:0;background:#1a73e8;color:#fff}
        #r{font-size:28px;font-weight:600;margin:20px 0 6px}.s{color:#666;font-size:15px}</style></head>
        <body><h2>Wi-Fi Extender is running</h2>
        <p class="s">Proxy: $myIp:$port</p>
        <button id="b" onclick="run()">Test speed: iPhone ↔ extender</button>
        <div id="r"></div><div id="d" class="s"></div>
        <p class="s">Compare this with a normal internet speed test (e.g. fast.com).
        If this number is much higher, the bottleneck is the old phone's link to the router:
        move it closer to the router. If it's similar or lower, move your iPhone closer to the old phone.</p>
        <script>
        async function run(){
          const b=document.getElementById('b'),r=document.getElementById('r'),d=document.getElementById('d');
          b.disabled=true;r.textContent='Testing…';d.textContent='';
          try{
            const t=performance.now();
            const res=await fetch('/speedtest-data?mb=40&r='+Math.random(),{cache:'no-store'});
            const rd=res.body.getReader();let n=0;
            for(;;){const x=await rd.read();if(x.done)break;n+=x.value.length;}
            const s=(performance.now()-t)/1000;
            r.textContent=(n*8/s/1e6).toFixed(1)+' Mbps';
            d.textContent=(n/1048576).toFixed(0)+' MB in '+s.toFixed(1)+' s';
          }catch(e){r.textContent='Test failed';d.textContent=String(e);}
          b.disabled=false;
        }
        </script></body></html>
    """.trimIndent()

    /**
     * iOS configuration profile: joins the extender network and sets its proxy in one step.
     * (Opened from the QR code in the app; iOS shows it under Settings → Profile Downloaded.)
     */
    private fun mobileConfig(ssid: String, pass: String, myIp: String): String {
        fun x(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val wifiUuid = java.util.UUID.nameUUIDFromBytes("wifi-$ssid".toByteArray()).toString().uppercase()
        val rootUuid = java.util.UUID.nameUUIDFromBytes("root-$ssid".toByteArray()).toString().uppercase()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>PayloadContent</key>
              <array>
                <dict>
                  <key>AutoJoin</key><true/>
                  <key>EncryptionType</key><string>WPA</string>
                  <key>HIDDEN_NETWORK</key><false/>
                  <key>SSID_STR</key><string>${x(ssid)}</string>
                  <key>Password</key><string>${x(pass)}</string>
                  <key>ProxyType</key><string>Manual</string>
                  <key>ProxyServer</key><string>${x(myIp)}</string>
                  <key>ProxyServerPort</key><integer>$port</integer>
                  <key>PayloadDisplayName</key><string>Wi-Fi Extender network</string>
                  <key>PayloadIdentifier</key><string>com.ilyes.wifiextender.wifi</string>
                  <key>PayloadType</key><string>com.apple.wifi.managed</string>
                  <key>PayloadUUID</key><string>$wifiUuid</string>
                  <key>PayloadVersion</key><integer>1</integer>
                </dict>
              </array>
              <key>PayloadDescription</key><string>Joins your Wi-Fi Extender network and sets its proxy.</string>
              <key>PayloadDisplayName</key><string>Wi-Fi Extender</string>
              <key>PayloadIdentifier</key><string>com.ilyes.wifiextender</string>
              <key>PayloadRemovalDisallowed</key><false/>
              <key>PayloadType</key><string>Configuration</string>
              <key>PayloadUUID</key><string>$rootUuid</string>
              <key>PayloadVersion</key><integer>1</integer>
            </dict>
            </plist>
        """.trimIndent() + "\n"
    }

    private fun respond(out: OutputStream, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.write(bytes)
        out.flush()
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        runCatching {
            out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun closeQuietly(c: java.io.Closeable?) {
        try { c?.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ProxyServer"
        private const val BUFFER_SIZE = 64 * 1024
        private const val DNS_TTL_MS = 60_000L
        private val HOP_BY_HOP = setOf(
            "proxy-connection", "connection", "keep-alive", "proxy-authorization", "proxy-authenticate"
        )
    }
}
