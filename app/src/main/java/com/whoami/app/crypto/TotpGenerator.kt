package com.whoami.app.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpGenerator {
    const val PERIOD_SECONDS = 60L
    private const val DIGITS = 6

    fun generateCode(secret: ByteArray, timeMillis: Long = System.currentTimeMillis()): String {
        val counter = timeMillis / 1000 / PERIOD_SECONDS
        return generateHotp(secret, counter)
    }

    fun secondsRemaining(timeMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = (timeMillis / 1000) % PERIOD_SECONDS
        return (PERIOD_SECONDS - elapsed).toInt()
    }

    fun verifyCode(
        secret: ByteArray,
        code: String,
        timeMillis: Long = System.currentTimeMillis(),
        windowSize: Int = 1
    ): Boolean {
        val counter = timeMillis / 1000 / PERIOD_SECONDS
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
