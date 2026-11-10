package com.androgrid.worker.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.androgrid.worker.enrollment.QrCodeAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera QR code scanning activity utilizing CameraX and unbundled barcode recognition.
 * Provides a high-contrast viewfinder overlay and finishes upon valid QR code capture.
 *
 * Traces to: [REQ-OP-001], [NFR-SEC-001]
 */
class QrScannerActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required for QR enrollment", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupViews()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun setupViews() {
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        // Overlay with cutout viewfinder
        val overlayView = ViewfinderOverlayView(this)
        rootLayout.addView(overlayView)

        // Instructional Label
        val instructionLabel = TextView(this).apply {
            text = "Center the AndroGrid pairing QR code inside frame"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(32, 24, 32, 24)
        }
        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(32, 64, 32, 0)
        }
        rootLayout.addView(instructionLabel, labelParams)

        // Cancel Button
        val cancelButton = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#424242"))
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        val cancelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, 64)
        }
        rootLayout.addView(cancelButton, cancelParams)

        setContentView(rootLayout)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { scannedContent ->
                            onQrCodeCaptured(scannedContent)
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onQrCodeCaptured(scannedContent: String) {
        runOnUiThread {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SCANNED_PAYLOAD, scannedContent)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    /**
     * Custom view rendering semi-transparent darkened scrim with a transparent square cutout.
     */
    private class ViewfinderOverlayView(context: android.content.Context) : View(context) {
        private val scrimPaint = Paint().apply {
            color = Color.parseColor("#80000000")
        }
        private val transparentPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#00E676") // Bright green
            strokeWidth = 6f
            style = Paint.Style.STROKE
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

            val boxSize = (width.coerceAtMost(height) * 0.7f)
            val left = (width - boxSize) / 2f
            val top = (height - boxSize) / 2f
            val rect = RectF(left, top, left + boxSize, top + boxSize)

            canvas.drawRoundRect(rect, 16f, 16f, transparentPaint)
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        }
    }

    companion object {
        const val EXTRA_SCANNED_PAYLOAD = "com.androgrid.worker.extra.SCANNED_PAYLOAD"
    }
}
