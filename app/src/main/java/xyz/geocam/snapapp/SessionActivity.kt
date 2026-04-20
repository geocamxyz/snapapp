package xyz.geocam.snapapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
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

class SessionActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var locationHelper: LocationHelper
    private lateinit var sensorManager: SensorManager

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var sessionDb: SessionDb? = null
    private var sessionName: String? = null
    private var shotCount = 0
    private var capturing = false

    private var currentBearing = Float.NaN
    private var bearingAccuracyDeg = Float.NaN

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

        locationHelper = LocationHelper(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        checkPermissionsAndStart()

        binding.buttonShutter.setOnClickListener {
            if (!capturing) captureShot()
        }

        binding.buttonCloseSession.setOnClickListener {
            confirmCloseSession()
        }
    }

    override fun onResume() {
        super.onResume()
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationHelper.stopUpdates()
        sessionDb?.close()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        currentBearing = (azimuth + 360f) % 360f
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
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture!!
                )
                binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER
            } catch (e: Exception) {
                Toast.makeText(this, "Camera init failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureShot() {
        val cam = camera ?: return
        val ic = imageCapture ?: return

        lifecycleScope.launch {
            capturing = true
            binding.buttonShutter.isEnabled = false
            binding.progressCapture.visibility = View.VISIBLE

            try {
                if (sessionDb == null) {
                    val name = buildSessionName()
                    sessionName = name
                    File(filesDir, name).mkdirs()
                    val dbFile = File(filesDir, "$name.db")
                    sessionDb = SessionDb.create(dbFile)
                    sessionDb!!.setMeta("session_start", System.currentTimeMillis().toString())
                    sessionDb!!.setMeta("app_version", BuildConfig.VERSION_NAME)
                }

                val captureZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val halfZoom = 1f + (captureZoom - 1f) * 0.5f
                val ts = System.currentTimeMillis()
                val dir = File(filesDir, sessionName!!)

                binding.textCaptureStatus.text = "Capturing zoom…"
                val zoomPath = takePicture(ic, File(dir, "shot_${ts}_zoom.jpg"))

                cam.cameraControl.setZoomRatio(halfZoom).await()
                delay(600)
                binding.textCaptureStatus.text = "Capturing mid…"
                val midPath = takePicture(ic, File(dir, "shot_${ts}_mid.jpg"))

                cam.cameraControl.setZoomRatio(1f).await()
                delay(600)
                binding.textCaptureStatus.text = "Capturing wide…"
                val widePath = takePicture(ic, File(dir, "shot_${ts}_wide.jpg"))

                cam.cameraControl.setZoomRatio(captureZoom).await()

                val loc = locationHelper.current ?: locationHelper.getLastKnown()
                val bearing = if (currentBearing.isNaN()) null else currentBearing
                val bearingAcc = if (bearingAccuracyDeg.isNaN()) null else bearingAccuracyDeg

                sessionDb!!.insertShot(
                    capturedAt = ts,
                    lat = loc?.latitude, lon = loc?.longitude,
                    accuracyM = loc?.accuracyM, altitudeM = loc?.altitudeM,
                    locationSource = loc?.source, locationTimeMs = loc?.timeMs,
                    bearingDeg = bearing, bearingAccuracyDeg = bearingAcc,
                    zoomJpegPath = zoomPath,
                    midJpegPath = midPath,
                    wideJpegPath = widePath
                )
                shotCount = sessionDb!!.getShotCount()
                binding.textShotCount.text = "Shots: $shotCount"

            } catch (e: Exception) {
                Toast.makeText(this@SessionActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressCapture.visibility = View.GONE
                binding.textCaptureStatus.text = ""
                binding.buttonShutter.isEnabled = true
                capturing = false
            }
        }
    }

    private suspend fun takePicture(ic: ImageCapture, file: File): String =
        suspendCancellableCoroutine { cont ->
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            ic.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(file.absolutePath)
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

    private fun confirmCloseSession() {
        if (sessionDb == null) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Close session")
            .setMessage("Close this session with $shotCount shot(s)?")
            .setPositiveButton("Close") { _, _ ->
                sessionDb?.close()
                sessionDb = null
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
