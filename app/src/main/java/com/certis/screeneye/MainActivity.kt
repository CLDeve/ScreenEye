package com.certis.screeneye

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var warningText: TextView
    private lateinit var statsText: TextView
    private lateinit var calibrationOverlay: ConstraintLayout
    private lateinit var calibrationBody: TextView
    private lateinit var calibrationCountdown: TextView
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var alertOverlay: View
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var faceDetector: FaceDetector
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int? = null

    private var lastLookingTimeMs = 0L
    private var lastAlertTimeMs = 0L
    private var alertAnimator: ObjectAnimator? = null
    private var shakeAnimator: ObjectAnimator? = null
    private var isAlertActive = false
    private var isAlertStrong = false

    private var isCalibrating = true
    private var calibrationStartMs = 0L
    private var calibrationSamples = 0
    private var calibrationYawSum = 0f
    private var calibrationPitchSum = 0f
    private var baselineYaw = 0f
    private var baselinePitch = 0f
    private val calibrationDurationMs = 10_000L
    private val minCalibrationSamples = 20

    private var sessionStartMs = 0L
    private var lastStateChangeMs = 0L
    private var isLookingState: Boolean? = null
    private var totalLookingMs = 0L
    private var totalAwayMs = 0L
    private var lookAwayCount = 0
    private var currentFocusStartMs = 0L
    private var longestFocusMs = 0L

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        warningText = findViewById(R.id.warningText)
        statsText = findViewById(R.id.statsText)
        calibrationOverlay = findViewById(R.id.calibrationOverlay)
        calibrationBody = findViewById(R.id.calibrationBody)
        calibrationCountdown = findViewById(R.id.calibrationCountdown)
        alertOverlay = findViewById(R.id.alertOverlay)
        alertOverlay.alpha = 0f
        warningText.visibility = View.GONE
        statsText.visibility = View.GONE
        calibrationOverlay.visibility = View.VISIBLE
        cameraExecutor = Executors.newSingleThreadExecutor()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        setBeepVolumePercent(75)
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        sessionStartMs = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previewView.setRenderEffect(
                RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP)
            )
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(options)

        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.close()
        toneGenerator.release()
        restoreVolume()
        stopAlert()
    }

    override fun onResume() {
        super.onResume()
        setBeepVolumePercent(75)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                handleFaces(faces)
            }
            .addOnFailureListener {
                // Ignore failed frames to keep analysis responsive.
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleFaces(faces: List<Face>) {
        val now = System.currentTimeMillis()
        if (lastLookingTimeMs == 0L) {
            lastLookingTimeMs = now
        }

        if (isCalibrating) {
            handleCalibration(faces, now)
            return
        }

        val isLooking = faces.isNotEmpty() && faces.any { face ->
            val yaw = face.headEulerAngleY
            val pitch = face.headEulerAngleX
            abs(yaw - baselineYaw) <= 20f && abs(pitch - baselinePitch) <= 20f
        }

        runOnUiThread {
            if (isLooking) {
                stopAlert()
                statusText.text = "Looking at screen"
            } else {
                statusText.text = "Look away detected"
            }
        }

        updateStats(now, isLooking)

        if (isLooking) {
            lastLookingTimeMs = now
            return
        }

        val awayForMs = now - lastLookingTimeMs
        if (awayForMs >= 2000L) {
            val strong = awayForMs >= 5000L
            startAlert(strong)
            val cooldownMs = if (strong) 1500L else 2500L
            val toneDuration = if (strong) 350 else 200
            if (now - lastAlertTimeMs >= cooldownMs) {
                setBeepVolumePercent(75)
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, toneDuration)
                lastAlertTimeMs = now
            }
        } else {
            stopAlert()
        }
    }

    private fun startAlert(strong: Boolean) {
        if (isAlertActive && isAlertStrong == strong) {
            return
        }
        isAlertActive = true
        isAlertStrong = strong
        runOnUiThread {
            warningText.text = if (strong) "LOOK AT SCREEN" else "Eyes on screen"
            warningText.visibility = View.VISIBLE
        }

        alertOverlay.setBackgroundColor(Color.RED)
        val maxAlpha = if (strong) 1.0f else 0.7f
        val durationMs = if (strong) 350L else 700L
        alertAnimator?.cancel()
        alertOverlay.alpha = 0f
        alertAnimator = ObjectAnimator.ofFloat(alertOverlay, View.ALPHA, 0f, maxAlpha).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        if (strong) {
            startShake()
        } else {
            stopShake()
        }
    }

    private fun stopAlert() {
        if (!isAlertActive) {
            return
        }
        isAlertActive = false
        isAlertStrong = false
        alertAnimator?.cancel()
        alertOverlay.alpha = 0f
        warningText.visibility = View.GONE
        stopShake()
    }

    private fun startShake() {
        if (shakeAnimator?.isRunning == true) {
            return
        }
        shakeAnimator = ObjectAnimator.ofFloat(rootLayout, View.TRANSLATION_X, -10f, 10f).apply {
            duration = 60L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopShake() {
        shakeAnimator?.cancel()
        rootLayout.translationX = 0f
    }

    private fun handleCalibration(faces: List<Face>, now: Long) {
        if (faces.isEmpty()) {
            runOnUiThread {
                calibrationOverlay.visibility = View.VISIBLE
                calibrationBody.text = "Center your face on screen"
                calibrationCountdown.text = "Waiting for face..."
                statusText.text = "Calibrating"
                warningText.visibility = View.GONE
                statsText.visibility = View.GONE
            }
            stopAlert()
            return
        }

        if (calibrationStartMs == 0L) {
            calibrationStartMs = now
        }

        val face = faces.first()
        calibrationYawSum += face.headEulerAngleY
        calibrationPitchSum += face.headEulerAngleX
        calibrationSamples += 1

        val elapsed = now - calibrationStartMs
        val remaining = ((calibrationDurationMs - elapsed).coerceAtLeast(0L) / 1000L) + 1
        runOnUiThread {
            calibrationOverlay.visibility = View.VISIBLE
            calibrationBody.text = "Look straight at the screen"
            calibrationCountdown.text = "Calibrating... $remaining s"
            statusText.text = "Calibrating"
            warningText.visibility = View.GONE
            statsText.visibility = View.GONE
        }

        if (elapsed >= calibrationDurationMs && calibrationSamples >= minCalibrationSamples) {
            baselineYaw = calibrationYawSum / calibrationSamples
            baselinePitch = calibrationPitchSum / calibrationSamples
            isCalibrating = false
            lastLookingTimeMs = now
            lastStateChangeMs = now
            isLookingState = true
            currentFocusStartMs = now
            runOnUiThread {
                calibrationOverlay.visibility = View.GONE
                statusText.text = "Calibration complete"
                statsText.visibility = View.VISIBLE
            }
        }
    }

    private fun updateStats(now: Long, isLooking: Boolean) {
        if (isLookingState == null) {
            isLookingState = isLooking
            lastStateChangeMs = now
            if (isLooking) {
                currentFocusStartMs = now
            }
            return
        }

        if (isLookingState != isLooking) {
            val delta = now - lastStateChangeMs
            if (isLookingState == true) {
                totalLookingMs += delta
                val focusSpan = now - currentFocusStartMs
                if (focusSpan > longestFocusMs) {
                    longestFocusMs = focusSpan
                }
                lookAwayCount += 1
            } else {
                totalAwayMs += delta
                currentFocusStartMs = now
            }
            lastStateChangeMs = now
            isLookingState = isLooking
        }

        val currentLookingMs = if (isLookingState == true) now - lastStateChangeMs else 0L
        val currentAwayMs = if (isLookingState == false) now - lastStateChangeMs else 0L
        val lookingMs = totalLookingMs + currentLookingMs
        val awayMs = totalAwayMs + currentAwayMs
        val focusPercent = ((lookingMs * 100) / (lookingMs + awayMs).coerceAtLeast(1L))

        runOnUiThread {
            statsText.text = "Focus $focusPercent%\nLook-aways $lookAwayCount\nLongest ${formatDuration(longestFocusMs)}"
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun setBeepVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) {
            return
        }
        if (originalVolume == null) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val clamped = percent.coerceIn(0, 100)
        val target = (max * clamped) / 100
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            target,
            AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
        )
    }

    private fun restoreVolume() {
        val volume = originalVolume ?: return
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            volume,
            AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
        )
        originalVolume = null
    }
}
