package app.tvcouchbrowser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The phone trackpad's wire format and the small part of WebSocket (RFC 6455) it needs.
 * Pure Kotlin with no Android classes, so it can be unit-tested on the JVM.
 *
 * Messages are short text frames: a command letter, optionally followed by two numbers.
 *   m dx dy   move the pointer (phone pixels, scaled on the TV)
 *   s dx dy   scroll the page under the pointer
 *   c         click            2   double-click
 *   d / u     press / release (drag)
 *   l         long press (context menu)
 *   z n       zoom in (n > 0) or out (n < 0)
 *   x         remove the element under the pointer
 *   k         clean up the page (overlays, scroll lock)
 *   p         keep-alive; the TV answers "p"
 */
object PointerProtocol {

    data class Command(val op: Char, val a: Float = 0f, val b: Float = 0f)

    private const val OPS = "msc2dulzxkp"
    private const val MAX_DELTA = 2000f
    const val MAX_TEXT = 64

    /** Parses one message; null if it is malformed, too long or has an unknown command. */
    fun parse(msg: String): Command? {
        if (msg.isEmpty() || msg.length > MAX_TEXT) return null
        val parts = msg.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts[0].length != 1) return null
        val op = parts[0][0]
        if (op !in OPS) return null
        val needs = when (op) { 'm', 's' -> 2; 'z' -> 1; else -> 0 }
        if (parts.size != needs + 1) return null
        val nums = FloatArray(2)
        for (i in 0 until needs) {
            val v = parts[i + 1].toFloatOrNull() ?: return null
            if (!v.isFinite()) return null
            nums[i] = v.coerceIn(-MAX_DELTA, MAX_DELTA)
        }
        return Command(op, nums[0], nums[1])
    }

    // ---- WebSocket ----------------------------------------------------------------------------

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** The Sec-WebSocket-Accept value for a client key, or null if the key isn't a valid 16-byte nonce. */
    fun acceptKey(key: String?): String? {
        if (key == null) return null
        val raw = try { Base64.getDecoder().decode(key.trim()) } catch (e: IllegalArgumentException) { return null }
        if (raw.size != 16) return null
        val sha = MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha)
    }

    class Frame(val opcode: Int, val payload: ByteArray)

    class ProtocolException(val code: Int, msg: String) : IOException(msg)

    const val OP_TEXT = 1
    const val OP_CLOSE = 8
    const val OP_PING = 9
    const val OP_PONG = 10

    /**
     * Reads one frame from a client. Clients must mask; fragmented, binary and oversized frames are
     * refused with a [ProtocolException] carrying the close code to send. Returns null at end of stream.
     */
    fun readFrame(input: InputStream, maxPayload: Int = 256): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val fin = b0 and 0x80 != 0
        if (b0 and 0x70 != 0) throw ProtocolException(1002, "reserved bits set")
        val opcode = b0 and 0x0f
        if (b1 and 0x80 == 0) throw ProtocolException(1002, "client frame not masked")
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = ((readByte(input) shl 8) or readByte(input)).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or readByte(input).toLong() } }
        val control = opcode >= 8
        if (control && (len > 125 || !fin)) throw ProtocolException(1002, "bad control frame")
        when (opcode) {
            OP_TEXT, OP_CLOSE, OP_PING, OP_PONG -> Unit
            0, 2 -> throw ProtocolException(1003, "only unfragmented text frames are accepted")
            else -> throw ProtocolException(1002, "unknown opcode $opcode")
        }
        if (!fin) throw ProtocolException(1003, "fragmented messages are not accepted")
        if (len > maxPayload) throw ProtocolException(1009, "message too big")
        val mask = ByteArray(4); readFully(input, mask)
        val payload = ByteArray(len.toInt()); readFully(input, payload)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(opcode, payload)
    }

    /** Writes an unmasked server frame (servers never mask). Payloads here are always short. */
    fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val buf = ByteArrayOutputStream(payload.size + 4)
        buf.write(0x80 or opcode)
        if (payload.size < 126) buf.write(payload.size)
        else { buf.write(126); buf.write(payload.size shr 8); buf.write(payload.size and 0xff) }
        buf.write(payload)
        out.write(buf.toByteArray()); out.flush()
    }

    fun closePayload(code: Int) = byteArrayOf((code shr 8).toByte(), (code and 0xff).toByte())

    private fun readByte(input: InputStream): Int {
        val b = input.read(); if (b < 0) throw IOException("eof"); return b
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) throw IOException("eof"); n += r }
    }

    /** A token bucket: [perSecond] messages a second on average, bursts up to the same number. */
    class RateLimit(private val perSecond: Int, private val clock: () -> Long = System::nanoTime) {
        private var tokens = perSecond.toDouble()
        private var last = clock()
        fun allow(): Boolean {
            val now = clock()
            tokens = minOf(perSecond.toDouble(), tokens + (now - last) / 1e9 * perSecond)
            last = now
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }
}
