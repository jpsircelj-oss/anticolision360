package com.anticolision360.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var startup: TextView

    private val detectorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var detector: DetectorEngine? = null
    private val tracker = MotionTracker()
    private val riskEngine = RiskEngine()
    @Volatile private var speedKmh: Float? = null
    @Volatile private var latestTracks: List<TrackedObject> = emptyList()
    private var lastAnalysis = 0L
    private var locationManager: LocationManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startNativeSystem()
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                startLocation()
            }
        } else {
            startup.text = "AntiColisión 360 necesita permiso de cámara para funcionar."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        buildUi()
        requestPermissionsIfNeeded()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 8, 13)) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.rgb(5, 8, 13))
        }
        overlay = OverlayView(this)
        startup = TextView(this).apply {
            text = "ANTI COLISIÓN 360\nPreparando motor nativo…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 8, 13))
            alpha = 1f
        }

        root.addView(previewView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(startup, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            startNativeSystem()
            if (locationGranted) startLocation()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun startNativeSystem() {
        startup.text = "ANTI COLISIÓN 360\nCargando IA de visión…"
        detectorExecutor.execute {
            try {
                detector = DetectorEngine(applicationContext)
                runOnUiThread {
                    bindCamera()
                    startup.animate().alpha(0f).setDuration(420).withEndAction {
                        startup.visibility = android.view.View.GONE
                    }.start()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    startup.text = "No pudo iniciar la IA nativa.\n${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()

                analysis.setAnalyzer(detectorExecutor) { image ->
                    val now = System.currentTimeMillis()
                    if (now - lastAnalysis < 90L) {
                        image.close()
                        return@setAnalyzer
                    }
                    lastAnalysis = now
                    try {
                        val raw = detector?.detect(image).orEmpty()
                        val moving = tracker.update(raw, now)
                        latestTracks = moving
                        val state = riskEngine.evaluate(moving, speedKmh, now)
                        runOnUiThread { overlay.update(moving, state, true) }
                    } catch (_: Throwable) {
                        val state = riskEngine.evaluate(latestTracks, speedKmh, now)
                        runOnUiThread { overlay.update(latestTracks, state, detector != null) }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                startup.visibility = android.view.View.VISIBLE
                startup.alpha = 1f
                startup.text = "No pudo abrir la cámara trasera.\n${t.message ?: t.javaClass.simpleName}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    250L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            } else {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    500L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Throwable) { }
    }

    override fun onLocationChanged(location: Location) {
        speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else speedKmh
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    override fun onDestroy() {
        try { locationManager?.removeUpdates(this) } catch (_: Throwable) { }
        detectorExecutor.shutdownNow()
        super.onDestroy()
    }
}
