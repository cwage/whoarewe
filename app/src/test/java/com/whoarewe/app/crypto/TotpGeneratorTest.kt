package com.whoarewe.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TotpGeneratorTest {

    // RFC 6238 Appendix B test vectors (SHA1, 8-digit)
    // We use 6-digit codes; the rotation period is configured in TotpGenerator
    // and referenced via PERIOD_SECONDS so this file has no hardcoded step size.

    // Shared secret from RFC 4226 test vectors
    private val rfcSecret = "12345678901234567890".toByteArray()

    private val periodMillis = TotpGenerator.PERIOD_SECONDS * 1000

    @Test
    fun `generates 6-digit codes`() {
        val code = TotpGenerator.generateCode(rfcSecret, 0L)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `pads codes shorter than 6 digits with leading zeros`() {
        // Generate many codes and verify all are 6 digits
        for (t in 0L..100L) {
            val code = TotpGenerator.generateCode(rfcSecret, t * periodMillis)
            assertEquals("Code at t=$t should be 6 digits", 6, code.length)
        }
    }

    @Test
    fun `same secret and time produces same code`() {
        val time = 1000000000L * 1000 // 1 billion seconds in millis
        val code1 = TotpGenerator.generateCode(rfcSecret, time)
        val code2 = TotpGenerator.generateCode(rfcSecret, time)
        assertEquals(code1, code2)
    }

    @Test
    fun `same secret different time period produces different code`() {
        // Walk consecutive windows; at least one pair must differ.
        var allSame = true
        for (i in 0L..9L) {
            val a = TotpGenerator.generateCode(rfcSecret, i * periodMillis)
            val b = TotpGenerator.generateCode(rfcSecret, (i + 1) * periodMillis)
            if (a != b) allSame = false
        }
        assertFalse("All consecutive codes were identical — something is wrong", allSame)
    }

    @Test
    fun `same time window produces same code regardless of position within window`() {
        // Two times within the same window: window-start and window-start + half-period.
        val windowStart = periodMillis
        val midWindow = windowStart + (periodMillis / 2)
        val code1 = TotpGenerator.generateCode(rfcSecret, windowStart)
        val code2 = TotpGenerator.generateCode(rfcSecret, midWindow)
        assertEquals(code1, code2)
    }

    @Test
    fun `different secrets produce different codes`() {
        val secret1 = "secret_one_padded_ok".toByteArray()
        val secret2 = "secret_two_padded_ok".toByteArray()
        val time = 1000000000L * 1000
        val code1 = TotpGenerator.generateCode(secret1, time)
        val code2 = TotpGenerator.generateCode(secret2, time)
        // Not guaranteed but overwhelmingly likely
        assertTrue(
            "Different secrets should produce different codes (1 in a million chance of false failure)",
            code1 != code2
        )
    }

    @Test
    fun `secondsRemaining returns value between 1 and period`() {
        // Walk two full periods at one-second resolution.
        val limit = (TotpGenerator.PERIOD_SECONDS * 2).toInt()
        for (t in 0..limit) {
            val remaining = TotpGenerator.secondsRemaining(t.toLong() * 1000)
            assertTrue(
                "remaining=$remaining at t=$t",
                remaining in 1..TotpGenerator.PERIOD_SECONDS.toInt()
            )
        }
    }

    @Test
    fun `verifyCode accepts current window`() {
        val time = 1000000000L * 1000
        val code = TotpGenerator.generateCode(rfcSecret, time)
        assertTrue(TotpGenerator.verifyCode(rfcSecret, code, time))
    }

    @Test
    fun `verifyCode accepts adjacent windows with windowSize 1`() {
        val time = 1000000000L * 1000
        // Code from previous window
        val prevCode = TotpGenerator.generateCode(rfcSecret, time - periodMillis)
        assertTrue(TotpGenerator.verifyCode(rfcSecret, prevCode, time, windowSize = 1))

        // Code from next window
        val nextCode = TotpGenerator.generateCode(rfcSecret, time + periodMillis)
        assertTrue(TotpGenerator.verifyCode(rfcSecret, nextCode, time, windowSize = 1))
    }

    @Test
    fun `verifyCode rejects code from distant window`() {
        val time = 1000000000L * 1000
        // Code from 3 windows away
        val farCode = TotpGenerator.generateCode(rfcSecret, time + (3 * periodMillis))
        assertFalse(TotpGenerator.verifyCode(rfcSecret, farCode, time, windowSize = 1))
    }

    @Test
    fun `verifyCode rejects wrong code`() {
        val time = 1000000000L * 1000
        assertFalse(TotpGenerator.verifyCode(rfcSecret, "000000", time))
        assertFalse(TotpGenerator.verifyCode(rfcSecret, "999999", time))
    }

    // RFC 4226 Appendix D test vectors for HOTP (which TOTP builds on)
    // Counter 0-9 with secret "12345678901234567890"
    // These verify our HMAC-SHA1 + dynamic truncation is correct.
    // RFC vectors are for 6-digit codes.
    @Test
    fun `matches RFC 4226 HOTP test vectors`() {
        // RFC 4226 test vectors: counter -> expected 6-digit code
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489"
        )
        for ((counter, expectedCode) in expected.withIndex()) {
            // TOTP with counter = time / period, so time = counter * period * 1000
            val timeMillis = counter.toLong() * TotpGenerator.PERIOD_SECONDS * 1000
            val code = TotpGenerator.generateCode(rfcSecret, timeMillis)
            assertEquals(
                "HOTP counter=$counter",
                expectedCode,
                code
            )
        }
    }
}
