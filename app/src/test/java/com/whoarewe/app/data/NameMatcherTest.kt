package com.whoarewe.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the collision-detection helper used by
 * `WhoAreWeViewModel.onQrScanned` (cwage/whoarewe#33). The invariants being
 * pinned are:
 *
 *  - Case differences do not defeat collision detection — otherwise an
 *    attacker could sneak past the warning by scanning "alice" when the
 *    victim already has "Alice".
 *  - Lowercasing is [Locale.ROOT]-stable, not sensitive to the user's
 *    device locale — otherwise the check would have a different outcome
 *    on a Turkish-locale device (I / ı) than on an en-US device.
 *  - NFC normalization runs — otherwise a keyboard that types "é" as
 *    `e + combining acute` would bypass a stored "é" written as the
 *    precomposed U+00E9.
 *  - Plain un-equal names do not match (negative control).
 */
class NameMatcherTest {

    @Test
    fun `case differences collapse to a match`() {
        assertTrue(NameMatcher.matches("Alice", "alice"))
        assertTrue(NameMatcher.matches("ALICE", "alice"))
        assertTrue(NameMatcher.matches("aLiCe", "AlIcE"))
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        assertTrue(NameMatcher.matches("  Alice  ", "alice"))
        assertTrue(NameMatcher.matches("Alice", "\tAlice\n"))
    }

    @Test
    fun `NFC normalization collapses combining accents`() {
        // "Aléx" via combining acute accent (U+0065 + U+0301)
        val decomposed = "Al\u0065\u0301x"
        // "Aléx" via precomposed é (U+00E9)
        val precomposed = "Al\u00E9x"
        assertTrue(NameMatcher.matches(decomposed, precomposed))
        // And case-insensitively
        assertTrue(NameMatcher.matches(decomposed.uppercase(), precomposed))
    }

    @Test
    fun `lowercasing is locale-ROOT stable`() {
        // "I".lowercase(Locale("tr")) == "ı" (dotless i), but we must use
        // Locale.ROOT everywhere so the collision check yields the same
        // answer on every device regardless of the user's system locale.
        // This test pins that — if someone later changes NameMatcher to use
        // the default locale it will fire on any JVM with a Turkish default
        // locale, and the fact that the normalized form contains "i" rather
        // than "ı" asserts the locale-insensitive behavior.
        assertEquals("i", NameMatcher.normalize("I"))
        assertTrue(NameMatcher.matches("I", "i"))
    }

    @Test
    fun `distinct names do not match`() {
        assertFalse(NameMatcher.matches("Alice", "Bob"))
        assertFalse(NameMatcher.matches("Alice", "Alison"))
        // Substring relationship is not a match
        assertFalse(NameMatcher.matches("Ali", "Alice"))
    }

    @Test
    fun `empty strings match empty strings`() {
        // The real pipeline never feeds empty into NameMatcher — the
        // sanitizer rejects empty names upstream — but the helper should
        // still be well-defined on the edge case rather than throwing.
        assertTrue(NameMatcher.matches("", ""))
        assertTrue(NameMatcher.matches("   ", ""))
        assertFalse(NameMatcher.matches("", "a"))
    }

    @Test
    fun `non-ASCII letters are preserved and match`() {
        assertTrue(NameMatcher.matches("Åsa Bäck", "åsa bäck"))
        assertTrue(NameMatcher.matches("ÅSA BÄCK", "åsa bäck"))
    }
}
