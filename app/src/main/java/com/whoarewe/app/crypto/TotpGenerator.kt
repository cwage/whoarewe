package com.whoarewe.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpGenerator {
    /**
     * Default rotation period in seconds, app-wide.
     *
     * This is the **single tunable** for how often the displayed code rotates.
     * The display tick loop, the contact list progress ring, the e2e tests, and
     * any future verifier all derive from this value — nothing else hard-codes
     * a step size.
     *
     * Currently 5 minutes (300s). The rationale and trade-off are documented in
     * `docs/pairing.md`. Short version: with a 60s period the wall-clock window
     * boundary races the display refresh, and two phones can briefly disagree
     * on which code is current — which is exactly the failure mode the product
     * is supposed to make impossible. 300s gives ample slack for any plausible
     * inter-device clock skew while still rotating often enough that a captured
     * code is useless within the same conversation.
     */
    const val PERIOD_SECONDS = 300L
    private const val DIGITS = 6

    fun generateCode(
        secret: ByteArray,
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Long = PERIOD_SECONDS
    ): String {
        val counter = timeMillis / 1000 / periodSeconds
        return generateHotp(secret, counter)
    }

    fun secondsRemaining(
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Long = PERIOD_SECONDS
    ): Int {
        val elapsed = (timeMillis / 1000) % periodSeconds
        return (periodSeconds - elapsed).toInt()
    }

    fun verifyCode(
        secret: ByteArray,
        code: String,
        timeMillis: Long = System.currentTimeMillis(),
        periodSeconds: Long = PERIOD_SECONDS,
        windowSize: Int = 1
    ): Boolean {
        val counter = timeMillis / 1000 / periodSeconds
        for (i in -windowSize..windowSize) {
            if (generateHotp(secret, counter + i) == code) return true
        }
        return false
    }

    private fun generateHotp(secret: ByteArray, counter: Long): String {
        // RFC 4226: counter as big-endian 8-byte array
        val counterBytes = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }

        // HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        // Dynamic truncation (RFC 4226 section 5.4)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % Math.pow(10.0, DIGITS.toDouble()).toInt()
        return otp.toString().padStart(DIGITS, '0')
    }
}
