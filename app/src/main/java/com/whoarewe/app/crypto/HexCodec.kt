package com.whoarewe.app.crypto

/**
 * Centralized byte ↔ hex conversion. The single source of truth for every
 * place in the app that stores or compares cryptographic material as a hex
 * string (Identity public keys, TrustedContact public keys, TrustedContact
 * TOTP secrets, fingerprints, debug dumps).
 *
 * Why this exists: the obvious-looking pattern `bytes.joinToString("") { "%02x".format(it) }`
 * is *wrong* for any byte ≥ 0x80, because Kotlin `Byte` is signed and
 * `String.format("%02x", b)` widens it to `Int` with sign extension. The byte
 * `0x80.toByte()` (= -128) becomes the int `-128` (= `0xffffff80`), which
 * `%02x` formats as `"ffffff80"` rather than `"80"`. Half the bytes in any
 * random key trip this. The bug went unnoticed for a long time because every
 * comparison in the app applied the same buggy encoder on both sides — the
 * symmetric corruption cancelled out functionally, but the stored hex strings
 * didn't actually decode back to the original bytes. cwage/whoarewe#15.
 *
 * **Do not reimplement this inline anywhere.** If you need byte→hex or
 * hex→byte conversion, call `HexCodec.bytesToHex` / `HexCodec.hexToBytes`.
 */
object HexCodec {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /**
     * Encode bytes as a lowercase hex string. Each byte produces exactly two
     * characters, so a 32-byte array becomes a 64-character string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }

    /**
     * Decode a lowercase or uppercase hex string back to bytes. Throws
     * `IllegalArgumentException` for odd length or non-hex characters — the
     * old `chunked(2).map { it.toInt(16).toByte() }` pattern silently
     * accepted malformed input and produced corrupt byte arrays.
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length, got ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexCharValue(hex[i * 2])
            val lo = hexCharValue(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexCharValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex character: '$c'")
    }
}
