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

    // ---- Display-name validation (cwage/whoarewe#26) ----

    @Test
    fun `decode accepts a name at the maximum length`() {
        val maxName = "a".repeat(QrCodeUtils.MAX_DISPLAY_NAME_LENGTH)
        val encoded = QrCodeUtils.encode(maxName, testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals(maxName, decoded!!.displayName)
    }

    @Test
    fun `decode rejects a name one character over the maximum length`() {
        val tooLong = "a".repeat(QrCodeUtils.MAX_DISPLAY_NAME_LENGTH + 1)
        val encoded = QrCodeUtils.encode(tooLong, testKey)
        assertNull(
            "A display name of length ${QrCodeUtils.MAX_DISPLAY_NAME_LENGTH + 1} must be rejected",
            QrCodeUtils.decode(encoded)
        )
    }

    @Test
    fun `decode rejects a name with an embedded newline`() {
        // Embedded \n is the "two visual rows from one contact" impersonation
        // vector. Must be rejected so ContactListScreen cannot render the
        // attacker's single trusted contact as two separate rows.
        val encoded = QrCodeUtils.encode("Alice\nBob: 654321", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with an embedded carriage return`() {
        val encoded = QrCodeUtils.encode("Alice\rBob", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with an embedded tab`() {
        val encoded = QrCodeUtils.encode("Alice\tBob", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with a right-to-left override`() {
        // U+202E is the classic homoglyph / direction-flip vector.
        val encoded = QrCodeUtils.encode("Alice\u202EBob", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with a zero-width joiner`() {
        // U+200D is category Cf (format). Kills the "invisible glyph" class.
        val encoded = QrCodeUtils.encode("Ali\u200Dce", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with a null byte`() {
        val encoded = QrCodeUtils.encode("Alice\u0000", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with a Unicode line separator`() {
        // U+2028 is category Zl, not Cc. Compose / many text renderers still
        // break lines on it, so it's a "two visual rows" vector that bypassed
        // the original Cc/Cf-only check. Copilot flagged this on PR #36.
        val encoded = QrCodeUtils.encode("Alice\u2028Bob", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode rejects a name with a Unicode paragraph separator`() {
        // U+2029 is category Zp — same story as U+2028.
        val encoded = QrCodeUtils.encode("Alice\u2029Bob", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode accepts a name with a regular ASCII space`() {
        // U+0020 is Zs (SPACE_SEPARATOR). We intentionally do NOT reject
        // Zs, because "Alice Smith" is a perfectly fine display name.
        // This test pins that — if someone later "tightens" the filter to
        // reject all Z* categories, it will fire here.
        val encoded = QrCodeUtils.encode("Alice Smith", testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals("Alice Smith", decoded!!.displayName)
    }

    @Test
    fun `decode NFC-normalizes combining characters to their precomposed form`() {
        // "é" can be written as U+00E9 (precomposed) or U+0065 U+0301
        // (e + combining acute accent). NFC collapses the latter to the
        // former, so both devices should store the same bytes in Room
        // regardless of which encoding the sender's keyboard produced.
        val decomposed = "Al\u0065\u0301x"  // "Aléx" via combining accent
        val precomposed = "Al\u00E9x"       // "Aléx" via precomposed é
        val encoded = QrCodeUtils.encode(decomposed, testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals(precomposed, decoded!!.displayName)
    }

    @Test
    fun `decode trims leading and trailing whitespace`() {
        val encoded = QrCodeUtils.encode("  alice  ", testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals("alice", decoded!!.displayName)
    }

    @Test
    fun `decode rejects a whitespace-only name`() {
        val encoded = QrCodeUtils.encode("   ", testKey)
        assertNull(QrCodeUtils.decode(encoded))
    }

    @Test
    fun `decode accepts a plain Unicode name`() {
        // Non-ASCII letters are fine — only Cc/Cf are blocked.
        val encoded = QrCodeUtils.encode("Åsa Bäck", testKey)
        val decoded = QrCodeUtils.decode(encoded)
        assertNotNull(decoded)
        assertEquals("Åsa Bäck", decoded!!.displayName)
    }
}
