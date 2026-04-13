package com.whoarewe.app.crypto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

object QrDecoder {

    private val DECODE_HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    // ZXing's static image decoder is sensitive to the ratio of QR module
    // size to image dimensions — at certain resolutions the module grid
    // aliases against the pixel grid and the binarizer misreads enough
    // modules to exceed error-correction capacity. Retrying at a few
    // downscaled resolutions cheaply dodges the aliasing without making
    // any assumptions about image content.
    private val RETRY_SCALES = floatArrayOf(0.75f, 0.5f, 0.3f)

    fun decodeFromUri(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        return decodeFromBitmap(bitmap)
    }

    private fun decodeFromBitmap(bitmap: Bitmap): String? {
        // Try at original resolution first.
        tryDecode(bitmap)?.let { return it }

        // Retry at downscaled resolutions to work around aliasing.
        for (scale in RETRY_SCALES) {
            val sw = (bitmap.width * scale).toInt()
            val sh = (bitmap.height * scale).toInt()
            if (sw < 100 || sh < 100) continue
            val scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, false)
            try {
                tryDecode(scaled)?.let { return it }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        return null
    }

    private fun tryDecode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        return try {
            MultiFormatReader().decode(binaryBitmap, DECODE_HINTS).text
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }
}
