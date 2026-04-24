package xyz.geocam.snapapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import xyz.geocam.snapapp.location.LocationHelper
import xyz.geocam.snapapp.recognition.FeatureExtractor
import xyz.geocam.snapapp.recognition.MatchResult
import xyz.geocam.snapapp.recognition.ShardManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RecognitionActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: MatchOverlayView
    private lateinit var mapView: MapView
    private lateinit var textStatus: TextView
    private lateinit var textShard: TextView

    private lateinit var locationHelper: LocationHelper
    private lateinit var shardManager: ShardManager
    private lateinit var featureExtractor: FeatureExtractor

    private val inferScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inferring  = AtomicBoolean(false)

    private var phoneMarker: Marker? = null
    private var matchMarker: Marker? = null
    private var lastMatch: MatchResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        previewView = findViewById(R.id.recogPreview)
        overlayView = findViewById(R.id.recogOverlay)
        mapView     = findViewById(R.id.recogMap)
        textStatus  = findViewById(R.id.textRecogStatus)
        textShard   = findViewById(R.id.textShardName)

        Configuration.getInstance().apply {
            load(this@RecognitionActivity,
                android.preference.PreferenceManager.getDefaultSharedPreferences(this@RecognitionActivity))
            osmdroidTileCache = File(cacheDir, "osm_tiles")
        }
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)

        locationHelper = LocationHelper(this)
        shardManager   = ShardManager(this)

        val modelExists = try { assets.open(FeatureExtractor.MODEL_ASSET).close(); true }
                          catch (_: Exception) { false }

        if (!modelExists) {
            textStatus.text = "megaloc.tflite not found in assets"
            textStatus.visibility = View.VISIBLE
        } else {
            featureExtractor = FeatureExtractor(this)
        }

        updateShardLabel()

        findViewById<android.widget.Button>(R.id.buttonRecogBack).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        if (shardManager.shardCount == 0) {
            textStatus.text = "No shards in files/recognition/ — load shards first"
            textStatus.visibility = View.VISIBLE
        }

        checkPermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        locationHelper.startUpdates(onLocation = { loc ->
            shardManager.updateLocation(loc.latitude, loc.longitude)
            updateShardLabel()
            updateMapPhone(loc.latitude, loc.longitude)
        })
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationHelper.stopUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        inferScope.cancel()
        shardManager.close()
        if (::featureExtractor.isInitialized) featureExtractor.close()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this), ::analyzeFrame)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!::featureExtractor.isInitialized || inferring.getAndSet(true)) {
            imageProxy.close()
            return
        }
        val bitmap: Bitmap = imageProxy.toBitmap()
        imageProxy.close()

        inferScope.launch {
            try {
                val embedding = featureExtractor.extract(bitmap)
                val result    = shardManager.search(embedding)
                withContext(Dispatchers.Main) { onMatchResult(result) }
            } finally {
                inferring.set(false)
            }
        }
    }

    private fun onMatchResult(result: MatchResult?) {
        overlayView.setMatch(result?.score)
        lastMatch = result
        if (result != null) {
            updateMapMatch(result.lat, result.lon)
            mapView.visibility = View.VISIBLE
        } else if (lastMatch == null) {
            mapView.visibility = View.GONE
        }
    }

    private fun updateMapPhone(lat: Double, lon: Double) {
        val gp = GeoPoint(lat, lon)
        mapView.controller.setCenter(gp)
        if (phoneMarker == null) {
            phoneMarker = Marker(mapView).apply {
                title = "You"
                mapView.overlays.add(this)
            }
        }
        phoneMarker!!.position = gp
        mapView.invalidate()
    }

    private fun updateMapMatch(lat: Double, lon: Double) {
        if (matchMarker == null) {
            matchMarker = Marker(mapView).apply {
                title = "Match"
                mapView.overlays.add(this)
            }
        }
        matchMarker!!.position = GeoPoint(lat, lon)
        mapView.invalidate()
    }

    private fun updateShardLabel() {
        val name = shardManager.activeShardName
        textShard.text = if (name != null) "Shard: $name" else "No shard for this location"
    }
}
