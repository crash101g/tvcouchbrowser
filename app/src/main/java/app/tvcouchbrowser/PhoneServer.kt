package app.tvcouchbrowser

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The small HTTP server behind the phone page. It runs only while the browser is in the foreground.
 * Every request needs the pairing token, must come from a private address on the TV's own subnet,
 * and can only carry a URL, text or a key name; never JavaScript.
 */
class PhoneServer(
    private val context: Context,
    private val tokens: TokenStore,
    private val actions: Actions,
) {
    interface Actions {
        fun openUrl(input: String): Boolean
        fun type(text: String)
        fun key(name: String): Boolean
    }

    private var server: ServerSocket? = null
    private var pool = Executors.newFixedThreadPool(3)
    private val failures = ConcurrentHashMap<String, Failures>()
    private var nsdListener: NsdManager.RegistrationListener? = null

    @Volatile
    var port = 0
        private set

    private class Failures(var count: Int, var windowStart: Long, var blockedUntil: Long)

    fun start() {
        if (server != null) return
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(3)
        val socket = PORTS.firstNotNullOfOrNull { p ->
            try { ServerSocket(p, 8) } catch (e: Exception) { null }
        } ?: return
        server = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "phone-server").start()
        advertise()
    }

    fun stop() {
        server?.close()
        server = null
        pool.shutdownNow()
        nsdListener?.let { l ->
            try { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } catch (_: Exception) {}
        }
        nsdListener = null
    }

    private fun advertise() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = "_http._tcp"
            port = this@PhoneServer.port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "mDNS registered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        nsdListener = listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) { Log.w(TAG, "mDNS: $e") }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: SocketException) { return }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                client.close()
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { serve(it) }
    }

    private fun serve(s: Socket) {
        s.soTimeout = 10_000
        val remote = s.inetAddress
        if (!isLanPeer(remote)) { Log.w(TAG, "refused ${remote.hostAddress}"); return }
        val req = try { readRequest(BufferedInputStream(s.getInputStream())) } catch (e: Exception) { null }
            ?: return respond(s, 400, "text/plain", "Bad request")
        val ip = remote.hostAddress ?: "?"

        val f = failures[ip]
        val now = System.currentTimeMillis()
        if (f != null && f.blockedUntil > now) return respond(s, 429, "text/plain", "Too many attempts. Try again later.")

        if (req.path == "/favicon.ico") return respond(s, 404, "text/plain", "")
        if (!tokens.matches(req.query["t"])) {
            recordFailure(ip, now)
            Thread.sleep(300)
            return respond(s, 403, "text/html; charset=utf-8", EXPIRED_PAGE)
        }
        failures.remove(ip)

        when {
            req.method == "GET" && req.path == "/" ->
                respond(s, 200, "text/html; charset=utf-8", context.assets.open("phone.html").use { String(it.readBytes()) })
            req.method == "POST" && req.path == "/open" -> {
                val url = extract(req.body, "url")
                if (url.isBlank() || url.length > 4096) return respond(s, 400, "text/plain", "No URL")
                respondJson(s, actions.openUrl(url.trim()))
            }
            req.method == "POST" && req.path == "/type" -> {
                val text = extract(req.body, "text").filter { it == '\n' || it >= ' ' }.take(2000)
                actions.type(text); respondJson(s, true)
            }
            req.method == "POST" && req.path == "/key" -> respondJson(s, actions.key(req.query["k"] ?: ""))
            else -> respond(s, 404, "text/plain", "Not found")
        }
    }

    private fun recordFailure(ip: String, now: Long) {
        val f = failures.getOrPut(ip) { Failures(0, now, 0) }
        synchronized(f) {
            if (now - f.windowStart > FAIL_WINDOW_MS) { f.count = 0; f.windowStart = now }
            if (++f.count >= MAX_FAILURES) { f.blockedUntil = now + BLOCK_MS; f.count = 0 }
        }
    }

    /** True for private (RFC 1918 / ULA) or link-local peers on the same subnet as one of the TV's addresses. */
    private fun isLanPeer(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) return false
        val private = when (addr) {
            is Inet4Address -> addr.isSiteLocalAddress || addr.isLinkLocalAddress
            is Inet6Address -> addr.isLinkLocalAddress || (addr.address[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
        if (!private) return false
        val peer = addr.address
        for (nif in NetworkInterface.getNetworkInterfaces() ?: return false) {
            if (!nif.isUp || nif.isLoopback) continue
            for (ia in nif.interfaceAddresses) {
                val local = ia.address.address
                if (local.size != peer.size) continue
                if (samePrefix(local, peer, ia.networkPrefixLength.toInt())) return true
            }
        }
        return false
    }

    private fun samePrefix(a: ByteArray, b: ByteArray, bits: Int): Boolean {
        var left = bits
        for (i in a.indices) {
            if (left <= 0) return true
            val mask = if (left >= 8) 0xff else (0xff shl (8 - left)) and 0xff
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            left -= 8
        }
        return true
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.size() > 8192) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':'); if (i > 0) l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim() else null
        }.toMap()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 16_384) return null
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        return Request(parts[0], path, if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap(), String(body, 0, read))
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    /** Accepts a JSON object, a form body or plain text, so the iPhone Shortcut can send any of them. */
    private fun extract(body: String, field: String): String {
        val t = body.trim()
        if (t.startsWith("{")) return try { JSONObject(t).optString(field) } catch (e: Exception) { "" }
        if (t.startsWith("$field=")) return parseQuery(t)[field] ?: ""
        return body
    }

    private fun respondJson(s: Socket, ok: Boolean) =
        respond(s, if (ok) 200 else 422, "application/json", if (ok) "{\"ok\":true}" else "{\"ok\":false}")

    private fun respond(s: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 422 -> "Unprocessable"; else -> "Too Many Requests" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'\r\n" +
            "Connection: close\r\n\r\n"
        try {
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "PhoneServer"
        const val SERVICE_NAME = "TVCouchBrowser"
        val PORTS = listOf(8765, 8766, 8767, 8768)
        private const val MAX_FAILURES = 5
        private const val FAIL_WINDOW_MS = 10 * 60_000L
        private const val BLOCK_MS = 10 * 60_000L
        private const val EXPIRED_PAGE = "<!doctype html><meta name=viewport content='width=device-width'>" +
            "<body style='font:18px -apple-system,sans-serif;padding:24px'>This link has expired. " +
            "On the TV, open the menu, choose <b>Pair phone</b> and scan the new QR code.</body>"
    }
}
