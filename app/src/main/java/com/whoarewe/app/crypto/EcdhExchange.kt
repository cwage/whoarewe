package com.whoarewe.app.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519Field
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest

object EcdhExchange {

    /**
     * Thrown when an Ed25519 public key fails validation — either because the
     * 32-byte encoding is non-canonical (y ≥ p, per RFC 8032 §5.1.3), the
     * point is not on the curve, or y == 1 (the identity point, which makes
     * the Edwards → Montgomery birational map u = (1 + y) / (1 − y) singular).
     *
     * See cwage/whoarewe#21.
     */
    class InvalidPublicKeyException(message: String) : IllegalArgumentException(message)

    /**
     * Cheap upfront validator for a candidate peer Ed25519 public key. Use
     * this *before* requesting a biometric unlock so a hostile or malformed
     * QR can't cost the user an auth prompt just to fail mid-pair.
     *
     * Returns true iff:
     *  - the input is 32 bytes,
     *  - [Ed25519.validatePublicKeyPartial] accepts it (canonical encoding,
     *    y < p, recovered x on the curve), and
     *  - y != 1 (rejecting the Edwards identity / birational singularity).
     */
    fun isValidEd25519PublicKey(ed25519PubKey: ByteArray): Boolean {
        if (ed25519PubKey.size != Ed25519.PUBLIC_KEY_SIZE) return false
        if (!Ed25519.validatePublicKeyPartial(ed25519PubKey, 0)) return false
        if (isIdentityY(ed25519PubKey)) return false
        return true
    }

    /**
     * Derive a shared TOTP secret from our Ed25519 private key and their Ed25519 public key.
     * Both parties compute the same shared secret independently.
     *
     * @param ourPrivateKey raw 32-byte Ed25519 private key (seed)
     * @param theirPublicKey raw 32-byte Ed25519 public key
     * @return 20-byte shared secret suitable for TOTP (HMAC-SHA1 key)
     * @throws InvalidPublicKeyException if [theirPublicKey] fails validation.
     *   Callers that have already run [isValidEd25519PublicKey] will not see
     *   this in practice, but it is preserved as defence-in-depth.
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
     * Convert a validated Ed25519 public key to its X25519 Montgomery form via
     * the Edwards → Montgomery birational map
     *
     *     u = (1 + y) / (1 − y) mod p
     *
     * using BouncyCastle's [X25519Field] primitives rather than hand-rolled
     * `java.math.BigInteger` arithmetic. BC's [Ed25519.validatePublicKeyPartial]
     * enforces canonical encoding (y < p) and on-curve membership — see
     * [isValidEd25519PublicKey], which this function calls as an upfront
     * precondition guard.
     *
     * @throws InvalidPublicKeyException if [ed25519PubKey] fails validation.
     */
    private fun ed25519PublicToX25519(ed25519PubKey: ByteArray): ByteArray {
        if (!isValidEd25519PublicKey(ed25519PubKey)) {
            throw InvalidPublicKeyException(
                "Ed25519 public key failed validation: non-canonical encoding, off-curve point, or y == 1"
            )
        }

        // Clear the sign-of-x bit (top bit of byte 31) to recover the 255-bit
        // little-endian y encoding that X25519Field.decode expects.
        val yBytes = ed25519PubKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()

        val y = X25519Field.create()
        X25519Field.decode(yBytes, 0, y)

        val one = X25519Field.create()
        X25519Field.one(one)

        val numerator = X25519Field.create()
        X25519Field.add(one, y, numerator)

        val denominator = X25519Field.create()
        X25519Field.sub(one, y, denominator)

        // isValidEd25519PublicKey already rejected y == 1. The explicit
        // post-normalize zero check here guards against any unreduced
        // representation slipping through and is cheap.
        X25519Field.normalize(denominator)
        if (X25519Field.isZeroVar(denominator)) {
            throw InvalidPublicKeyException("Ed25519 public key has a zero denominator (y == 1)")
        }

        val denominatorInv = X25519Field.create()
        X25519Field.inv(denominator, denominatorInv)

        val u = X25519Field.create()
        X25519Field.mul(numerator, denominatorInv, u)
        X25519Field.normalize(u)

        val result = ByteArray(32)
        X25519Field.encode(u, result, 0)
        return result
    }

    /**
     * True iff [ed25519PubKey] decodes to y == 1 (the Edwards identity, which
     * makes the birational map's denominator zero). Checked with the x-sign
     * bit masked off, so both `01 00..00` and `01 00..00 80` are caught.
     */
    private fun isIdentityY(ed25519PubKey: ByteArray): Boolean {
        if (ed25519PubKey[0] != 0x01.toByte()) return false
        for (i in 1 until 31) {
            if (ed25519PubKey[i] != 0.toByte()) return false
        }
        return (ed25519PubKey[31].toInt() and 0x7F) == 0
    }
}
