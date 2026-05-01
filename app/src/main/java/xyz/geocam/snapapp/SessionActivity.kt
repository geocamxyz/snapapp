package xyz.geocam.snapapp

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.geocam.snapapp.databinding.ActivitySessionBinding
import xyz.geocam.snapapp.db.SessionDb
import xyz.geocam.snapapp.location.LocationHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class SessionActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var locationHelper: LocationHelper
    private lateinit var sensorManager: SensorManager
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var shutterSound: MediaActionSound

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var sessionDb: SessionDb? = null
    private var sessionName: String? = null
    private var shotCount = 0
    private var capturing = false
    private var sliderTracking = false
    private var guideAnimRunning = false
    private var calibrationJob: Job? = null
    private var calibrationCount = 0
    private var rapidJob: Job? = null
    private var rapidCount = 0

    private var currentBearing = Float.NaN
    private var bearingAccuracyDeg = Float.NaN

    companion object {
        const val BURST_COUNT = 3
        const val BURST_INTERVAL_MS = 500L
        const val MIN_SHOTS = 2
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationHelper.startUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shutterSound = MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
        locationHelper = LocationHelper(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val state = cam.cameraInfo.zoomState.value ?: return false
                val newRatio = (state.zoomRatio * detector.scaleFactor)
                    .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                cam.cameraControl.setZoomRatio(newRatio)
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            v.performClick()
            false
        }

        binding.seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val cam = camera ?: return
                val state = cam.cameraInfo.zoomState.value ?: return
                val ratio = state.minZoomRatio +
                    (progress.toFloat() / seek.max) * (state.maxZoomRatio - state.minZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
            }
            override fun onStartTrackingTouch(seek: SeekBar) { sliderTracking = true }
            override fun onStopTrackingTouch(seek: SeekBar) { sliderTracking = false }
        })

        binding.buttonCapture.setOnClickListener {
            if (!capturing) captureShot()
        }
        binding.buttonWideScan.setOnClickListener {
            if (calibrationJob == null) startCalibration() else stopCalibration()
        }
        binding.buttonRapid.setOnClickListener {
            if (rapidJob == null) startRapidFire() else stopRapidFire()
        }
        binding.buttonCloseSession.setOnClickListener { confirmCloseSession() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmCloseSession() }
        })

        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release()
        locationHelper.stopUpdates()
        sessionDb?.close()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orientation)
        currentBearing = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
        binding.textBearing.text = "%.0f°".format(currentBearing)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        bearingAccuracyDeg = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 5f
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 15f
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 45f
            else -> Float.NaN
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isEmpty()) {
            startCamera()
            locationHelper.startUpdates()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.viewFinder.surfaceProvider
            }
            val quality = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("jpeg_quality", SettingsActivity.DEFAULT_JPEG_QUALITY)
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(quality)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture!!
                )
                binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER
                observeZoomState()
                showGuideOverlay(moveReminder = false)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeZoomState() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            val ratio = state.zoomRatio
            binding.textZoomLevel.text = "%.1f×".format(ratio)
            if (!sliderTracking) {
                val range = state.maxZoomRatio - state.minZoomRatio
                val progress = if (range > 0f)
                    ((ratio - state.minZoomRatio) / range * binding.seekZoom.max).roundToInt()
                else 0
                binding.seekZoom.progress = progress
            }
        }
    }

    private fun showGuideOverlay(moveReminder: Boolean) {
        if (guideAnimRunning) return
        guideAnimRunning = true

        val w = resources.displayMetrics.widthPixels.toFloat()
        val startX = w * 0.38f

        binding.guideArm.translationX = startX
        binding.guideText.text = getString(
            if (moveReminder) R.string.guide_move_reminder else R.string.guide_instruction
        )
        binding.guideOverlay.alpha = 1f
        binding.guideOverlay.visibility = View.VISIBLE

        val sweep = ObjectAnimator.ofFloat(binding.guideArm, View.TRANSLATION_X, startX, -startX).apply {
            duration = 2200
            interpolator = AccelerateDecelerateInterpolator()
        }
        sweep.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                binding.guideText.text = getString(R.string.guide_hold)
                binding.guideOverlay.postDelayed({
                    binding.guideOverlay.animate()
                        .alpha(0f).setDuration(700)
                        .withEndAction {
                            binding.guideOverlay.visibility = View.GONE
                            guideAnimRunning = false
                        }.start()
                }, 1200)
            }
        })
        sweep.start()

        binding.guideOverlay.setOnClickListener {
            sweep.cancel()
            binding.guideOverlay.animate().cancel()
            binding.guideOverlay.visibility = View.GONE
            guideAnimRunning = false
        }
    }

    private fun captureShot() {
        val cam = camera ?: return
        val ic = imageCapture ?: return

        lifecycleScope.launch {
            capturing = true
            binding.buttonCapture.isEnabled = false
            binding.progressCapture.visibility = View.VISIBLE

            try {
                ensureSessionDb()

                val captureZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val halfZoom = 1f + (captureZoom - 1f) * 0.5f
                val ts = System.currentTimeMillis()
                val tmp = cacheDir

                // Wide-pre: zoom out to 1× first
                cam.cameraControl.setZoomRatio(1f).await()
                delay(500)
                binding.textCaptureStatus.text = "Capturing wide…"
                val widePreFile = File(tmp, "snap_wide_pre.jpg")
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                takePicture(ic, widePreFile)

                // Zoom back to user level for burst
                cam.cameraControl.setZoomRatio(captureZoom).await()
                delay(500)

                val burstFrames = mutableListOf<ByteArray>()
                repeat(BURST_COUNT) { i ->
                    binding.textCaptureStatus.text = "Burst ${i + 1}/$BURST_COUNT…"
                    val f = File(tmp, "snap_burst_$i.jpg")
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    takePicture(ic, f)
                    burstFrames.add(f.readBytes().also { f.delete() })
                    if (i < BURST_COUNT - 1) delay(BURST_INTERVAL_MS)
                }

                cam.cameraControl.setZoomRatio(halfZoom).await()
                delay(500)
                binding.textCaptureStatus.text = "Capturing mid…"
                val midFile = File(tmp, "snap_mid.jpg")
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                takePicture(ic, midFile)

                cam.cameraControl.setZoomRatio(1f).await()
                delay(500)
                binding.textCaptureStatus.text = "Capturing wide…"
                val wideFile = File(tmp, "snap_wide.jpg")
                shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                takePicture(ic, wideFile)

                cam.cameraControl.setZoomRatio(captureZoom).await()

                val widePreBytes = widePreFile.readBytes().also { widePreFile.delete() }
                val midBytes  = midFile.readBytes().also { midFile.delete() }
                val wideBytes = wideFile.readBytes().also { wideFile.delete() }

                val loc = locationHelper.current ?: locationHelper.getLastKnown()
                val bearing = if (currentBearing.isNaN()) null else currentBearing
                val bearingAcc = if (bearingAccuracyDeg.isNaN()) null else bearingAccuracyDeg

                sessionDb!!.insertShot(
                    capturedAt = ts,
                    lat = loc?.latitude, lon = loc?.longitude,
                    accuracyM = loc?.accuracyM, altitudeM = loc?.altitudeM,
                    locationSource = loc?.source, locationTimeMs = loc?.timeMs,
                    bearingDeg = bearing, bearingAccuracyDeg = bearingAcc,
                    zoomRatio = captureZoom,
                    widePrejpeg = widePreBytes,
                    burstFrames = burstFrames,
                    midJpeg = midBytes,
                    wideJpeg = wideBytes
                )
                shotCount = sessionDb!!.getShotCount()
                binding.textShotCount.text = "Shots: $shotCount"

                if (shotCount < MIN_SHOTS) {
                    showGuideOverlay(moveReminder = true)
                }

            } catch (e: Exception) {
                Toast.makeText(this@SessionActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressCapture.visibility = View.GONE
                binding.textCaptureStatus.text = ""
                binding.buttonCapture.isEnabled = true
                capturing = false
            }
        }
    }

    private suspend fun takePicture(ic: ImageCapture, file: File): Unit =
        suspendCancellableCoroutine { cont ->
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            ic.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(Unit)
                    }
                    override fun onError(exc: ImageCaptureException) {
                        cont.resumeWithException(exc)
                    }
                }
            )
        }

    private suspend fun buildSessionName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val loc = locationHelper.current ?: locationHelper.getLastKnown()
            ?: return "${date}_unknown"

        val street = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        Geocoder(this@SessionActivity).getFromLocation(
                            loc.latitude, loc.longitude, 1
                        ) { addresses -> cont.resume(addresses.firstOrNull()?.thoroughfare) }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    Geocoder(this@SessionActivity)
                        .getFromLocation(loc.latitude, loc.longitude, 1)
                        ?.firstOrNull()?.thoroughfare
                }
            } catch (e: Exception) { null }
        }

        val slug = street?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "-")
            ?.trim('-')
            ?: "loc-${loc.latitude.toInt()}-${loc.longitude.toInt()}"

        val base = "${date}_${slug}"
        var name = base
        var n = 2
        while (File(filesDir, "$name.db").exists()) { name = "${base}_$n"; n++ }
        return name
    }

    private fun startCalibration() {
        val cam = camera ?: return
        val ic  = imageCapture ?: return
        calibrationCount = 0

        // Alternate between 1× (wide) and the user's current zoom (min 2×)
        val midZoom = maxOf(cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f, 2f)

        Toast.makeText(this, getString(R.string.calibration_guide), Toast.LENGTH_LONG).show()

        calibrationJob = lifecycleScope.launch {
            val savedZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            try {
                ensureSessionDb()
                sessionDb!!.setMeta("session_type", "calibration")

                binding.buttonCapture.isEnabled = false
                binding.buttonWideScan.setTextColor(
                    ContextCompat.getColor(this@SessionActivity, R.color.status_error))

                var wideNext = true
                while (true) {
                    val targetZoom = if (wideNext) 1f else midZoom
                    cam.cameraControl.setZoomRatio(targetZoom).await()
                    delay(200) // let lens settle

                    val ts = System.currentTimeMillis()
                    val f  = File(cacheDir, "cal_tmp.jpg")
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    takePicture(ic, f)
                    val jpeg = f.readBytes().also { f.delete() }

                    val loc = locationHelper.current ?: locationHelper.getLastKnown()
                    sessionDb!!.insertCalibrationShot(ts, loc?.latitude, loc?.longitude, jpeg, targetZoom)

                    calibrationCount++
                    binding.buttonWideScan.text = getString(R.string.calibration_stop, calibrationCount)
                    binding.textScanCount.text = "Cal: $calibrationCount"
                    binding.textScanCount.visibility = View.VISIBLE

                    wideNext = !wideNext
                    delay(300) // 200ms settle + 300ms = ~500ms between shots
                }
            } finally {
                withContext(NonCancellable) {
                    cam.cameraControl.setZoomRatio(savedZoom).await()
                }
                withContext(Dispatchers.Main) {
                    binding.buttonWideScan.text = getString(R.string.calibration_mode)
                    binding.buttonWideScan.setTextColor(
                        ContextCompat.getColor(this@SessionActivity, android.R.color.white))
                    binding.buttonCapture.isEnabled = !capturing
                    calibrationJob = null
                }
            }
        }
    }

    private fun stopCalibration() {
        calibrationJob?.cancel()
    }

    private fun startRapidFire() {
        val cam = camera ?: return
        val ic  = imageCapture ?: return
        rapidCount = 0

        val captureZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val midZoom = 1f + (captureZoom - 1f) * 0.5f
        val zoomCycle = listOf(1f, midZoom, captureZoom)

        calibrationJob?.cancel()  // can't run both simultaneously

        rapidJob = lifecycleScope.launch {
            val savedZoom = captureZoom
            try {
                ensureSessionDb()
                sessionDb!!.setMeta("session_type", "rapid_fire")

                binding.buttonCapture.isEnabled = false
                binding.buttonWideScan.isEnabled = false
                binding.buttonRapid.setTextColor(
                    ContextCompat.getColor(this@SessionActivity, R.color.status_error))

                var idx = 0
                while (true) {
                    val targetZoom = zoomCycle[idx % 3]
                    cam.cameraControl.setZoomRatio(targetZoom).await()

                    val ts = System.currentTimeMillis()
                    val f  = File(cacheDir, "rapid_tmp.jpg")
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    takePicture(ic, f)
                    val jpeg = f.readBytes().also { f.delete() }

                    val loc = locationHelper.current ?: locationHelper.getLastKnown()
                    sessionDb!!.insertCalibrationShot(ts, loc?.latitude, loc?.longitude, jpeg, targetZoom)

                    rapidCount++
                    binding.buttonRapid.text = "■ $rapidCount"

                    idx++
                }
            } finally {
                withContext(NonCancellable) {
                    cam.cameraControl.setZoomRatio(savedZoom).await()
                }
                withContext(Dispatchers.Main) {
                    binding.buttonRapid.text = getString(R.string.rapid_fire)
                    binding.buttonRapid.setTextColor(
                        ContextCompat.getColor(this@SessionActivity, android.R.color.white))
                    binding.buttonCapture.isEnabled = !capturing
                    binding.buttonWideScan.isEnabled = true
                    rapidJob = null
                }
            }
        }
    }

    private fun stopRapidFire() {
        rapidJob?.cancel()
    }

    private suspend fun ensureSessionDb() {
        if (sessionDb != null) return
        val name = buildSessionName()
        sessionName = name
        val dbFile = File(filesDir, "$name.db")
        sessionDb = SessionDb.create(dbFile)
        sessionDb!!.setMeta("session_start", System.currentTimeMillis().toString())
        sessionDb!!.setMeta("app_version", BuildConfig.VERSION_NAME)
    }

    private fun confirmCloseSession() {
        if (sessionDb == null) { finish(); return }

        val scanLine = if (calibrationCount > 0) "\n$calibrationCount calibration frame(s)" else ""
        val hint = if (shotCount < MIN_SHOTS)
            "\n\nTip: take at least $MIN_SHOTS burst shots from different positions for triangulation." else ""

        AlertDialog.Builder(this)
            .setTitle("Close session")
            .setMessage("Close this session?\n$shotCount burst shot(s)$scanLine$hint")
            .setPositiveButton("Close") { _, _ ->
                stopCalibration()
                stopRapidFire()
                sessionDb?.close()
                sessionDb = null
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
