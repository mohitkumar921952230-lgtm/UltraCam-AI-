package com.mohit.ultracam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import com.mohit.ultracam.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    enum class CamMode { NIGHT, PORTRAIT, PHOTO, VIDEO, PRO }
    enum class FlashMode(val label: String) { AUTO("⚡Auto"), ON("⚡On"), OFF("⚡Off") }

    private lateinit var binding: ActivityMainBinding
    private lateinit var gyroStabilizer: GyroStabilizer
    private lateinit var glProcessor: UltraGlSurfaceProcessor

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentMode = CamMode.PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = FlashMode.AUTO
    private var timerSeconds = 0
    private var aspectRatio = AspectRatio.RATIO_4_3
    private var gridEnabled = false
    private var stabilizationEnabled = true
    private var proManualEnabled = false

    private var isLowLightScene = false
    private var lastLumaCheckMs = 0L
    private var requestedZoom = 1.0f
    private var lastSavedUri: Uri? = null
    private var isCapturing = false
    private var recordSeconds = 0

    private val lutChips = mutableMapOf<LutType, TextView>()

    private val recordTicker = object : Runnable {
        override fun run() {
            recordSeconds++
            val min = recordSeconds / 60
            val sec = recordSeconds % 60
            binding.tvSubStatus.text = String.format("● REC %02d:%02d • Live LUT + Gimbal ON", min, sec)
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            binding.previewView.post { bindCameraUseCases() }
        } else {
            Toast.makeText(this, "Camera permission required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        gyroStabilizer = GyroStabilizer(this)
        glProcessor = UltraGlSurfaceProcessor(gyroStabilizer)

        setupUiStyling()
        setupLutStrip()
        setupClickListeners()
        setupProControls()
        setupTouchGestures()

        if (hasRequiredPermissions()) {
            binding.previewView.post { bindCameraUseCases() }
        } else {
            requestRequiredPermissions()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun setupUiStyling() {
        binding.tvAiBadge.background = GradientDrawable().apply {
            setColor(Color.parseColor("#88111111"))
            cornerRadius = 40f
            setStroke(2, Color.parseColor("#55FFD54F"))
        }
        binding.focusRing.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(4, Color.parseColor("#FFD54F"))
            cornerRadius = 18f
        }
        binding.btnSwitchCamera.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#44FFFFFF"))
        }
        binding.btnProManualToggle.background = GradientDrawable().apply {
            setColor(Color.parseColor("#33FFFFFF"))
            cornerRadius = 20f
        }
        updateShutterAppearance(isRecording = false)
        updateModeTabColors()
    }

    private fun updateShutterAppearance(isRecording: Boolean) {
        binding.btnShutter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(7, Color.WHITE)
        }
        binding.shutterInner.background = GradientDrawable().apply {
            shape = if (isRecording) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            if (isRecording) cornerRadius = 16f
            setColor(
                when {
                    isRecording -> Color.parseColor("#FF3B30")
                    currentMode == CamMode.VIDEO -> Color.parseColor("#FF3B30")
                    else -> Color.WHITE
                }
            )
        }
        val lp = binding.shutterInner.layoutParams
        val sizeDp = if (isRecording) 34 else 60
        val px = (sizeDp * resources.displayMetrics.density).toInt()
        lp.width = px
        lp.height = px
        binding.shutterInner.layoutParams = lp
    }

    private fun setupLutStrip() {
        LutType.entries.forEach { lut ->
            val tv = TextView(this).apply {
                text = lut.displayName
                textSize = 11f
                setPadding(26, 12, 26, 12)
                setOnClickListener {
                    selectLut(lut)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            binding.lutContainer.addView(tv, lp)
            lutChips[lut] = tv
        }
        selectLut(LutType.IPHONE_NATURAL)
    }

    private fun selectLut(lut: LutType) {
        glProcessor.targetLut = lut
        glProcessor.goldenGlowAmount = when (lut) {
            LutType.GOLDEN_SUNSET -> 0.45f
            LutType.IPHONE_NATURAL -> 0.18f
            LutType.MOODY_NATURE -> 0.12f
            LutType.ULTRA_VIVID -> 0.20f
            LutType.CINEMA_TEAL -> 0.25f
            LutType.NEUTRAL -> 0.0f
        }
        lutChips.forEach { (type, view) ->
            val active = type == lut
            view.setTextColor(if (active) Color.BLACK else Color.WHITE)
            view.background = GradientDrawable().apply {
                setColor(if (active) Color.parseColor("#FFD54F") else Color.parseColor("#33FFFFFF"))
                cornerRadius = 32f
            }
        }
        updateAiBadge()
    }

    private fun updateAiBadge() {
        val lutName = glProcessor.targetLut.displayName
        val nightTag = if (currentMode == CamMode.NIGHT || isLowLightScene) " • 🌙 Night AI" else ""
        val portraitTag = if (currentMode == CamMode.PORTRAIT) " • Bokeh AI" else ""
        binding.tvAiBadge.text = "AI: $lutName$portraitTag$nightTag"
    }

    private fun setupClickListeners() {
        binding.modeNight.setOnClickListener { switchMode(CamMode.NIGHT) }
        binding.modePortrait.setOnClickListener { switchMode(CamMode.PORTRAIT) }
        binding.modePhoto.setOnClickListener { switchMode(CamMode.PHOTO) }
        binding.modeVideo.setOnClickListener { switchMode(CamMode.VIDEO) }
        binding.modePro.setOnClickListener { switchMode(CamMode.PRO) }

        binding.btnSwitchCamera.setOnClickListener {
            if (activeRecording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            requestedZoom = 1.0f
            applyZoom(1.0f)
            bindCameraUseCases()
        }

        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.OFF
                FlashMode.OFF -> FlashMode.AUTO
            }
            binding.btnFlash.text = flashMode.label
            imageCapture?.flashMode = when (flashMode) {
                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            }
            if (currentMode == CamMode.VIDEO) {
                camera?.cameraControl?.enableTorch(flashMode == FlashMode.ON)
            }
        }

        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            binding.btnTimer.text = if (timerSeconds == 0) "⏱ Off" else "⏱ ${timerSeconds}s"
        }

        binding.btnRatio.setOnClickListener {
            if (activeRecording != null) return@setOnClickListener
            aspectRatio = if (aspectRatio == AspectRatio.RATIO_4_3) {
                AspectRatio.RATIO_16_9
            } else {
                AspectRatio.RATIO_4_3
            }
            binding.btnRatio.text = if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
            bindCameraUseCases()
        }

        binding.btnGrid.setOnClickListener {
            gridEnabled = !gridEnabled
            binding.gridOverlay.visibility = if (gridEnabled) View.VISIBLE else View.GONE
            binding.btnGrid.setTextColor(if (gridEnabled) Color.parseColor("#FFD54F") else Color.GRAY)
        }

        binding.btnStab.setOnClickListener {
            stabilizationEnabled = !stabilizationEnabled
            gyroStabilizer.isEnabled = stabilizationEnabled
            glProcessor.stabilizationEnabled = stabilizationEnabled
            binding.btnStab.text = if (stabilizationEnabled) "Gimbal ON" else "Gimbal OFF"
            binding.btnStab.setTextColor(if (stabilizationEnabled) Color.parseColor("#FFD54F") else Color.GRAY)
        }

        binding.btnZoom1x.setOnClickListener { applyZoom(1.0f) }
        binding.btnZoom2x.setOnClickListener { applyZoom(2.0f) }
        binding.btnZoom3x.setOnClickListener { applyZoom(3.0f) }
        binding.btnZoom5x.setOnClickListener { applyZoom(5.0f) }

        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val maxZ = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 4.0f else 10.0f
                val z = 1.0f + (progress / 100f) * (maxZ - 1.0f)
                applyZoom(z)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnShutter.setOnClickListener {
            if (currentMode == CamMode.VIDEO) {
                toggleVideoRecording()
            } else {
                triggerPhotoCaptureWithTimer()
            }
        }

        binding.ivGalleryThumb.setOnClickListener {
            val uri = lastSavedUri ?: return@setOnClickListener
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (currentMode == CamMode.VIDEO) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }

    private fun switchMode(mode: CamMode) {
        if (activeRecording != null || isCapturing) return
        currentMode = mode
        binding.proPanel.visibility = if (mode == CamMode.PRO) View.VISIBLE else View.GONE
        updateModeTabColors()
        updateShutterAppearance(isRecording = false)
        updateAiBadge()
        bindCameraUseCases()
    }

    private fun updateModeTabColors() {
        val gold = Color.parseColor("#FFD54F")
        val gray = Color.parseColor("#AAAAAA")
        binding.modeNight.setTextColor(if (currentMode == CamMode.NIGHT) gold else gray)
        binding.modePortrait.setTextColor(if (currentMode == CamMode.PORTRAIT) gold else gray)
        binding.modePhoto.setTextColor(if (currentMode == CamMode.PHOTO) gold else gray)
        binding.modeVideo.setTextColor(if (currentMode == CamMode.VIDEO) gold else gray)
        binding.modePro.setTextColor(if (currentMode == CamMode.PRO) gold else gray)
    }

    private fun setupProControls() {
        binding.btnProManualToggle.setOnClickListener {
            proManualEnabled = !proManualEnabled
            binding.btnProManualToggle.text = if (proManualEnabled) "Manual: ON" else "Manual: OFF"
            binding.btnProManualToggle.setTextColor(
                if (proManualEnabled) Color.parseColor("#FFD54F") else Color.WHITE
            )
            applyProSettings()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyProSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        binding.seekIso.setOnSeekBarChangeListener(listener)
        binding.seekShutter.setOnSeekBarChangeListener(listener)
        binding.seekFocus.setOnSeekBarChangeListener(listener)
        binding.seekEv.setOnSeekBarChangeListener(listener)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyProSettings() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)

        if (!proManualEnabled) {
            c2Control.clearCaptureRequestOptions()
            val evRange = cam.cameraInfo.exposureState.exposureCompensationRange
            val evIdx = (((binding.seekEv.progress - 10) / 10f) * evRange.upper).toInt()
                .coerceIn(evRange.lower, evRange.upper)
            cam.cameraControl.setExposureCompensationIndex(evIdx)
            binding.tvProValues.text = "Auto AE/AF • EV: $evIdx"
            return
        }

        val iso = 100 + (binding.seekIso.progress * 31) // 100 - 3200
        // Shutter 1/2000s to 1/4s in nanoseconds
        val shutterNs = (500_000L + (binding.seekShutter.progress.toLong() * binding.seekShutter.progress.toLong() * 25_000L))
        val focusDist = (binding.seekFocus.progress / 100f) * 10f

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
            .build()
        c2Control.setCaptureRequestOptions(options)
        val denom = (1_000_000_000L / shutterNs.coerceAtLeast(1)).coerceAtLeast(1)
        binding.tvProValues.text = "ISO $iso • 1/${denom}s • MF %.1f".format(focusDist)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val maxZ = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 4.0f else 10.0f
                    val nextZoom = (requestedZoom * detector.scaleFactor).coerceIn(1.0f, maxZ)
                    applyZoom(nextZoom)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                showFocusRing(event.x, event.y)
            }
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val half = binding.focusRing.width / 2f
        binding.focusRing.x = x - half
        binding.focusRing.y = y - half
        binding.focusRing.visibility = View.VISIBLE
        binding.focusRing.alpha = 1f
        binding.focusRing.scaleX = 1.25f
        binding.focusRing.scaleY = 1.25f
        binding.focusRing.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            mainHandler.postDelayed({
                binding.focusRing.animate().alpha(0f).setDuration(300).withEndAction {
                    binding.focusRing.visibility = View.GONE
                }.start()
            }, 900)
        }.start()
    }

    private fun applyZoom(zoom: Float) {
        val maxZ = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 4.0f else 10.0f
        requestedZoom = zoom.coerceIn(1.0f, maxZ)

        val zoomState = camera?.cameraInfo?.zoomState?.value
        val maxHw = zoomState?.maxZoomRatio ?: 1.0f
        val hwZoom = requestedZoom.coerceAtMost(maxHw)
        camera?.cameraControl?.setZoomRatio(hwZoom)

        // Seamless GPU AI-Sharpened Zoom whenever HW zoom is capped (e.g. Front camera!)
        val extraGpuZoom = (requestedZoom / hwZoom.coerceAtLeast(1.0f)).coerceAtLeast(1.0f)
        glProcessor.gpuZoomFactor = extraGpuZoom
      glProcessor.sharpnessAmount = if (requestedZoom > 1.3f) 0.78f else 0.45f

        binding.tvZoomReadout.text = String.format("%.1fx", requestedZoom)
        val prog = (((requestedZoom - 1.0f) / (maxZ - 1.0f)) * 100f).toInt().coerceIn(0, 100)
        binding.seekZoom.progress = prog
    }

    private fun bindCameraUseCases() {
        if (!hasRequiredPermissions()) return
        lifecycleScope.launch {
            try {
                val provider = ProcessCameraProvider.getInstance(this@MainActivity).awaitResult()
                cameraProvider = provider
                provider.unbindAll()

                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val preview = Preview.Builder()
                    .setTargetAspectRatio(aspectRatio)
                    .build().apply {
                        setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                val builder = UseCaseGroup.Builder().addUseCase(preview)
                binding.previewView.viewPort?.let { builder.setViewPort(it) }

                if (currentMode == CamMode.VIDEO) {
                    val qualitySelector = QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                    val recorder = Recorder.Builder()
                        .setQualitySelector(qualitySelector)
                        .build()

                    videoCapture = VideoCapture.Builder(recorder)
                        .setVideoStabilizationEnabled(stabilizationEnabled)
                        .build()
                    imageCapture = null

                    builder.addUseCase(videoCapture!!)val effect = object : CameraEffect(
                        PREVIEW or VIDEO_CAPTURE,
                        glProcessor.glExecutor,
                        glProcessor,
                        { err -> err.printStackTrace() }
                    ) {}
                    builder.addEffect(effect)
                } else {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetAspectRatio(aspectRatio)
                        .setFlashMode(
                            when (flashMode) {
                                FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                                FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                                FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                            }
                        )
                        .build()
                    videoCapture = null

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().apply {
                            setAnalyzer(analysisExecutor) { proxy ->
                                analyzeAmbientLight(proxy)
                                proxy.close()
                            }
                        }

                    builder.addUseCase(imageCapture!!)
                    builder.addUseCase(analysis)
                    val effect = object : CameraEffect(
                        PREVIEW,
                        glProcessor.glExecutor,
                        glProcessor,
                        { err -> err.printStackTrace() }
                    ) {}
                    builder.addEffect(effect)
                }

                camera = provider.bindToLifecycle(this@MainActivity, selector, builder.build())
                applyZoom(requestedZoom)
                if (currentMode == CamMode.PRO) applyProSettings()
            } catch (e: Exception) {
                binding.tvSubStatus.text = "Camera init error: ${e.localizedMessage}"
            }
        }
    }

    private fun analyzeAmbientLight(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastLumaCheckMs < 700L) return
        lastLumaCheckMs = now

        val yPlane = proxy.planes[0].buffer
        val step = 16
        var sum = 0L
        var count = 0
        var idx = 0
        val limit = yPlane.remaining()
        while (idx < limit) {
            sum += (yPlane.get(idx).toInt() and 0xFF)
            count++
            idx += step
        }
        val avgLuma = if (count > 0) (sum / count).toInt() else 128
        val low = avgLuma < 52
        if (low != isLowLightScene) {
            isLowLightScene = low
            runOnUiThread { updateAiBadge() }
        }
    }

    private fun triggerPhotoCaptureWithTimer() {
        if (isCapturing) return
        lifecycleScope.launch {
            if (timerSeconds > 0) {
                binding.tvStatusCenter.visibility = View.VISIBLE
                for (t in timerSeconds downTo 1) {
                    binding.tvStatusCenter.text = t.toString()
                    delay(1000L)
                }
                binding.tvStatusCenter.visibility = View.GONE
            }
            capturePhotoWithAi()
        }
    }

    private fun capturePhotoWithAi() {
        val imgCap = imageCapture ?: return
        val cam = camera ?: return
        isCapturing = true

        lifecycleScope.launch {
            try {
                // Front camera warm screen flash in dark scenes
                val useFrontFlash = lensFacing == CameraSelector.LENS_FACING_FRONT &&
                    (flashMode == FlashMode.ON || (flashMode == FlashMode.AUTO && isLowLightScene))
                if (useFrontFlash) {
                    binding.screenFlashOverlay.visibility = View.VISIBLE
                    delay(150L)
                }

                val useMultiNight = currentMode == CamMode.NIGHT ||
                    (currentMode == CamMode.PHOTO && isLowLightScene && !proManualEnabled)

                val capturedFrames = mutableListOf<Bitmap>()
                if (useMultiNight) {
                    binding.tvSubStatus.text = "🌙 Capturing Multi-Exposure Anti-Glare HDR..."
                    val evRange = cam.cameraInfo.exposureState.exposureCompensationRange
                    val darkEv = (evRange.lower * 0.70f).toInt().coerceIn(evRange.lower, 0)
                    val brightEv = (evRange.upper * 0.65f).toInt().coerceIn(0, evRange.upper)

                    cam.cameraControl.setExposureCompensationIndex(darkEv).awaitResult()
                    delay(90L)
                    capturedFrames.add(takeBitmapFrame(imgCap))

                    cam.cameraControl.setExposureCompensationIndex(0).awaitResult()
                    delay(70L)
                    capturedFrames.add(takeBitmapFrame(imgCap))

                    cam.cameraControl.setExposureCompensationIndex(brightEv).awaitResult()
                    delay(90L)
                    capturedFrames.add(takeBitmapFrame(imgCap))

                    cam.cameraControl.setExposureCompensationIndex(0).awaitResult()
                } else {
                    binding.tvSubStatus.text = "⚡ Capturing..."
                    capturedFrames.add(takeBitmapFrame(imgCap))
                }

                if (useFrontFlash) binding.screenFlashOverlay.visibility = View.GONE

                binding.tvSubStatus.text = "✨ Offline AI Enhancing (HDR + LUT + HD)..."
                val hwZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                val extraZoom = (requestedZoom / hwZoom.coerceAtLeast(1.0f)).coerceAtLeast(1.0f)

                val savedUri = AiPhotoEnhancer.processAndSave(
                    context = this@MainActivity,
                    frames = capturedFrames,
                    lutType = glProcessor.targetLut,
                    isFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
                    isPortraitMode = currentMode == CamMode.PORTRAIT,
                    isNightBoost = useMultiNight,
                    extraZoom = extraZoom,
                    skinProtect = glProcessor.skinToneProtection,
                    goldenGlow = glProcessor.goldenGlowAmount,
                    sharpness = glProcessor.sharpnessAmount
                )

                if (savedUri != null) {
                    lastSavedUri = savedUri
                    binding.ivGalleryThumb.setImageURI(savedUri)
                    binding.tvSubStatus.text = "✅ Saved Ultra-HD to DCIM/UltraCam-AI"
                } else {
                    binding.tvSubStatus.text = "❌ Save failed"
                }
            } catch (e: Exception) {
                binding.screenFlashOverlay.visibility = View.GONE
                binding.tvSubStatus.text = "Capture error: ${e.localizedMessage}"
            } finally {
                isCapturing = false
            }
        }
    }

    private suspend fun takeBitmapFrame(imgCap: ImageCapture): Bitmap =
        suspendCancellableCoroutine { cont ->
            imgCap.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val raw = image.toBitmap()
                        val rot = image.imageInfo.rotationDegrees
                        image.close()
                        val upright = if (rot != 0) {
                            val m = Matrix().apply { postRotate(rot.toFloat()) }
                            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            raw.recycle()
                            rotated
                        } else {
                            raw
                        }
                        if (cont.isActive) cont.resume(upright)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                }
            )
        }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val vc = videoCapture ?: return
        val currentRec = activeRecording
        if (currentRec != null) {
            currentRec.stop()
            activeRecording = null
            return
        }

        val name = "UltraCam_VID_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/UltraCam-AI")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, outputOptions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordSeconds = 0
                    updateShutterAppearance(isRecording = true)
                    mainHandler.postDelayed(recordTicker, 1000L)
                }
                is VideoRecordEvent.Finalize -> {
                    mainHandler.removeCallbacks(recordTicker)
                    updateShutterAppearance(isRecording = false)
                    activeRecording = null
                    if (!event.hasError()) {
                        lastSavedUri = event.outputResults.outputUri
                        binding.tvSubStatus.text = "✅ Video Saved with LUT + Stabilization!"
                    } else {
                        binding.tvSubStatus.text = "Video error: ${event.error}"
                    }
                }
            }
        }
    }

    private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
        suspendCancellableCoroutine { cont ->
            addListener({
                try {
                    if (cont.isActive) cont.resume(get())
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }

    override fun onResume() {
        super.onResume()
        gyroStabilizer.start()
    }

    override fun onPause() {
        super.onPause()
        gyroStabilizer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(recordTicker)
        analysisExecutor.shutdown()
        glProcessor.release()
    }
}
