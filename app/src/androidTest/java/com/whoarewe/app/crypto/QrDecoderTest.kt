package com.whoarewe.app.crypto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    private val testName = "alice"
    private val testKey = ByteArray(32) { it.toByte() }

    private fun makeScreenshotBitmap(
        screenW: Int,
        screenH: Int,
        qrSize: Int = 768,
        bgColor: Int = 0xFFF9F9F9.toInt(),
    ): Bitmap {
        val payload = QrCodeUtils.encode(testName, testKey)
        val qr = QrCodeUtils.generateBitmap(payload, qrSize)
        val screenshot = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screenshot)
        canvas.drawColor(bgColor)
        val left = ((screenW - qrSize) / 2).toFloat()
        val top = ((screenH - qrSize) / 2).toFloat()
        canvas.drawBitmap(qr, left, top, Paint())
        qr.recycle()
        return screenshot
    }

    @Test
    fun decodesQrFromFullScreenScreenshot() {
        // 1080x2424 is the resolution that triggered #51
        val screenshot = makeScreenshotBitmap(1080, 2424)
        val result = QrDecoder.decodeFromBitmap(screenshot)
        screenshot.recycle()
        assertNotNull("Should decode QR from 1080x2424 screenshot", result)
        val decoded = QrCodeUtils.decode(result!!)
        assertNotNull(decoded)
        assertEquals(testName, decoded!!.displayName)
    }

    @Test
    fun decodesQrFromSmallImage() {
        val screenshot = makeScreenshotBitmap(540, 960, qrSize = 400)
        val result = QrDecoder.decodeFromBitmap(screenshot)
        screenshot.recycle()
        assertNotNull("Should decode QR from small image", result)
    }

    @Test
    fun decodesQrFromLargeImage() {
        val screenshot = makeScreenshotBitmap(1440, 3200)
        val result = QrDecoder.decodeFromBitmap(screenshot)
        screenshot.recycle()
        assertNotNull("Should decode QR from 1440x3200 image", result)
    }

    @Test
    fun returnsNullForImageWithNoQr() {
        val blank = Bitmap.createBitmap(1080, 2424, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.WHITE)
        val result = QrDecoder.decodeFromBitmap(blank)
        blank.recycle()
        assertNull("Should return null for image with no QR", result)
    }
}
