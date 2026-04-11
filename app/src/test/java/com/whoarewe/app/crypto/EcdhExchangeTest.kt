package com.whoarewe.app.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class EcdhExchangeTest {

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val privateKey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return privateKey to publicKey
    }

    @Test
    fun `both sides derive the same shared secret`() {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()

        val secretAlice = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        val secretBob = EcdhExchange.deriveSharedSecret(bobPrivate, alicePublic)

        assertArrayEquals(
            "Alice and Bob should derive the same shared secret",
            secretAlice,
            secretBob
        )
    }

    @Test
    fun `shared secret is 20 bytes (TOTP key size)`() {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()

        val secret = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        assert(secret.size == 20) { "Expected 20 bytes, got ${secret.size}" }
    }

    @Test
    fun `different key pairs produce different shared secrets`() {
        val (alicePrivate, _) = generateEd25519KeyPair()
        val (_, bobPublic) = generateEd25519KeyPair()
        val (_, charliePublic) = generateEd25519KeyPair()

        val secretAB = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        val secretAC = EcdhExchange.deriveSharedSecret(alicePrivate, charliePublic)

        assertFalse(
            "Different pairs should produce different secrets",
            secretAB.contentEquals(secretAC)
        )
    }

    @Test
    fun `shared secret is deterministic`() {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (_, bobPublic) = generateEd25519KeyPair()

        val secret1 = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        val secret2 = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)

        assertArrayEquals("Same inputs should produce same output", secret1, secret2)
    }

    @Test
    fun `multiple key pairs all produce matching secrets bidirectionally`() {
        // Test with 5 different key pairs to catch edge cases
        repeat(5) {
            val (privA, pubA) = generateEd25519KeyPair()
            val (privB, pubB) = generateEd25519KeyPair()

            val secretA = EcdhExchange.deriveSharedSecret(privA, pubB)
            val secretB = EcdhExchange.deriveSharedSecret(privB, pubA)

            assertArrayEquals("Iteration $it: secrets should match", secretA, secretB)
        }
    }

    // ---- Public-key validation (cwage/whoarewe#21) ----

    @Test
    fun `isValidEd25519PublicKey accepts freshly generated keys`() {
        repeat(20) {
            val (_, pub) = generateEd25519KeyPair()
            assertTrue(
                "Generated Ed25519 key should validate",
                EcdhExchange.isValidEd25519PublicKey(pub)
            )
        }
    }

    @Test
    fun `isValidEd25519PublicKey rejects wrong-sized input`() {
        assertFalse(EcdhExchange.isValidEd25519PublicKey(ByteArray(0)))
        assertFalse(EcdhExchange.isValidEd25519PublicKey(ByteArray(31)))
        assertFalse(EcdhExchange.isValidEd25519PublicKey(ByteArray(33)))
        assertFalse(EcdhExchange.isValidEd25519PublicKey(ByteArray(64)))
    }

    @Test
    fun `isValidEd25519PublicKey rejects y equals 1 (identity, denominator zero)`() {
        // y = 1 encodes to [0x01, 0, 0, ..., 0]. Without this rejection,
        // ed25519PublicToX25519 would divide by zero mid-pair.
        val identity = ByteArray(32).also { it[0] = 0x01 }
        assertFalse(
            "y == 1 must be rejected",
            EcdhExchange.isValidEd25519PublicKey(identity)
        )
        // Same y == 1 with the x-sign bit set (0x80 in byte 31) should also
        // be rejected, since we mask the sign bit before the equality check.
        val identitySignSet = identity.copyOf().also { it[31] = 0x80.toByte() }
        assertFalse(
            "y == 1 with x-sign bit set must also be rejected",
            EcdhExchange.isValidEd25519PublicKey(identitySignSet)
        )
    }

    @Test
    fun `isValidEd25519PublicKey rejects non-canonical encoding y equals p`() {
        // p = 2^255 - 19, little-endian 32 bytes: 0xED 0xFF…0xFF 0x7F
        val yEqualsP = ByteArray(32) { 0xFF.toByte() }
        yEqualsP[0] = 0xED.toByte()
        yEqualsP[31] = 0x7F.toByte()
        assertFalse(
            "y == p is non-canonical (RFC 8032 §5.1.3) and must be rejected",
            EcdhExchange.isValidEd25519PublicKey(yEqualsP)
        )
    }

    @Test
    fun `isValidEd25519PublicKey rejects non-canonical encoding y equals p plus 1`() {
        val yEqualsPPlusOne = ByteArray(32) { 0xFF.toByte() }
        yEqualsPPlusOne[0] = 0xEE.toByte()
        yEqualsPPlusOne[31] = 0x7F.toByte()
        assertFalse(
            "y == p+1 is non-canonical and must be rejected",
            EcdhExchange.isValidEd25519PublicKey(yEqualsPPlusOne)
        )
    }

    @Test
    fun `deriveSharedSecret throws InvalidPublicKeyException on y equals 1`() {
        val (ourPriv, _) = generateEd25519KeyPair()
        val identity = ByteArray(32).also { it[0] = 0x01 }
        val ex = assertThrows(EcdhExchange.InvalidPublicKeyException::class.java) {
            EcdhExchange.deriveSharedSecret(ourPriv, identity)
        }
        // Message should be human-readable and not a raw JCA trace.
        assertTrue(
            "Message should mention the validation failure, got: ${ex.message}",
            ex.message?.contains("validation") == true
        )
    }

    @Test
    fun `deriveSharedSecret throws InvalidPublicKeyException on non-canonical y equals p`() {
        val (ourPriv, _) = generateEd25519KeyPair()
        val yEqualsP = ByteArray(32) { 0xFF.toByte() }
        yEqualsP[0] = 0xED.toByte()
        yEqualsP[31] = 0x7F.toByte()
        assertThrows(EcdhExchange.InvalidPublicKeyException::class.java) {
            EcdhExchange.deriveSharedSecret(ourPriv, yEqualsP)
        }
    }

    @Test
    fun `deriveSharedSecret throws InvalidPublicKeyException on wrong size`() {
        val (ourPriv, _) = generateEd25519KeyPair()
        assertThrows(EcdhExchange.InvalidPublicKeyException::class.java) {
            EcdhExchange.deriveSharedSecret(ourPriv, ByteArray(31))
        }
    }

    // ---- Existing coverage (functional correctness) ----

    @Test
    fun `derived TOTP codes match between both parties`() {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()

        val secretAlice = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        val secretBob = EcdhExchange.deriveSharedSecret(bobPrivate, alicePublic)

        // Verify TOTP codes match at several different times — including a sample
        // straddling a wall-clock period boundary, since both sides should still
        // agree as long as their inputs to generateCode are identical.
        val periodMillis = TotpGenerator.PERIOD_SECONDS * 1000
        val times = listOf(
            0L,
            periodMillis,
            1_000_000_000L * 1000,
            System.currentTimeMillis()
        )
        for (time in times) {
            val codeAlice = TotpGenerator.generateCode(secretAlice, time)
            val codeBob = TotpGenerator.generateCode(secretBob, time)
            assert(codeAlice == codeBob) {
                "Codes should match at time=$time: alice=$codeAlice, bob=$codeBob"
            }
        }
    }
}
