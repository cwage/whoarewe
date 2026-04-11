package com.whoarewe.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Display-name comparison for the "key change warning" path in
 * `WhoAreWeViewModel.onQrScanned`. Two names should match iff they represent
 * the same *human-readable* label, so a scanned QR that says "alice" can be
 * recognized as a collision with an existing contact named "Alice" without
 * the user needing to think about capitalization or combining accents.
 *
 * The normalization pipeline is:
 *   1. **NFC normalize** — so "é" typed as U+00E9 matches "é" typed as
 *      U+0065 U+0301 (the same reason `QrCodeUtils.sanitizeDisplayName`
 *      runs NFC). Both the stored contact and the incoming QR were already
 *      NFC-normalized at write time (by `sanitizeDisplayName`), but doing it
 *      again here is idempotent, free, and defensive against any row that
 *      was written before #26 landed.
 *   2. **Trim** — matches the sanitizer's trim step; again, defensive.
 *   3. **Lowercase with [Locale.ROOT]** — deliberately *not* the user's
 *      default locale. The default-locale `.lowercase()` would give Turkish
 *      users different results ("I".lowercase(Locale("tr")) == "ı", not "i"),
 *      which would make the collision check locale-dependent and break
 *      reproducibility across users' devices.
 *
 * Homoglyph / confusables attacks (e.g. "Alice" vs "Aliсe" with a Cyrillic
 * `с`) are **not** caught here — that is a known limitation documented in
 * cwage/whoarewe#33. A Unicode confusables mapping is substantially more
 * surface than the collision warning itself and is tracked separately. The
 * warning's value comes from catching the straightforward "attacker-typed
 * the same display name" case, which is what an impersonation attempt is
 * most likely to look like in practice.
 */
object NameMatcher {
    fun normalize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC)
            .trim()
            .lowercase(Locale.ROOT)

    fun matches(a: String, b: String): Boolean = normalize(a) == normalize(b)
}
