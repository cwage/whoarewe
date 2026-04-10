package com.whoarewe.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeUtilsTest {

    private val testName = "alice"
    private val testKey = ByteArray(32) { it.toByte() } // 0x00..0x1f

    @Test
    fun `encode produces whoarewe v1 format`() {
        val encoded = QrCodeUtils.encode(testName, testKey)
        assert(encoded.startsWith("whoarewe:v1:"))
        assert(encoded.endsWith(":alice"))
    }

    @Test
    fun `decode roundtrips with encode`() {
        val encoded = QrCodeUtils.encode(testName, testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals(testName, decoded!!.displayName)
        assertArrayEquals(testKey, decoded.publicKey)
    }

    @Test
    fun `decode rejects wrong scheme`() {
        assertNull(QrCodeUtils.decode("notright:v1:AAAA:alice"))
    }

    @Test
    fun `decode rejects wrong version`() {
        val encoded = QrCodeUtils.encode(testName, testKey)
        val tampered = encoded.replace("v1", "v2")
        assertNull(QrCodeUtils.decode(tampered))
    }

    @Test
    fun `decode rejects empty name`() {
        val encoded = QrCodeUtils.encode("x", testKey)
        val tampered = encoded.substringBeforeLast(":") + ":"
        assertNull(QrCodeUtils.decode(tampered))
    }

    @Test
    fun `decode rejects wrong key length`() {
        // 16 bytes instead of 32
        val shortKey = ByteArray(16) { it.toByte() }
        val encoded = QrCodeUtils.encode(testName, shortKey)
        // This will encode fine but decode should reject (not 32 bytes)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects garbage input`() {
        assertNull(QrCodeUtils.decode(""))
        assertNull(QrCodeUtils.decode("not a qr code"))
        assertNull(QrCodeUtils.decode("whoarewe:v1"))
        assertNull(QrCodeUtils.decode("whoarewe:v1:notbase64!@#:name"))
    }

    @Test
    fun `decode rejects too few parts`() {
        assertNull(QrCodeUtils.decode("whoarewe:v1:keyonly"))
    }

    @Test
    fun `name with colons roundtrips correctly`() {
        // The name is the 4th part with limit=4, so colons in name are preserved
        val nameWithColon = "alice:bob"
        val encoded = QrCodeUtils.encode(nameWithColon, testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals(nameWithColon, decoded!!.displayName)
    }

    @Test
    fun `different keys produce different encodings`() {
        val key1 = ByteArray(32) { 0 }
        val key2 = ByteArray(32) { 1 }
        val enc1 = QrCodeUtils.encode(testName, key1)
        val enc2 = QrCodeUtils.encode(testName, key2)
        assert(enc1 != enc2)
    }
}
