package com.whoami.app.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `derived TOTP codes match between both parties`() {
        val (alicePrivate, alicePublic) = generateEd25519KeyPair()
        val (bobPrivate, bobPublic) = generateEd25519KeyPair()

        val secretAlice = EcdhExchange.deriveSharedSecret(alicePrivate, bobPublic)
        val secretBob = EcdhExchange.deriveSharedSecret(bobPrivate, alicePublic)

        // Verify TOTP codes match at several different times
        val times = listOf(0L, 60_000L, 1_000_000_000L * 1000, System.currentTimeMillis())
        for (time in times) {
            val codeAlice = TotpGenerator.generateCode(secretAlice, time)
            val codeBob = TotpGenerator.generateCode(secretBob, time)
            assert(codeAlice == codeBob) {
                "Codes should match at time=$time: alice=$codeAlice, bob=$codeBob"
            }
        }
    }
}
