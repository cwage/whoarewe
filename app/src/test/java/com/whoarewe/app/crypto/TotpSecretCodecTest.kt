package com.whoarewe.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Host-JVM coverage for [TotpSecretCodec] — see cwage/whoarewe#32. Pins the
 * round-trip, the wrong-key rejection (which is the *only* thing standing
 * between a stolen SQLite file and a working TOTP capability), and the
 * per-encrypt fresh-IV guarantee.
 */
class TotpSecretCodecTest {

    private val sampleDek = ByteArray(32) { (it + 1).toByte() }
    private val samplePlaintext = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14
    )

    @Test
    fun encryptThenDecrypt_roundTripsPlaintext() {
        val encrypted = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        val decrypted = TotpSecretCodec.decrypt(encrypted, sampleDek)
        assertArrayEquals(samplePlaintext, decrypted)
    }

    @Test
    fun encrypt_producesFreshIvEveryCall() {
        // AES-GCM catastrophically fails under IV reuse with the same key,
        // so the codec must generate a new IV on each call. Generating 8
        // and asserting they're all distinct is more than enough to catch
        // "oops I accidentally cached the IV" while being cheap.
        val ivs = (0 until 8).map {
            TotpSecretCodec.encrypt(samplePlaintext, sampleDek).iv.toList()
        }
        assertEquals(
            "IVs must all be distinct per call",
            ivs.size,
            ivs.toSet().size
        )
    }

    @Test
    fun encrypt_differentIvsProduceDifferentCiphertexts() {
        val a = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        val b = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        assertFalse(
            "Same plaintext + key + fresh IV must never produce identical ciphertext",
            a.ciphertext.contentEquals(b.ciphertext)
        )
    }

    @Test
    fun decryptWithWrongDek_throws() {
        val encrypted = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        val wrongDek = ByteArray(32) { (it + 42).toByte() }
        // JCE AES-GCM surfaces tag mismatch as AEADBadTagException (a
        // subclass of BadPaddingException). Pin the specific type so a
        // future refactor that swallows it into a generic RuntimeException
        // fires this test.
        assertThrows(AEADBadTagException::class.java) {
            TotpSecretCodec.decrypt(encrypted, wrongDek)
        }
    }

    @Test
    fun decryptWithTamperedCiphertext_throws() {
        val encrypted = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        val tampered = encrypted.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            TotpSecretCodec.decrypt(
                TotpSecretCodec.EncryptedSecret(tampered, encrypted.iv),
                sampleDek
            )
        }
    }

    @Test
    fun decryptWithTamperedIv_throws() {
        val encrypted = TotpSecretCodec.encrypt(samplePlaintext, sampleDek)
        val tamperedIv = encrypted.iv.copyOf()
        tamperedIv[0] = (tamperedIv[0].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            TotpSecretCodec.decrypt(
                TotpSecretCodec.EncryptedSecret(encrypted.ciphertext, tamperedIv),
                sampleDek
            )
        }
    }

    @Test
    fun encrypt_rejectsShortDek() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpSecretCodec.encrypt(samplePlaintext, ByteArray(16))
        }
    }

    @Test
    fun encrypt_rejectsLongDek() {
        assertThrows(IllegalArgumentException::class.java) {
            TotpSecretCodec.encrypt(samplePlaintext, ByteArray(64))
        }
    }

    @Test
    fun generateDek_returns32Bytes() {
        assertEquals(32, TotpSecretCodec.generateDek().size)
    }

    @Test
    fun generateDek_differsOnEachCall() {
        // Not a statistical test — just catches the "whoops I hardcoded
        // the seed" failure mode. Two calls should essentially never
        // return the same 32 bytes from SecureRandom.
        val a = TotpSecretCodec.generateDek()
        val b = TotpSecretCodec.generateDek()
        assertFalse(
            "Two SecureRandom DEKs must differ",
            a.contentEquals(b)
        )
        // And neither should be all-zero (the default ByteArray state).
        assertTrue(a.any { it != 0.toByte() })
        assertTrue(b.any { it != 0.toByte() })
        // Hush the unused-variable warning on assertNotEquals if it gets added.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun encryptedSecret_equalsUsesContentComparison() {
        // ByteArray.equals is reference equality by default — the class
        // overrides it to content equality, which matters for any test
        // or code path that compares EncryptedSecret instances. Pin it.
        val a = TotpSecretCodec.EncryptedSecret(
            ciphertext = byteArrayOf(1, 2, 3),
            iv = byteArrayOf(4, 5, 6)
        )
        val b = TotpSecretCodec.EncryptedSecret(
            ciphertext = byteArrayOf(1, 2, 3),
            iv = byteArrayOf(4, 5, 6)
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
