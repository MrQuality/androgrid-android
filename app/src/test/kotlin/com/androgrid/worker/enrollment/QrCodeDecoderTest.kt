package com.androgrid.worker.enrollment

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hermetic unit test verifying QR code decoding from bitmap images.
 *
 * Traces to: [REQ-OP-001], [NFR-SEC-001]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QrCodeDecoderTest {

    @Test
    fun decode_validQrBitmap_returnsOriginalContent() {
        val originalText = """{"cluster_endpoint":"192.168.1.100:50051","ca_fingerprint":"a1b2c3d4e5f60718293a4b5c6d7e8f901234567890abcdef1234567890abcdef","pairing_token":"tok-123"}"""
        val qrBitmap = generateQrBitmap(originalText, 256, 256)

        val decoded = QrCodeDecoder.decodeBitmap(qrBitmap)

        assertThat(decoded).isEqualTo(originalText)
    }

    @Test
    fun decode_blankBitmap_returnsNull() {
        val blankBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        val decoded = QrCodeDecoder.decodeBitmap(blankBitmap)

        assertThat(decoded).isNull()
    }

    private fun generateQrBitmap(content: String, width: Int, height: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
