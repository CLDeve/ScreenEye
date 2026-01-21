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
import android.os.Handler
import android.os.Looper
import android.content.Intent
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
import com.certis.screeneye.data.AppDatabase
import com.certis.screeneye.data.LogEvent
import com.certis.screeneye.data.LogEventDao
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
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
    private lateinit var startButton: android.widget.Button
    private lateinit var logsButton: android.widget.Button
    private lateinit var shiftCountdownText: TextView
    private lateinit var shiftAlertOverlay: ConstraintLayout
    private lateinit var shiftAlertBody: TextView
    private lateinit var shiftAcknowledgeButton: android.widget.Button
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var alertOverlay: View
    private lateinit var faceOverlayView: FaceOverlayView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var logExecutor: ExecutorService
    private lateinit var toneGenerator: ToneGenerator
    private lateinit var faceDetector: FaceDetector
    private lateinit var audioManager: AudioManager
    private var originalVolume: Int? = null
    private lateinit var logDao: LogEventDao

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
    private var calibrationEyeRatioSum = 0f
    private var calibrationEyeRatioSamples = 0
    private var baselineEyeYRatio: Float? = null
    private val calibrationDurationMs = 5_000L
    private val minCalibrationSamples = 20
    private val minEyeCalibrationSamples = 10
    private val eyeDownRatioThreshold = 0.04f
    private val eyeClosedProbabilityThreshold = 0.4f
    private var hasStarted = false
    private val shiftDurationMs = 10 * 1000L
    private var shiftStartMs = 0L
    private var shiftAlertTrackingId: Int? = null
    private var lastTrackingId: Int? = null
    private val shiftHandler = Handler(Looper.getMainLooper())
    private val shiftTickRunnable = object : Runnable {
        override fun run() {
            updateShiftCountdown()
            if (hasStarted && shiftStartMs > 0L) {
                shiftHandler.postDelayed(this, 1000L)
            }
        }
    }

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
        logDao = AppDatabase.getInstance(this).logEventDao()
        logExecutor = Executors.newSingleThreadExecutor()

        rootLayout = findViewById(R.id.rootLayout)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        warningText = findViewById(R.id.warningText)
        statsText = findViewById(R.id.statsText)
        calibrationOverlay = findViewById(R.id.calibrationOverlay)
        calibrationBody = findViewById(R.id.calibrationBody)
        calibrationCountdown = findViewById(R.id.calibrationCountdown)
        startButton = findViewById(R.id.startButton)
        logsButton = findViewById(R.id.logsButton)
        shiftCountdownText = findViewById(R.id.shiftCountdownText)
        shiftAlertOverlay = findViewById(R.id.shiftAlertOverlay)
        shiftAlertBody = findViewById(R.id.shiftAlertBody)
        shiftAcknowledgeButton = findViewById(R.id.shiftAcknowledgeButton)
        alertOverlay = findViewById(R.id.alertOverlay)
        faceOverlayView = findViewById(R.id.faceOverlayView)
        alertOverlay.alpha = 0f
        warningText.visibility = View.GONE
        statsText.visibility = View.GONE
        calibrationOverlay.visibility = View.VISIBLE
        calibrationBody.text = "Sit centered and look straight at the screen"
        calibrationCountdown.text = "Tap Start when ready"
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
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(options)

        startButton.setOnClickListener {
            if (!hasStarted) {
                hasStarted = true
                resetCalibration()
                startButton.visibility = View.GONE
                calibrationBody.text = "Keep your face centered and eyes on screen"
                calibrationCountdown.text = "Calibrating..."
                logEvent("CALIBRATION_START", null, null)
            }
        }

        logsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        shiftAcknowledgeButton.setOnClickListener {
            val reference = shiftAlertTrackingId
            val current = lastTrackingId
            if (reference == null || current == null) {
                shiftAlertBody.text = "Face not detected. Please face the camera."
                return@setOnClickListener
            }
            if (reference == current) {
                shiftAlertBody.text = "Same operator detected. Please switch."
                logEvent("SHIFT_SAME_OPERATOR", "tracking_id=$current", null)
                return@setOnClickListener
            }
            shiftAlertOverlay.visibility = View.GONE
            startShiftTimer()
            shiftAlertBody.text = "Please switch operator and acknowledge."
            shiftAlertTrackingId = null
            logEvent("SHIFT_ACK", null, null)
        }

        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        shiftHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        faceDetector.close()
        toneGenerator.release()
        restoreVolume()
        stopAlert()
        logExecutor.shutdown()
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
                handleFaces(
                    faces,
                    imageProxy.width,
                    imageProxy.height,
                    imageProxy.imageInfo.rotationDegrees
                )
            }
            .addOnFailureListener {
                // Ignore failed frames to keep analysis responsive.
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleFaces(
        faces: List<Face>,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ) {
        if (!hasStarted) {
            runOnUiThread {
                faceOverlayView.updateFaces(emptyList(), 0, 0, 0, true)
            }
            return
        }
        val trackingId = faces.firstOrNull()?.trackingId
        if (trackingId != null) {
            lastTrackingId = trackingId
        }
        runOnUiThread {
            faceOverlayView.updateFaces(
                faces,
                imageWidth,
                imageHeight,
                rotationDegrees,
                true
            )
        }
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
            val headAligned = abs(yaw - baselineYaw) <= 20f && abs(pitch - baselinePitch) <= 20f
            if (!headAligned) {
                return@any false
            }
            val baselineEyeRatio = baselineEyeYRatio
            val currentEyeRatio = eyeCenterRatio(face)
            val eyesDown = baselineEyeRatio != null &&
                currentEyeRatio != null &&
                currentEyeRatio - baselineEyeRatio >= eyeDownRatioThreshold
            val eyesClosed = isEyesClosed(face)
            !eyesDown && !eyesClosed
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
        logEvent("ALERT_START", if (strong) "strong" else "soft", null)
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
        logEvent("ALERT_STOP", null, null)
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
                calibrationBody.text = "Move into frame and center your face"
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
        val eyeRatio = eyeCenterRatio(face)
        if (eyeRatio != null) {
            calibrationEyeRatioSum += eyeRatio
            calibrationEyeRatioSamples += 1
        }

        val elapsed = now - calibrationStartMs
        val remaining = ((calibrationDurationMs - elapsed).coerceAtLeast(0L) / 1000L) + 1
        runOnUiThread {
            calibrationOverlay.visibility = View.VISIBLE
            calibrationBody.text = "Hold still and look straight at the screen"
            calibrationCountdown.text = "Calibrating... $remaining s"
            statusText.text = "Calibrating"
            warningText.visibility = View.GONE
            statsText.visibility = View.GONE
        }

        if (elapsed >= calibrationDurationMs && calibrationSamples >= minCalibrationSamples) {
            baselineYaw = calibrationYawSum / calibrationSamples
            baselinePitch = calibrationPitchSum / calibrationSamples
            baselineEyeYRatio = if (calibrationEyeRatioSamples >= minEyeCalibrationSamples) {
                calibrationEyeRatioSum / calibrationEyeRatioSamples
            } else {
                null
            }
            isCalibrating = false
            lastLookingTimeMs = now
            lastStateChangeMs = now
            isLookingState = true
            currentFocusStartMs = now
            startShiftTimer()
            logEvent("CALIBRATION_COMPLETE", null, null)
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
                logEvent("LOOK_AWAY_START", null, null)
            } else {
                totalAwayMs += delta
                currentFocusStartMs = now
                logEvent("LOOK_AWAY_END", null, delta)
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

    private fun startShiftTimer() {
        shiftStartMs = System.currentTimeMillis()
        updateShiftCountdown()
        shiftHandler.removeCallbacks(shiftTickRunnable)
        shiftHandler.post(shiftTickRunnable)
    }

    private fun updateShiftCountdown() {
        if (!hasStarted || shiftStartMs == 0L) {
            shiftCountdownText.text = "Shift 30:00"
            return
        }
        val elapsed = System.currentTimeMillis() - shiftStartMs
        val remaining = (shiftDurationMs - elapsed).coerceAtLeast(0L)
        val totalSeconds = remaining / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        shiftCountdownText.text = String.format("Shift %02d:%02d", minutes, seconds)
        if (remaining == 0L) {
            shiftAlertOverlay.visibility = View.VISIBLE
            shiftAlertBody.text = "Please switch operator and acknowledge."
            shiftAlertTrackingId = lastTrackingId
            shiftHandler.removeCallbacks(shiftTickRunnable)
            playShiftAlertTone()
            logEvent("SHIFT_ALERT", null, null)
        }
    }

    private fun resetCalibration() {
        isCalibrating = true
        calibrationStartMs = 0L
        calibrationSamples = 0
        calibrationYawSum = 0f
        calibrationPitchSum = 0f
        calibrationEyeRatioSum = 0f
        calibrationEyeRatioSamples = 0
        baselineEyeYRatio = null
        lastLookingTimeMs = 0L
        isLookingState = null
    }

    private fun playShiftAlertTone() {
        setBeepVolumePercent(75)
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
    }


    private fun logEvent(type: String, message: String?, durationMs: Long?) {
        val event = LogEvent(
            timestampMs = System.currentTimeMillis(),
            type = type,
            message = message,
            durationMs = durationMs
        )
        logExecutor.execute {
            logDao.insert(event)
        }
    }

    private fun eyeCenterRatio(face: Face): Float? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (leftEye == null || rightEye == null) {
            return null
        }
        val bounds = face.boundingBox
        val height = bounds.height().toFloat()
        if (height <= 0f) {
            return null
        }
        val eyeCenterY = (leftEye.y + rightEye.y) / 2f
        return (eyeCenterY - bounds.top) / height
    }

    private fun isEyesClosed(face: Face): Boolean {
        val leftOpen = face.leftEyeOpenProbability
        val rightOpen = face.rightEyeOpenProbability
        if (leftOpen == null || rightOpen == null) {
            return false
        }
        return leftOpen < eyeClosedProbabilityThreshold && rightOpen < eyeClosedProbabilityThreshold
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
