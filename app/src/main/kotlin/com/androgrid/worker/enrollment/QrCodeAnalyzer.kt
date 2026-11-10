package com.androgrid.worker.enrollment

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX ImageAnalysis analyzer detecting enrollment QR codes from the live camera stream.
 * Employs unbundled Google ML Kit Barcode Scanning with ZXing planar fallback for AOSP/custom ROMs.
 *
 * Traces to: [REQ-OP-001], [NFR-SEC-001]
 *
 * @param onQrCodeDetected Callback triggered upon first successful QR code recognition.
 */
class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val isDispatched = AtomicBoolean(false)
    private val scanner = BarcodeScanning.getClient()

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isDispatched.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val qrCode = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    val rawValue = qrCode?.rawValue
                    if (rawValue != null) {
                        if (isDispatched.compareAndSet(false, true)) {
                            onQrCodeDetected(rawValue)
                        }
                    } else {
                        // Fallback to ZXing if ML Kit found no barcode
                        val fallback = QrCodeDecoder.decodeImageProxy(imageProxy)
                        if (fallback != null && isDispatched.compareAndSet(false, true)) {
                            onQrCodeDetected(fallback)
                        }
                    }
                }
                .addOnFailureListener {
                    // Fallback to ZXing if ML Kit throws an exception
                    val fallback = QrCodeDecoder.decodeImageProxy(imageProxy)
                    if (fallback != null && isDispatched.compareAndSet(false, true)) {
                        onQrCodeDetected(fallback)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            // Direct ZXing analysis when Image is null
            val fallback = QrCodeDecoder.decodeImageProxy(imageProxy)
            if (fallback != null && isDispatched.compareAndSet(false, true)) {
                onQrCodeDetected(fallback)
            }
            imageProxy.close()
        }
    }
}
