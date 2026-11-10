package com.androgrid.worker.enrollment

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * High-performance QR code decoding utility supporting both in-memory Bitmaps and CameraX ImageProxy frames.
 *
 * Traces to: [REQ-OP-001], [NFR-SEC-001]
 */
object QrCodeDecoder {

    /**
     * Decodes a QR code directly from an Android [Bitmap].
     *
     * @param bitmap Bitmap image containing potential QR code.
     * @return Decoded text content or null if no valid QR code was detected.
     */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        return try {
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a QR code from a CameraX [ImageProxy] frame using YUV planar luminance.
     *
     * @param imageProxy Camera frame image proxy.
     * @return Decoded text content or null if decoding fails.
     */
    fun decodeImageProxy(imageProxy: ImageProxy): String? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        val source = PlanarYUVLuminanceSource(
            bytes,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        return try {
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (_: Exception) {
            null
        }
    }
}
