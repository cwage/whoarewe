package com.whoarewe.app.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.Normalizer

object QrCodeUtils {
    private const val SCHEME = "whoarewe"
    private const val VERSION = "v1"

    /**
     * Maximum accepted display-name length in Java code units, measured
     * *after* NFC normalization and trimming. 64 is a comfortable ceiling
     * for any human name and caps the row-overflow / DB-bloat surface
     * described in cwage/whoarewe#26 well below ZXing's Version 40 QR
     * capacity (~2900 alphanumeric chars).
     */
    const val MAX_DISPLAY_NAME_LENGTH = 64

    data class QrPayload(
        val displayName: String,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QrPayload) return false
            return displayName == other.displayName && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            return 31 * displayName.hashCode() + publicKey.contentHashCode()
        }
    }

    fun encode(displayName: String, publicKey: ByteArray): String {
        val keyBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
        return "$SCHEME:$VERSION:$keyBase64:$displayName"
    }

    fun decode(data: String): QrPayload? {
        val parts = data.split(":", limit = 4)
        if (parts.size != 4) return null
        if (parts[0] != SCHEME) return null
        if (parts[1] != VERSION) return null

        val publicKey = try {
            java.util.Base64.getUrlDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (publicKey.size != 32) return null // Ed25519 public key is 32 bytes

        val displayName = sanitizeDisplayName(parts[3]) ?: return null

        return QrPayload(displayName = displayName, publicKey = publicKey)
    }

    /**
     * Normalize and validate a display name pulled from a scanned QR. Returns
     * the cleaned name, or null if the input should be rejected. See
     * cwage/whoarewe#26 for the full exposure list; the short version:
     *
     *  - **NFC normalize first**, so two devices that type the same name with
     *    different combining-character sequences store the same bytes.
     *  - **Trim** leading/trailing whitespace, matching the local-identity
     *    setup flow (`WhoAreWeViewModel.requestGenerateIdentity`).
     *  - **Reject empty** (after trim) — an empty name is not a name.
     *  - **Reject > [MAX_DISPLAY_NAME_LENGTH] code units** to bound row
     *    overflow and DB bloat from a maximal-capacity QR.
     *  - **Reject Unicode Cc (control) or Cf (format)** codepoints. This kills
     *    embedded newlines / tabs (the "two rows rendered from one contact"
     *    impersonation trick), the `\u202E` RTL-override homoglyph vector,
     *    and zero-width joiners — all print-unsafe for a trust-anchor label.
     *
     * Iterates by codepoint so astral-plane characters are handled correctly
     * (a single emoji is one codepoint but two Java code units; Cc/Cf live
     * entirely in the BMP today but codepoint iteration is the correct shape
     * regardless).
     */
    private fun sanitizeDisplayName(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) return null
        if (normalized.length > MAX_DISPLAY_NAME_LENGTH) return null

        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val type = Character.getType(cp)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) {
                return null
            }
            i += Character.charCount(cp)
        }
        return normalized
    }

    fun generateBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
