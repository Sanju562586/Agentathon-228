package com.guardian.mesh.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.guardian.mesh.R
import com.guardian.mesh.autofill.TrustedDevice
import com.guardian.mesh.autofill.TrustedDeviceStore
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraControl: CameraControl? = null
    private var isTorchOn = false

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Pausing Service Camera...")
        com.guardian.mesh.GuardianService.pauseCamera()
        
        // Give Service time to release camera
        previewView.postDelayed({
            if (allPermissionsGranted()) {
                Log.d(TAG, "onResume: Starting App Camera after delay...")
                startCamera()
            }
        }, 500)
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera: Requesting CameraProvider...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            Log.d(TAG, "startCamera: CameraProvider Ready.")
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { qrCode ->
                            handleQrCode(qrCode)
                        })
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                Log.d(TAG, "startCamera: Unbinding all previous use cases...")
                cameraProvider.unbindAll()
                
                Log.d(TAG, "startCamera: Binding new use cases...")
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                
                cameraControl = camera.cameraControl
                setupZoom(camera.cameraInfo)
                Log.d(TAG, "startCamera: Camera Bound Successfully! ✅")
                
            } catch (exc: Exception) {
                Log.e(TAG, "camera start failed", exc)
                runOnUiThread {
                    Toast.makeText(this@ScannerActivity, "Camera Error: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onPause() {
        super.onPause()
        com.guardian.mesh.GuardianService.resumeCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        com.guardian.mesh.GuardianService.pauseCamera()

        previewView = findViewById(R.id.viewFinder)

        // UI Buttons
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener {
            toggleFlash()
        }

        findViewById<android.widget.Button>(R.id.btnReset).setOnClickListener {
            TrustedDeviceStore.clearDevices(this)
            Toast.makeText(this, "Pairings Reset", Toast.LENGTH_SHORT).show()
        }

        // Note: Camera start is now handled in onResume with delay
        if (!allPermissionsGranted()) {
             ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }
    private fun setupZoom(cameraInfo: CameraInfo) {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            return@setOnTouchListener true
        }
    }

    private fun toggleFlash() {
        val control = cameraControl ?: return
        isTorchOn = !isTorchOn
        control.enableTorch(isTorchOn)
        
        val btnFlash = findViewById<ImageButton>(R.id.btnFlash)
        val tint = if(isTorchOn) "#00E676" else "#FFFFFF"
        btnFlash.setColorFilter(android.graphics.Color.parseColor(tint))
    }

    private var isProcessing = false

    private fun handleQrCode(rawValue: String) {
        if (isProcessing) return
        
        if (rawValue.startsWith("GUARDIAN_BIND:")) {
            isProcessing = true
            val publicKey = rawValue.removePrefix("GUARDIAN_BIND:")
            
            if (publicKey.length < 10) { // Basic sanity check
                 runOnUiThread {
                    Toast.makeText(this, "Invalid QR Format", Toast.LENGTH_SHORT).show()
                    isProcessing = false 
                }
                return
            }

            val shortId = "BROWSER-" + publicKey.hashCode().toString().takeLast(4)
            
            val device = TrustedDevice(
                id = shortId,
                name = "Chrome Extension",
                publicKey = publicKey
            )
            
            TrustedDeviceStore.addDevice(this, device)
            
            runOnUiThread {
                Toast.makeText(this, "Paired: $shortId", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ScannerActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private class BarcodeAnalyzer(private val listener: (String) -> Unit) : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val scanner = BarcodeScanning.getClient()
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            barcode.rawValue?.let {
                                listener(it)
                            }
                        }
                    }
                    .addOnFailureListener {
                        // Task failed with an exception
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
