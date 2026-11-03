package app.tvcouchbrowser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PointerProtocolTest {

    @Test fun parsesCommands() {
        assertEquals(PointerProtocol.Command('m', 12f, -4f), PointerProtocol.parse("m 12 -4"))
        assertEquals(PointerProtocol.Command('c'), PointerProtocol.parse("c"))
        assertEquals(PointerProtocol.Command('z', -1f), PointerProtocol.parse("z -1"))
        assertEquals(PointerProtocol.Command('s', 0f, 10f), PointerProtocol.parse("s 0 10"))
    }

    @Test fun rejectsMalformed() {
        for (bad in listOf("", "q", "m", "m 1", "m 1 2 3", "c 1", "mm 1 2", "m a b", "m NaN 1", "m Infinity 1",
            "z", "x".repeat(65), "javascript:alert(1)")) {
            assertNull(bad, PointerProtocol.parse(bad))
        }
    }

    @Test fun clampsHugeMoves() {
        assertEquals(2000f, PointerProtocol.parse("m 999999 0")!!.a)
        assertEquals(-2000f, PointerProtocol.parse("m 0 -999999")!!.b)
    }

    @Test fun acceptKeyMatchesRfcExample() {
        // RFC 6455 section 1.3
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", PointerProtocol.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertNull(PointerProtocol.acceptKey("short"))
        assertNull(PointerProtocol.acceptKey("not base64 at all!!"))
        assertNull(PointerProtocol.acceptKey(null))
    }

    private fun clientFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val m = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> out.write(m or payload.size)
            else -> { out.write(m or 126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
        }
        val mask = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)
        if (masked) out.write(mask)
        payload.forEachIndexed { i, b -> out.write(if (masked) b.toInt() xor mask[i and 3].toInt() else b.toInt()) }
        return out.toByteArray()
    }

    @Test fun readsMaskedTextFrame() {
        val f = PointerProtocol.readFrame(ByteArrayInputStream(clientFrame(1, "m 3 4".toByteArray())))!!
        assertEquals(PointerProtocol.OP_TEXT, f.opcode)
        assertEquals("m 3 4", String(f.payload))
    }

    private fun expectClose(bytes: ByteArray, code: Int) {
        try { PointerProtocol.readFrame(ByteArrayInputStream(bytes)); fail("accepted a bad frame") }
        catch (e: PointerProtocol.ProtocolException) { assertEquals(code, e.code) }
    }

    @Test fun refusesBadFrames() {
        expectClose(clientFrame(1, "c".toByteArray(), masked = false), 1002)
        expectClose(clientFrame(2, "c".toByteArray()), 1003) // binary
        expectClose(clientFrame(1, "c".toByteArray(), fin = false), 1003) // fragmented
        expectClose(clientFrame(1, ByteArray(300)), 1009) // too big
        expectClose(clientFrame(9, ByteArray(126)), 1002) // oversized ping
    }

    @Test fun endOfStreamIsNull() {
        assertNull(PointerProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun writesUnmaskedServerFrame() {
        val out = ByteArrayOutputStream()
        PointerProtocol.writeFrame(out, PointerProtocol.OP_TEXT, "p".toByteArray())
        assertArrayEquals(byteArrayOf(0x81.toByte(), 1, 'p'.code.toByte()), out.toByteArray())
    }

    @Test fun rateLimitDropsFloods() {
        var now = 0L
        val limit = PointerProtocol.RateLimit(120) { now }
        var allowed = 0
        repeat(1000) { if (limit.allow()) allowed++ } // 1000 at once
        assertEquals(120, allowed)
        assertFalse(limit.allow())
        now += 500_000_000L // half a second later: 60 more
        allowed = 0
        repeat(1000) { if (limit.allow()) allowed++ }
        assertEquals(60, allowed)
        now += 2_000_000_000L
        assertTrue(limit.allow())
    }
}
