package com.whoarewe.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexCodecTest {

    // ── Regression: bytes >= 0x80 must not be sign-extended (cwage/whoarewe#15) ──

    @Test
    fun `0x80 encodes as exactly two chars`() {
        // The original bug: `"%02x".format(0x80.toByte())` produced "ffffff80"
        // because `Byte` is signed and `String.format` widens to `Int` with
        // sign extension. HexCodec must not have that bug.
        assertEquals("80", HexCodec.bytesToHex(byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `0xff encodes as ff not ffffffff`() {
        assertEquals("ff", HexCodec.bytesToHex(byteArrayOf(0xff.toByte())))
    }

    @Test
    fun `32 byte all-0xff array encodes to exactly 64 chars`() {
        val bytes = ByteArray(32) { 0xff.toByte() }
        val hex = HexCodec.bytesToHex(bytes)
        assertEquals(64, hex.length)
        assertEquals("ff".repeat(32), hex)
    }

    // ── Round-trip across the full byte range ──

    @Test
    fun `full 0x00 to 0xff round trip`() {
        val bytes = ByteArray(256) { it.toByte() }
        val hex = HexCodec.bytesToHex(bytes)
        assertEquals(512, hex.length)
        val decoded = HexCodec.hexToBytes(hex)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun `random byte arrays round trip`() {
        // A handful of fixed seeds, exercising several lengths.
        val cases = listOf(
            byteArrayOf(),
            byteArrayOf(0),
            byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte()),
            ByteArray(32) { (it * 7 + 13).toByte() },
            ByteArray(64) { (255 - it).toByte() }
        )
        for (bytes in cases) {
            val hex = HexCodec.bytesToHex(bytes)
            assertEquals(bytes.size * 2, hex.length)
            assertArrayEquals(bytes, HexCodec.hexToBytes(hex))
        }
    }

    // ── Encoding format ──

    @Test
    fun `bytesToHex emits lowercase`() {
        val bytes = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte())
        assertEquals("abcdef", HexCodec.bytesToHex(bytes))
    }

    @Test
    fun `empty byte array encodes to empty string`() {
        assertEquals("", HexCodec.bytesToHex(byteArrayOf()))
    }

    // ── hexToBytes accepts both cases ──

    @Test
    fun `hexToBytes accepts uppercase`() {
        assertArrayEquals(
            byteArrayOf(0xab.toByte(), 0xcd.toByte()),
            HexCodec.hexToBytes("ABCD")
        )
    }

    @Test
    fun `hexToBytes accepts mixed case`() {
        assertArrayEquals(
            byteArrayOf(0xab.toByte(), 0xcd.toByte()),
            HexCodec.hexToBytes("aBcD")
        )
    }

    // ── hexToBytes rejects malformed input ──

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects odd length`() {
        HexCodec.hexToBytes("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects non hex character`() {
        HexCodec.hexToBytes("ab!d")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToBytes rejects whitespace`() {
        HexCodec.hexToBytes("ab cd")
    }
}
