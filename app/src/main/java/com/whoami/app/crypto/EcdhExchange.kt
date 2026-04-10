package com.whoami.app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.math.BigInteger
import java.security.MessageDigest

object EcdhExchange {

    // Ed25519 field prime: 2^255 - 19
    private val P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /**
     * Derive a shared TOTP secret from our Ed25519 private key and their Ed25519 public key.
     * Both parties compute the same shared secret independently.
     *
     * @param ourPrivateKey raw 32-byte Ed25519 private key (seed)
     * @param theirPublicKey raw 32-byte Ed25519 public key
     * @return 20-byte shared secret suitable for TOTP (HMAC-SHA1 key)
     */
    fun deriveSharedSecret(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val x25519Private = ed25519PrivateToX25519(ourPrivateKey)
        val x25519Public = ed25519PublicToX25519(theirPublicKey)

        val privateKeyParams = X25519PrivateKeyParameters(x25519Private, 0)
        val publicKeyParams = X25519PublicKeyParameters(x25519Public, 0)

        val agreement = X25519Agreement()
        agreement.init(privateKeyParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKeyParams, sharedSecret, 0)

        // Hash the raw ECDH output to get a uniform TOTP key
        // Use SHA-256 then truncate to 20 bytes (160 bits, standard TOTP key size for HMAC-SHA1)
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)

        // Zero out intermediate values
        x25519Private.fill(0)
        sharedSecret.fill(0)

        return hash.copyOf(20)
    }

    /**
     * Convert Ed25519 private key (seed) to X25519 private key.
     * Per RFC 8032: hash the seed with SHA-512, clamp the first 32 bytes.
     */
    private fun ed25519PrivateToX25519(seed: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        // Clamp per RFC 7748
        hash[0] = (hash[0].toInt() and 248).toByte()
        hash[31] = (hash[31].toInt() and 127).toByte()
        hash[31] = (hash[31].toInt() or 64).toByte()
        return hash.copyOf(32)
    }

    /**
     * Convert Ed25519 public key to X25519 public key.
     * Ed25519 encodes a point as the y-coordinate (little-endian) with sign bit in the top bit.
     * X25519 uses the Montgomery u-coordinate: u = (1 + y) / (1 - y) mod p
     */
    private fun ed25519PublicToX25519(ed25519PubKey: ByteArray): ByteArray {
        // Extract y-coordinate (clear the sign bit from the top bit of last byte)
        val yCopy = ed25519PubKey.copyOf()
        yCopy[31] = (yCopy[31].toInt() and 0x7F).toByte()

        // Decode as little-endian unsigned integer
        val yReversed = yCopy.reversedArray()
        val y = BigInteger(1, yReversed)

        // u = (1 + y) * (1 - y)^(-1) mod p
        val one = BigInteger.ONE
        val numerator = one.add(y).mod(P)
        val denominator = one.subtract(y).mod(P)
        val denominatorInv = denominator.modInverse(P)
        val u = numerator.multiply(denominatorInv).mod(P)

        // Encode u as 32-byte little-endian
        val uBytes = u.toByteArray()
        val result = ByteArray(32)
        // BigInteger is big-endian, we need little-endian
        for (i in uBytes.indices) {
            val destIdx = uBytes.size - 1 - i
            if (destIdx < 32) {
                result[destIdx] = uBytes[i]
            }
        }

        return result
    }
}
