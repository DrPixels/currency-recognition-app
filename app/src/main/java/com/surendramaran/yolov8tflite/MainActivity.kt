package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech  // Import for Text-to-Speech
import android.util.Log  // Import for Log class
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale  // Import for setting language
import android.os.Handler
import android.os.Looper
import androidx.camera.core.TorchState

class MainActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech  // TextToSpeech instance

    private var isDetectionPaused = false  // Flag to control detection
    private val delayHandler = Handler(Looper.getMainLooper())  // Handler for delay
    private val detectionDelay = 2000L  // Delay duration in milliseconds (e.g., 2 seconds)

    private var isFlashOn = false  // Flag to track flash state

    private var accumulatedTotal = 0
    private var isCounterOn = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Flash Button Click Listener
        binding.leftButton.setOnClickListener {
            toggleFlash()  // Toggle flash on/off
        }

        binding.rightButton.setOnClickListener {
            isCounterOn = !isCounterOn
            val status = if (isCounterOn) "Counter is On" else "Counter is Off"
            tts.speak(status, TextToSpeech.QUEUE_FLUSH, null, null)

            if (!isCounterOn) {
                accumulatedTotal = 0  // Reset the counter when turned off
            }
        }
        // Speak the initialization message after TTS is ready
        // This will be spoken when the app is ready to detect objects
    }

    // TextToSpeech initialization callback
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language to default (can be changed as needed)
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            }
            tts.speak("PesoBuddy is ready to detect. Please place the item in front of the camera.", TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.e(TAG, "TextToSpeech initialization failed")
        }
    }

    private fun toggleFlash() {
        if (camera != null) {
            val cameraControl = camera!!.cameraControl

            // Use flashState to toggle flash
            val flashState = if (isFlashOn) TorchState.OFF else TorchState.ON

            // Turn the flash on or off using the state
            cameraControl.enableTorch(isFlashOn)

            // Toggle the isFlashOn flag
            isFlashOn = !isFlashOn

            // Ensure TTS is initialized before speaking
            if (::tts.isInitialized) {
                val message = if (isFlashOn) "Flash is Off" else "Flash is On"

                // Ensure TTS speaks on the main thread
                runOnUiThread {
                    tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            } else {
                Log.e(TAG, "TTS is not initialized properly.")
            }
        } else {
            Log.e(TAG, "Camera is not initialized, cannot toggle flash.")
        }
    }



    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        // Release Text-to-Speech resources
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (isDetectionPaused) return

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            if (boundingBoxes.isNotEmpty()) {
                // Assume confidence is a property of BoundingBox, adjust according to your model output
                val detectedBox = boundingBoxes.first()
                val confidence = detectedBox.cnf  // Confidence score for detection (should be between 0 and 1)

                if (confidence >= 0.90) {  // Only process detections with 80% confidence or higher
                    val label = detectedBox.clsName  // Get the label of the detected item
                    val speechLabel = "Detected " + label + " pesos"
                    val detectedValue = extractValueFromLabel(label)

                    if (isCounterOn) {
                        accumulatedTotal += detectedValue
                        val totalMessage = "<speak>$speechLabel<break time=\"1000ms\"/>Total: $accumulatedTotal pesos</speak>"
                        tts.speak(totalMessage, TextToSpeech.QUEUE_FLUSH, null, null)
                    } else {
                        tts.speak(speechLabel, TextToSpeech.QUEUE_ADD, null, null)
                    }

                    // Pause detection to prevent too frequent speaking
                    isDetectionPaused = true
                    delayHandler.postDelayed({ isDetectionPaused = false }, detectionDelay)
                } else {
                    // If confidence is less than 80%, don't speak, but you could log or show a message
                    Log.d(TAG, "Detection confidence too low: $confidence. Skipping speech.")
                }
            }
        }
    }


    private fun extractValueFromLabel(label: String): Int {
        return label.split(" ")[0].toIntOrNull() ?: 0
    }


    // Function to speak detected label
    private fun speakDetectedLabel(label: String) {
        tts.speak(label, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}
