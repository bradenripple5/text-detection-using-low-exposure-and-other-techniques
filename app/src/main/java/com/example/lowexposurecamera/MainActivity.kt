package com.example.lowexposurecamera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.SparseIntArray
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.lowexposurecamera.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect2d
import org.opencv.tracking.legacy_Tracker
import org.opencv.tracking.legacy_TrackerMOSSE
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.jvm.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager

    private var cameraId: String? = null
    private var previewSize: Size = Size(1280, 720)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private var previewSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var isTorchAvailable = false
    private var isTorchEnabled = false
    private var sensorOrientation = 0
    @Volatile
    private var isProcessingFrame = false
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var exposureOptimizer: ExposureOptimizer
    @Volatile
    private var lastSceneLuma: Float = 1f
    @Volatile
    private var isLowLightMode = false
    @Volatile
    private var isLicensePlateFilterEnabled = true
    @Volatile
    private var isPhoneDetectionEnabled = false
    private var phoneFocusRect: Rect? = null
    @Volatile
    private var lastSampledLuma: Float = 1f
    private var lastLumaSampleTimestampNs: Long = Long.MIN_VALUE
    private var frameCounter = 0
    private val detectionCounts = mutableMapOf<String, Int>()
    private val previousDetectionBounds = mutableMapOf<String, RectF>()
    private val detectionHistory = ArrayDeque<String>()
    private var dominantTracker: DominantPhoneTracker? = null
    private var lastColumnSamples: ByteArray? = null
    @Volatile
    private var lastColumnDrift: Float = 0f
    private var lastDetectionTimestampMs: Long = -1L
    private var framesUntilNextDetection = 0
    private var consecutiveDetectionMisses = 0
    @Volatile
    private var isOpenCvReady = false

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        processImage(image)
    }

    private val exposureOptionsNs = longArrayOf(
        250_000L,   // 0.25 ms
        500_000L,   // 0.50 ms
        750_000L,   // 0.75 ms
        1_000_000L, // 1.00 ms
        1_500_000L,
        2_000_000L,
        3_000_000L,
        4_000_000L,
        6_000_000L,
        8_000_000L
    )
    private val isoOptions = intArrayOf(100, 200, 400, 800, 1600, 3200)

    private var exposureTimeNs: Long = exposureOptionsNs[3]
    private var isoValue: Int = isoOptions.first()

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Unable to open camera.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(CameraManager::class.java)
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        exposureOptimizer = ExposureOptimizer()
        updateStatus(getString(R.string.status_idle))
        updateExposureInfo()
        setupLowLightToggle()
        setupLicensePlateToggle()
        setupPhoneToggle()
        setupTorchToggle()
        ensureOpenCvReady()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (binding.viewFinder.isAvailable) {
            openCamera(binding.viewFinder.width, binding.viewFinder.height)
        } else {
            binding.viewFinder.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun setupLowLightToggle() {
        binding.lowLightToggle.isChecked = false
        binding.lowLightToggle.setOnCheckedChangeListener { _, isChecked ->
            exposureOptimizer.stop()
            isLowLightMode = false
            if (!isChecked) {
                runOnUiThread {
                    updateStatus(getString(R.string.status_idle))
                }
                updatePreviewSettings()
            }
        }
    }

    private fun setupLicensePlateToggle() {
        binding.licensePlateToggle.isChecked = false
        isLicensePlateFilterEnabled = binding.licensePlateToggle.isChecked
        binding.licensePlateToggle.setOnCheckedChangeListener { _, isChecked ->
            isLicensePlateFilterEnabled = isChecked
        }
    }

    private fun setupPhoneToggle() {
        binding.phoneToggle.isChecked = false
        isPhoneDetectionEnabled = false
        binding.phoneToggle.setOnCheckedChangeListener { _, isChecked ->
            isPhoneDetectionEnabled = isChecked
            resetDetectionCadence()
            if (!isChecked) {
                phoneFocusRect = null
                detectionHistory.clear()
                deactivateDominantTracker()
            }
        }
    }

    private fun setupTorchToggle() {
        binding.torchToggle.isEnabled = false
        binding.torchToggle.setOnCheckedChangeListener { button: CompoundButton, isChecked: Boolean ->
            if (!button.isPressed) {
                return@setOnCheckedChangeListener
            }
            setTorchEnabled(isChecked)
        }
    }

    private fun ensureOpenCvReady(): Boolean {
        if (isOpenCvReady) {
            return true
        }
        isOpenCvReady = OpenCVLoader.initDebug()
        if (!isOpenCvReady) {
            Log.e(TAG, "Failed to initialize OpenCV runtime.")
        }
        return isOpenCvReady
    }

    private fun resetDetectionCadence() {
        framesUntilNextDetection = 0
        consecutiveDetectionMisses = 0
    }

    private fun shouldDeferDetection(): Boolean {
        if (!isPhoneDetectionEnabled) {
            return false
        }
        if (framesUntilNextDetection <= 0) {
            return false
        }
        framesUntilNextDetection--
        return true
    }

    private fun scheduleNextDetection(detectedAny: Boolean) {
        if (!isPhoneDetectionEnabled) {
            resetDetectionCadence()
            return
        }
        if (detectedAny) {
            consecutiveDetectionMisses = 0
        } else {
            consecutiveDetectionMisses = (consecutiveDetectionMisses + 1).coerceAtMost(DETECTION_FORCE_AFTER_MISSES)
        }
        val driftHigh = lastColumnDrift >= DRIFT_FORCE_DETECTION_THRESHOLD
        val shouldRunSoon = driftHigh || consecutiveDetectionMisses >= DETECTION_FORCE_AFTER_MISSES
        val interval = if (shouldRunSoon) MIN_FRAMES_PER_TEXT_DETECTION else MAX_FRAMES_PER_TEXT_DETECTION
        framesUntilNextDetection = (interval - 1).coerceAtLeast(0)
    }

    private fun formatStatusMessage(message: String): String {
        val samples = lastColumnSamples
        return if (samples != null && samples.isNotEmpty()) {
            val driftPercent = lastColumnDrift * 100f
            val driftString = String.format(Locale.US, "%.1f%%", driftPercent)
            "$message | Drift $driftString"
        } else {
            message
        }
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = formatStatusMessage(message)
    }

    private fun updateExposureInfo() {
        val exposureMs = exposureTimeNs / 1_000_000.0
        val infoText = String.format(
            Locale.US,
            getString(R.string.exposure_info_format),
            exposureMs,
            isoValue
        )
        runOnUiThread {
            binding.exposureInfoText.text = infoText
        }
    }

    private fun openCamera(width: Int, height: Int) {
        if (!hasCameraPermission()) {
            requestCameraPermission()
            return
        }
        try {
            setUpCameraOutputs(width, height)
            cameraId?.let {
                cameraManager.openCamera(it, stateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission revoked.", e)
            runOnUiThread {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (binding.viewFinder.isAvailable) {
                openCamera(binding.viewFinder.width, binding.viewFinder.height)
            } else {
                binding.viewFinder.surfaceTextureListener = surfaceTextureListener
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int) {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing != CameraCharacteristics.LENS_FACING_BACK) {
                continue
            }
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width,
                height
            )
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            imageReader?.close()
            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                2
            ).also {
                it.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
            }
            updatePreviewAspectRatio(previewSize)
            cameraId = id
            isTorchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!isTorchAvailable && isTorchEnabled) {
                setTorchEnabled(false, showError = false)
            } else {
                updateTorchToggleState()
            }
            return
        }
    }

    private fun createCameraPreviewSession() {
        val texture = binding.viewFinder.surfaceTexture ?: return
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)

        try {
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!)
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            camera.createCaptureSession(
                listOf(previewSurface!!, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@MainActivity, "Preview configuration failed.", Toast.LENGTH_SHORT).show()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCameraPreviewSession failed", e)
        }
    }

    private fun startPreview() {
        frameCounter = 0
        isLowLightMode = false
        exposureOptimizer.stop()
        updatePreviewSettings()
        runOnUiThread {
            updateStatus(getString(R.string.status_idle))
            updateTorchToggleState()
        }
    }

    private fun processImage(image: Image) {
        if (isProcessingFrame) {
            image.close()
            return
        }
        isProcessingFrame = true
        try {
            if (binding.lowLightToggle.isChecked) {
                val luma = computeAverageLuma(image)
                lastSceneLuma = luma
                updateLowLightState(luma)
            }
            frameCounter++
            updateColumnDrift(image)
            val rotationDegrees = getImageRotationDegrees()
            val baseWidth: Int
            val baseHeight: Int
            if (rotationDegrees % 180 == 0) {
                baseWidth = image.width
                baseHeight = image.height
            } else {
                baseWidth = image.height
                baseHeight = image.width
            }
            val timestampMs = TimeUnit.NANOSECONDS.toMillis(image.timestamp)
            val frameDeltaMs = if (lastDetectionTimestampMs >= 0) {
                (timestampMs - lastDetectionTimestampMs).coerceAtLeast(0L)
            } else {
                0L
            }
            if (shouldUseDominantTracker()) {
                val trackedDetection = trackDominantPhone(
                    image,
                    frameDeltaMs,
                    rotationDegrees
                )
                if (trackedDetection != null) {
                    lastDetectionTimestampMs = timestampMs
                    val detections = listOf(trackedDetection)
                    handleTextResult(
                        detections,
                        image.width,
                        image.height,
                        rotationDegrees,
                        false
                    )
                    updateDetectionHistory(detections)
                    runOnUiThread {
                        val baseStatus = getString(
                            R.string.status_detected_text,
                            detections.size,
                            timestampMs
                        )
                        binding.statusText.text = formatStatusMessage("$baseStatus (tracking bitmap)")
                        exposureOptimizer.onDetectionResult(detections.size)
                    }
                    image.close()
                    isProcessingFrame = false
                    return
                } else {
                    deactivateDominantTracker()
                }
            }
            if (shouldDeferDetection()) {
                image.close()
                isProcessingFrame = false
                return
            }
            val (inputImage, cropRectForFrame) = prepareInputImage(image, rotationDegrees, baseWidth, baseHeight)
            val overlayWidth: Int
            val overlayHeight: Int
            if (cropRectForFrame != null) {
                overlayWidth = baseWidth
                overlayHeight = baseHeight
            } else {
                overlayWidth = inputImage.width
                overlayHeight = inputImage.height
            }
            val recognizerTask = textRecognizer.process(inputImage)
            recognizerTask
                .addOnSuccessListener { text ->
                    val detections = filterDetections(text, frameDeltaMs)
                    val adjustedDetections = adjustDetectionsForCrop(detections, cropRectForFrame)
                    val detectionCount = adjustedDetections.size
                    val isSourceAligned = cropRectForFrame != null
                    handleTextResult(
                        adjustedDetections,
                        overlayWidth,
                        overlayHeight,
                        rotationDegrees,
                        isSourceAligned
                    )
                    runOnUiThread {
                        updateStatus(
                            getString(
                                R.string.status_detected_text,
                                detectionCount,
                                timestampMs
                            )
                        )
                        exposureOptimizer.onDetectionResult(detectionCount)
                    }
                    updateDetectionHistory(adjustedDetections)
                    evaluateDominantTracker(adjustedDetections, image, rotationDegrees)
                    if (isPhoneDetectionEnabled) {
                        updatePhoneFocusRect(adjustedDetections, baseWidth, baseHeight)
                    } else {
                        phoneFocusRect = null
                    }
                    if (detectionCount > 0) {
                        lastDetectionTimestampMs = timestampMs
                    }
                    scheduleNextDetection(detectionCount > 0)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    scheduleNextDetection(false)
                }
                .addOnCompleteListener {
                    image.close()
                    isProcessingFrame = false
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to process image", t)
            scheduleNextDetection(false)
            image.close()
            isProcessingFrame = false
        }
    }

    private fun updatePreviewAspectRatio(size: Size) {
        val orientation = resources.configuration.orientation
        val ratioWidth: Int
        val ratioHeight: Int
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            ratioWidth = size.height
            ratioHeight = size.width
        } else {
            ratioWidth = size.width
            ratioHeight = size.height
        }
        val ratioString = "H,$ratioWidth:$ratioHeight"
        binding.root.post {
            val viewFinderParams = binding.viewFinder.layoutParams as ConstraintLayout.LayoutParams
            if (viewFinderParams.dimensionRatio != ratioString) {
                viewFinderParams.dimensionRatio = ratioString
                binding.viewFinder.layoutParams = viewFinderParams
            }
            val overlayParams = binding.textOverlay.layoutParams as ConstraintLayout.LayoutParams
            if (overlayParams.dimensionRatio != ratioString) {
                overlayParams.dimensionRatio = ratioString
                binding.textOverlay.layoutParams = overlayParams
            }
        }
    }

    private fun handleTextResult(
        detections: List<TextOverlayView.Detection>,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        sourceAlreadyAligned: Boolean
    ) {
        runOnUiThread {
            if (detections.isEmpty()) {
                binding.textOverlay.clear()
            } else {
                val rotatedWidth: Int
                val rotatedHeight: Int
                if (rotationDegrees % 180 == 0 || sourceAlreadyAligned) {
                    rotatedWidth = width
                    rotatedHeight = height
                } else {
                    rotatedWidth = height
                    rotatedHeight = width
                }
                binding.textOverlay.updateDetections(rotatedWidth, rotatedHeight, detections)
            }
        }
    }

    private fun adjustDetectionsForCrop(
        detections: List<TextOverlayView.Detection>,
        cropRect: Rect?
    ): List<TextOverlayView.Detection> {
        if (cropRect == null || detections.isEmpty()) {
            return detections
        }
        val offsetX = cropRect.left.toFloat()
        val offsetY = cropRect.top.toFloat()
        return detections.map { detection ->
            val adjustedBounds = RectF(detection.bounds).apply {
                offset(offsetX, offsetY)
            }
            val adjustedPrevious = detection.previousBounds?.let { RectF(it).apply { offset(offsetX, offsetY) } }
            TextOverlayView.Detection(
                detection.text,
                adjustedBounds,
                detection.frameDeltaMs,
                detection.count,
                adjustedPrevious,
                detection.isTrackingBitmap
            )
        }
    }

    private fun updateDetectionHistory(detections: List<TextOverlayView.Detection>) {
        if (!isPhoneDetectionEnabled || detections.isEmpty()) {
            detectionHistory.clear()
            return
        }
        detections.forEach { detection ->
            val key = detection.text.uppercase(Locale.US)
            detectionHistory.addLast(key)
            if (detectionHistory.size > DOMINANT_HISTORY_SIZE) {
                detectionHistory.removeFirst()
            }
        }
    }

    private fun evaluateDominantTracker(
        detections: List<TextOverlayView.Detection>,
        image: Image? = null,
        rotationDegrees: Int
    ) {
        if (detections.isEmpty()) {
            deactivateDominantTracker()
            detectionHistory.clear()
            return
        }
        if (detectionHistory.size < DOMINANT_HISTORY_SIZE) {
            return
        }
        val total = detectionHistory.size.toFloat()
        if (total <= 0f) {
            return
        }
        val frequency = detectionHistory.groupingBy { it }.eachCount()
        val dominantEntry = frequency.maxByOrNull { it.value } ?: return
        val dominantKey = dominantEntry.key
        val ratio = dominantEntry.value / total
        if (ratio >= DOMINANT_RATIO_THRESHOLD) {
            val match = detections.firstOrNull { it.text.equals(dominantKey, ignoreCase = true) }
            if (match != null && phoneFocusRect != null && image != null) {
                activateDominantTracker(dominantKey, match.text, phoneFocusRect!!, image, rotationDegrees)
            }
        } else if (dominantTracker != null && detections.none { it.text.equals(dominantTracker?.displayText, ignoreCase = true) }) {
            deactivateDominantTracker()
        }
    }

    private fun shouldUseDominantTracker(): Boolean {
        if (!isPhoneDetectionEnabled || !isOpenCvReady) {
            return false
        }
        val trackerState = dominantTracker
        return trackerState?.tracker != null && phoneFocusRect != null
    }

    private fun activateDominantTracker(
        key: String,
        displayText: String,
        focusRect: Rect,
        image: Image,
        rotationDegrees: Int
    ) {
        if (!ensureOpenCvReady()) {
            return
        }
        val trackerMat = imageToGrayMat(image, rotationDegrees) ?: return
        val frameWidth = trackerMat.width()
        val frameHeight = trackerMat.height()
        val rect = clampRectToBounds(focusRect, frameWidth, frameHeight)
        if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
            trackerMat.release()
            return
        }
        try {
            val tracker = legacy_TrackerMOSSE.create()
            val initialized = tracker.init(trackerMat, rect.toRect2d())
            if (!initialized) {
                tracker.clear()
                return
            }
            deactivateDominantTracker()
            val trackingCount = detectionCounts.getOrDefault(key, 0)
            dominantTracker = DominantPhoneTracker(
                key = key,
                displayText = displayText,
                currentRect = Rect(rect),
                trackingCount = trackingCount,
                tracker = tracker,
                referenceRotation = rotationDegrees
            )
            phoneFocusRect = Rect(rect)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MOSSE tracker", e)
        } finally {
            trackerMat.release()
        }
    }

    private fun deactivateDominantTracker() {
        dominantTracker?.tracker?.let { tracker ->
            runCatching { tracker.clear() }.onFailure { error ->
                Log.w(TAG, "Unable to clear MOSSE tracker", error)
            }
        }
        dominantTracker = null
        resetDetectionCadence()
    }

    private fun filterDetections(text: Text, frameDeltaMs: Long): List<TextOverlayView.Detection> {
        val detections = mutableListOf<TextOverlayView.Detection>()
        val shouldFilterForPlates = isLicensePlateFilterEnabled && !isPhoneDetectionEnabled
        val nextBoundsByKey = mutableMapOf<String, RectF>()
        text.textBlocks.forEach { block ->
            block.lines.forEach lineLoop@{ line ->
                val bounds = line.boundingBox ?: return@lineLoop
                val normalizedText = line.text.trim()
                if (normalizedText.isEmpty()) {
                    return@lineLoop
                }
                val key = normalizedText.uppercase(Locale.US)
                val previousBounds = previousDetectionBounds[key]?.let { RectF(it) }
                if (isPhoneDetectionEnabled) {
                    if (isPhoneNumber(normalizedText)) {
                        val count = detectionCounts.getOrDefault(key, 0) + 1
                        detectionCounts[key] = count
                        detections.add(
                            TextOverlayView.Detection(
                                normalizedText,
                                RectF(bounds),
                                frameDeltaMs,
                                count,
                                previousBounds
                            )
                        )
                        nextBoundsByKey[key] = RectF(bounds)
                    }
                } else if (shouldFilterForPlates) {
                    val cleanedText = normalizedText.filter { it.isLetterOrDigit() }
                    if (cleanedText.length in MIN_PLATE_CHAR_COUNT..MAX_PLATE_CHAR_COUNT) {
                        val count = detectionCounts.getOrDefault(key, 0) + 1
                        detectionCounts[key] = count
                        detections.add(
                            TextOverlayView.Detection(
                                cleanedText,
                                RectF(bounds),
                                frameDeltaMs,
                                count,
                                previousBounds
                            )
                        )
                        nextBoundsByKey[key] = RectF(bounds)
                    }
                } else {
                    val count = detectionCounts.getOrDefault(key, 0) + 1
                    detectionCounts[key] = count
                    detections.add(
                        TextOverlayView.Detection(
                            normalizedText,
                            RectF(bounds),
                            frameDeltaMs,
                            count,
                            previousBounds
                        )
                    )
                    nextBoundsByKey[key] = RectF(bounds)
                }
            }
        }
        previousDetectionBounds.clear()
        previousDetectionBounds.putAll(nextBoundsByKey)
        return detections
    }

    private fun updatePhoneFocusRect(
        detections: List<TextOverlayView.Detection>,
        width: Int,
        height: Int
    ) {
        if (!isPhoneDetectionEnabled) {
            phoneFocusRect = null
            return
        }
        val phoneDetection = detections.firstOrNull { isPhoneNumber(it.text) }
        if (phoneDetection == null) {
            phoneFocusRect = null
            return
        }
        phoneFocusRect = createExpandedRect(phoneDetection.bounds, width, height)
    }

    private fun createExpandedRect(bounds: RectF, width: Int, height: Int): Rect {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val expandedHalfWidth = bounds.width() * PHONE_FOCUS_WIDTH_SCALE * 0.5f
        val expandedHalfHeight = bounds.height() * PHONE_FOCUS_HEIGHT_SCALE * 0.5f
        val left = (centerX - expandedHalfWidth).coerceAtLeast(0f)
        val top = (centerY - expandedHalfHeight).coerceAtLeast(0f)
        val right = (centerX + expandedHalfWidth).coerceAtMost(width.toFloat())
        val bottom = (centerY + expandedHalfHeight).coerceAtMost(height.toFloat())
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt()
        )
    }

    private fun extractLumaData(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        val data = ByteArray(width * height)
        var destIndex = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val index = rowStart + x * pixelStride
                data[destIndex++] = if (index < limit) buffer.get(index) else 0
            }
        }
        return data
    }

    private fun isPhoneNumber(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) {
            return false
        }
        return PHONE_NUMBER_PATTERN.containsMatchIn(text)
    }

    private fun getImageRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val deviceRotation = ORIENTATIONS.get(rotation, 0)
        return (sensorOrientation - deviceRotation + 360) % 360
    }

    private fun updatePreviewSettings() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val lowLightEnabled = binding.lowLightToggle.isChecked
        val shouldUseManualExposure = lowLightEnabled && isLowLightMode
        if (shouldUseManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (isTorchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        try {
            previewRequest = builder.build()
            session.setRepeatingRequest(previewRequest!!, null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "applyManualPreviewSettings failed", e)
        }
    }

    private fun updateColumnDrift(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) {
            return
        }
        val columnX = (width * COLUMN_MONITOR_X_RATIO).roundToInt().coerceIn(0, width - 1)
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val limit = buffer.limit()
        val samples = ByteArray(height)
        for (y in 0 until height) {
            val offset = y * rowStride + columnX * pixelStride
            samples[y] = if (offset < limit) buffer.get(offset) else 0.toByte()
        }
        val previous = lastColumnSamples
        if (previous != null && previous.size == samples.size) {
            var sum = 0L
            for (i in samples.indices) {
                val curr = samples[i].toInt() and 0xFF
                val prev = previous[i].toInt() and 0xFF
                sum += abs(curr - prev)
            }
            lastColumnDrift = (sum.toFloat() / (samples.size * 255f)).coerceIn(0f, 1f)
        } else {
            lastColumnDrift = 0f
        }
        lastColumnSamples = samples
    }

    private fun closeCamera() {
        if (isTorchEnabled) {
            setTorchEnabled(false, showError = false)
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface = null
        exposureOptimizer.stop()
        isLowLightMode = false
        binding.textOverlay.clear()
        detectionHistory.clear()
        deactivateDominantTracker()
        updateTorchToggleState()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraThread").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted stopping background thread", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
        deactivateDominantTracker()
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val bigEnough = choices.filter { it.width >= width && it.height >= height }
        return when {
            bigEnough.isNotEmpty() -> bigEnough.minBy { it.width.toLong() * it.height }
            choices.isNotEmpty() -> choices.maxBy { it.width.toLong() * it.height }
            else -> previewSize
        }
    }

    private fun updateTorchToggleState() {
        val enableToggle = isTorchAvailable && cameraDevice != null
        runOnUiThread {
            binding.torchToggle.isEnabled = enableToggle
            val shouldBeChecked = enableToggle && isTorchEnabled
            if (binding.torchToggle.isChecked != shouldBeChecked) {
                binding.torchToggle.isChecked = shouldBeChecked
            }
        }
    }

    private fun setTorchEnabled(shouldEnable: Boolean, showError: Boolean = true) {
        if (!isTorchAvailable) {
            if (shouldEnable && showError) {
                Toast.makeText(this, "Camera light not available.", Toast.LENGTH_SHORT).show()
            }
            isTorchEnabled = false
            updateTorchToggleState()
            return
        }
        isTorchEnabled = shouldEnable
        updatePreviewSettings()
        updateTorchToggleState()
    }

    private fun applyExposureSetting(setting: ExposureSetting) {
        exposureTimeNs = setting.exposureTimeNs
        isoValue = setting.isoValue
        updateExposureInfo()
        if (isLowLightMode) {
            updatePreviewSettings()
        }
    }

    private fun updateLowLightState(luma: Float) {
        if (!binding.lowLightToggle.isChecked) {
            return
        }
        if (!isLowLightMode && luma <= LOW_LIGHT_ENTER_THRESHOLD) {
            isLowLightMode = true
            exposureOptimizer.start()
            runOnUiThread {
                updateStatus(getString(R.string.status_low_light_mode))
            }
        } else if (isLowLightMode && luma >= LOW_LIGHT_EXIT_THRESHOLD) {
            isLowLightMode = false
            exposureOptimizer.stop()
            updatePreviewSettings()
            runOnUiThread {
                updateStatus(getString(R.string.status_normal_mode))
            }
        }
    }

    private inner class ExposureOptimizer {
        private val settings: List<ExposureSetting> = exposureOptionsNs.flatMap { exposure ->
            isoOptions.map { iso ->
                ExposureSetting(exposure, iso)
            }
        }.sortedBy { it.isoValue * it.exposureTimeNs }

        private var state = OptimizationState.SAMPLING
        private var currentIndex = 0
        private var framesOnCurrent = 0
        private var scoreSum = 0f
        private var bestSetting = settings.firstOrNull()
        private var bestScore = -1f
        private var monitorFrames = 0
        private var monitorSum = 0f
        private var active = false

        fun start() {
            if (settings.isEmpty()) {
                return
            }
            if (!isLowLightMode) {
                return
            }
            active = true
            state = OptimizationState.SAMPLING
            currentIndex = 0
            framesOnCurrent = 0
            scoreSum = 0f
            monitorFrames = 0
            monitorSum = 0f
            bestScore = -1f
            bestSetting = settings.first()
            applyExposureSetting(settings[currentIndex])
        }

        fun stop() {
            active = false
            monitorFrames = 0
            monitorSum = 0f
            framesOnCurrent = 0
        }

        fun onDetectionResult(lineCount: Int) {
            if (!active || settings.isEmpty()) {
                return
            }
            when (state) {
                OptimizationState.SAMPLING -> handleSampling(lineCount)
                OptimizationState.HOLDING_BEST -> handleHolding(lineCount)
            }
        }

        private fun handleSampling(lineCount: Int) {
            framesOnCurrent++
            scoreSum += lineCount
            if (framesOnCurrent >= FRAMES_PER_SETTING) {
                val setting = settings[currentIndex]
                val avgScore = scoreSum / framesOnCurrent
                if (bestScore < 0f || avgScore > bestScore + MIN_SCORE_DELTA) {
                    bestScore = avgScore
                    bestSetting = setting
                }
                advanceSetting()
            }
        }

        private fun advanceSetting() {
            currentIndex++
            framesOnCurrent = 0
            scoreSum = 0f
            if (currentIndex >= settings.size) {
                state = OptimizationState.HOLDING_BEST
                bestSetting?.let { applyExposureSetting(it) }
                monitorFrames = 0
                monitorSum = 0f
            } else {
                applyExposureSetting(settings[currentIndex])
            }
        }

        private fun handleHolding(lineCount: Int) {
            monitorFrames++
            monitorSum += lineCount
            if (monitorFrames >= FRAMES_BEFORE_REEVALUATE) {
                val average = monitorSum / monitorFrames
                if (bestScore <= 0f || average < bestScore * (1f - SCORE_DROP_THRESHOLD)) {
                    restartSampling()
                } else {
                    monitorFrames = 0
                    monitorSum = 0f
                }
            }
        }

        private fun restartSampling() {
            state = OptimizationState.SAMPLING
            currentIndex = 0
            framesOnCurrent = 0
            scoreSum = 0f
            monitorFrames = 0
            monitorSum = 0f
            bestScore = -1f
            bestSetting = settings.firstOrNull()
            applyExposureSetting(settings[currentIndex])
        }
    }

    private fun computeAverageLuma(image: Image): Float {
        val timestampNs = image.timestamp
        val elapsedSinceSample = timestampNs - lastLumaSampleTimestampNs
        if (elapsedSinceSample in 0 until LUMA_SAMPLE_INTERVAL_NS && lastSampledLuma >= 0f) {
            return lastSampledLuma
        }
        val plane = image.planes.firstOrNull() ?: return lastSampledLuma
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val sampleCount = minOf(MAX_MONTE_CARLO_SAMPLES, width * height)
        var sum = 0L
        var samplesTaken = 0
        repeat(sampleCount) {
            val x = Random.nextInt(width)
            val y = Random.nextInt(height)
            val index = y * rowStride + x * pixelStride
            if (index in 0 until buffer.limit()) {
                sum += buffer.get(index).toInt() and 0xFF
                samplesTaken++
            }
        }
        if (samplesTaken == 0) {
            return lastSampledLuma
        }
        val averageLuma = (sum.toFloat() / samplesTaken) / 255f
        lastSampledLuma = averageLuma
        lastLumaSampleTimestampNs = timestampNs
        return averageLuma
    }

    private fun prepareInputImage(
        image: Image,
        rotationDegrees: Int,
        width: Int,
        height: Int
    ): Pair<InputImage, Rect?> {
        val cropRect = computeCropRect(width, height)
        if (cropRect == null) {
            return InputImage.fromMediaImage(image, rotationDegrees) to null
        }
        val bitmap = mediaImageToBitmap(image)
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)
        val boundedRect = clampRectToBounds(cropRect, rotatedBitmap.width, rotatedBitmap.height)
        if (boundedRect.isEmpty) {
            if (rotatedBitmap !== bitmap) {
                rotatedBitmap.recycle()
            }
            bitmap.recycle()
            return InputImage.fromMediaImage(image, rotationDegrees) to null
        }
        val croppedBitmap = Bitmap.createBitmap(
            rotatedBitmap,
            boundedRect.left,
            boundedRect.top,
            boundedRect.width(),
            boundedRect.height()
        )
        if (rotatedBitmap !== bitmap) {
            rotatedBitmap.recycle()
        }
        bitmap.recycle()
        return InputImage.fromBitmap(croppedBitmap, 0) to boundedRect
    }

    private fun computeCropRect(width: Int, height: Int): Rect? {
        if (!isPhoneDetectionEnabled) {
            phoneFocusRect = null
            return null
        }
        val currentFocus = phoneFocusRect?.let {
            val clamped = clampRectToBounds(it, width, height)
            if (clamped.isEmpty) {
                phoneFocusRect = null
                null
            } else {
                clamped
            }
        }
        if (currentFocus != null && !currentFocus.isEmpty) {
            return currentFocus
        }
        val fallbackWidth = (width * PHONE_FALLBACK_WIDTH_RATIO).roundToInt()
            .coerceAtLeast(PHONE_MIN_FALLBACK_SIZE)
            .coerceAtMost(width)
        val fallbackHeight = (height * PHONE_FALLBACK_HEIGHT_RATIO).roundToInt()
            .coerceAtLeast(PHONE_MIN_FALLBACK_SIZE)
            .coerceAtMost(height)
        val halfWidth = fallbackWidth / 2
        val halfHeight = fallbackHeight / 2
        val centerX = width / 2
        val centerY = height / 2
        val maxLeft = (width - fallbackWidth).coerceAtLeast(0)
        val maxTop = (height - fallbackHeight).coerceAtLeast(0)
        val left = (centerX - halfWidth).coerceIn(0, maxLeft)
        val top = (centerY - halfHeight).coerceIn(0, maxTop)
        val rect = Rect(left, top, left + fallbackWidth, top + fallbackHeight)
        return if (rect.isEmpty) null else rect
    }

    private fun trackDominantPhone(
        image: Image,
        frameDeltaMs: Long,
        rotationDegrees: Int
    ): TextOverlayView.Detection? {
        val trackerState = dominantTracker ?: return null
        val tracker = trackerState.tracker ?: return null
        if (rotationDegrees != trackerState.referenceRotation) {
            deactivateDominantTracker()
            return null
        }
        val trackerMat = imageToGrayMat(image, rotationDegrees) ?: return null
        val frameWidth = trackerMat.width()
        val frameHeight = trackerMat.height()
        return try {
            val updatedRect = Rect2d()
            val success = tracker.update(trackerMat, updatedRect)
            if (!success) {
                deactivateDominantTracker()
                null
            } else {
                val androidRect = updatedRect.toAndroidRect()
                val clampedRect = clampRectToBounds(androidRect, frameWidth, frameHeight)
                if (clampedRect.isEmpty) {
                    deactivateDominantTracker()
                    null
                } else {
                    val updatedCount = detectionCounts.getOrDefault(trackerState.key, 0) + 1
                    detectionCounts[trackerState.key] = updatedCount
                    trackerState.trackingCount = updatedCount
                    val previousBounds = RectF(trackerState.currentRect)
                    trackerState.currentRect = Rect(clampedRect)
                    phoneFocusRect = Rect(clampedRect)
                    TextOverlayView.Detection(
                        trackerState.displayText,
                        RectF(clampedRect),
                        frameDeltaMs,
                        updatedCount,
                        previousBounds,
                        isTrackingBitmap = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MOSSE tracker update failed", e)
            deactivateDominantTracker()
            null
        } finally {
            trackerMat.release()
        }
    }

    private fun Rect.toRect2d(): Rect2d {
        val safeWidth = width().coerceAtLeast(1)
        val safeHeight = height().coerceAtLeast(1)
        return Rect2d(
            left.toDouble(),
            top.toDouble(),
            safeWidth.toDouble(),
            safeHeight.toDouble()
        )
    }

    private fun Rect2d.toAndroidRect(): Rect {
        val left = x.roundToInt()
        val top = y.roundToInt()
        val right = (x + width).roundToInt()
        val bottom = (y + height).roundToInt()
        return Rect(left, top, right, bottom)
    }

    private fun clampRectToBounds(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, width)
        val top = rect.top.coerceIn(0, height)
        val right = rect.right.coerceIn(left, width)
        val bottom = rect.bottom.coerceIn(top, height)
        return Rect(left, top, right, bottom)
    }

    private fun imageToGrayMat(image: Image, rotationDegrees: Int): Mat? {
        val plane = image.planes.firstOrNull() ?: return null
        val lumaData = extractLumaData(plane, image.width, image.height)
        var mat = Mat(image.height, image.width, CvType.CV_8UC1)
        mat.put(0, 0, lumaData)
        if (rotationDegrees == 0) {
            return mat
        }
        val rotateCode = when (rotationDegrees % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> null
        }
        if (rotateCode == null) {
            return mat
        }
        val rotated = Mat()
        Core.rotate(mat, rotated, rotateCode)
        mat.release()
        return rotated
    }

    private fun mediaImageToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) {
            return bitmap
        }
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) {
                bitmap.recycle()
            }
        }
    }

    private data class DominantPhoneTracker(
        val key: String,
        var displayText: String,
        var currentRect: Rect,
        var trackingCount: Int = 0,
        var tracker: legacy_Tracker? = null,
        var referenceRotation: Int = 0
    )

    private data class ExposureSetting(val exposureTimeNs: Long, val isoValue: Int)

    private enum class OptimizationState { SAMPLING, HOLDING_BEST }

    companion object {
        private const val TAG = "LowExposureCamera"
        private const val REQUEST_CAMERA_PERMISSION = 1
        private const val FRAMES_PER_SETTING = 8
        private const val FRAMES_BEFORE_REEVALUATE = 60
        private const val SCORE_DROP_THRESHOLD = 0.25f
        private const val MIN_SCORE_DELTA = 0.5f
        private const val MIN_FRAMES_PER_TEXT_DETECTION = 1
        private const val MAX_FRAMES_PER_TEXT_DETECTION = 3
        private const val DETECTION_FORCE_AFTER_MISSES = 2
        private const val DRIFT_FORCE_DETECTION_THRESHOLD = 0.18f
        private const val PHONE_FOCUS_WIDTH_SCALE = 1.5f
        private const val PHONE_FOCUS_HEIGHT_SCALE = 3f
        private const val PHONE_FALLBACK_WIDTH_RATIO = 0.45f
        private const val PHONE_FALLBACK_HEIGHT_RATIO = 0.7f
        private const val PHONE_MIN_FALLBACK_SIZE = 120
        private const val LOW_LIGHT_ENTER_THRESHOLD = 0.03f
        private const val LOW_LIGHT_EXIT_THRESHOLD = 0.06f
        private const val MAX_MONTE_CARLO_SAMPLES = 100
        private val LUMA_SAMPLE_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1)
        private const val MAX_PLATE_CHAR_COUNT = 8
        private const val MIN_PLATE_CHAR_COUNT = 4
        private const val MIN_PHONE_DIGITS = 7
        private const val MAX_PHONE_DIGITS = 14
        private val PHONE_NUMBER_PATTERN = Regex("""\+?[\d\-\s\(\)]{7,}""")
        private const val DOMINANT_HISTORY_SIZE = 5
        private const val DOMINANT_RATIO_THRESHOLD = 0.8f
        private const val COLUMN_MONITOR_X_RATIO = 0.5f
        private const val TRACKING_PATCH_RADIUS = 4
        private const val TRACKING_SEARCH_RADIUS = 12
        private const val TRACKING_MIN_SAMPLES = 3
        private val ORIENTATIONS = SparseIntArray().apply {
            put(Surface.ROTATION_0, 0)
            put(Surface.ROTATION_90, 90)
            put(Surface.ROTATION_180, 180)
            put(Surface.ROTATION_270, 270)
        }
    }
}
