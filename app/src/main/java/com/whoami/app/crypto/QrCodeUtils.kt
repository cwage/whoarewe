package com.whoami.app.crypto

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeUtils {
    private const val SCHEME = "whoami"
    private const val VERSION = "v1"

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
        val keyBase64 = Base64.encodeToString(publicKey, Base64.URL_SAFE or Base64.NO_WRAP)
        return "$SCHEME:$VERSION:$keyBase64:$displayName"
    }

    fun decode(data: String): QrPayload? {
        val parts = data.split(":", limit = 4)
        if (parts.size != 4) return null
        if (parts[0] != SCHEME) return null
        if (parts[1] != VERSION) return null

        val publicKey = try {
            Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val displayName = parts[3]
        if (displayName.isBlank()) return null
        if (publicKey.size != 32) return null // Ed25519 public key is 32 bytes

        return QrPayload(displayName = displayName, publicKey = publicKey)
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
