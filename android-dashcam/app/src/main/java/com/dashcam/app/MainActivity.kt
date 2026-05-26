package com.dashcam.app

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DashCam"
        private const val REQUEST_PERMISSIONS = 1001
        private const val HIDE_DELAY_MS = 3000L

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    // ── UI ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView:  SurfaceView
    private lateinit var tvLocation:   TextView
    private lateinit var tvAddress:    TextView
    private lateinit var tvSpeed:      TextView
    private lateinit var tvDateTime:   TextView
    private lateinit var btnRecord:    ImageButton
    private lateinit var btnExit:      ImageButton
    private lateinit var tvRecording:  TextView

    // ── Recorder ──────────────────────────────────────────────────────────
    private var overlayRecorder: OverlayVideoRecorder? = null
    private var isRecording = false
    private val videoSize = Size(1920, 1080)

    // ── Blink animator ─────────────────────────────────────────────────────
    private var blinkAnimator: ObjectAnimator? = null

    // ── Overlay values (kept for recorder handoff on start) ────────────────
    private var lastOverlayLocation = ""
    private var lastOverlayAddress  = ""
    private var lastOverlaySpeed    = "0 km/h"

    // ── Location ───────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastGeocodedLocation: Location? = null
    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    // ── UI hide / time update ──────────────────────────────────────────────
    private val uiHandler  = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

    private val timeRunnable = object : Runnable {
        override fun run() {
            tvDateTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            uiHandler.postDelayed(this, 1000)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        setContentView(R.layout.activity_main)

        surfaceView  = findViewById(R.id.surfaceView)
        tvLocation   = findViewById(R.id.tvLocation)
        tvAddress    = findViewById(R.id.tvAddress)
        tvSpeed      = findViewById(R.id.tvSpeed)
        tvDateTime   = findViewById(R.id.tvDateTime)
        btnRecord    = findViewById(R.id.btnRecord)
        btnExit      = findViewById(R.id.btnExit)
        tvRecording  = findViewById(R.id.tvRecording)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        btnExit.setOnClickListener {
            if (isRecording) stopRecording()
            finishAndRemoveTask()
        }
        surfaceView.setOnClickListener { showControls() }

        // SurfaceHolder callback — start/stop the recorder with the surface lifecycle
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (checkPermissions()) initPreview(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                overlayRecorder?.release()
                overlayRecorder = null
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
        })

        if (!checkPermissions()) requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
        uiHandler.post(timeRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(timeRunnable)
        uiHandler.removeCallbacks(hideRunnable)
        stopLocationUpdates()
        if (isRecording) stopRecording()
        super.onPause()
    }

    override fun onDestroy() {
        overlayRecorder?.release()
        overlayRecorder = null
        geocoderExecutor.shutdown()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun checkPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && checkPermissions()) {
            surfaceView.holder.surface?.let { initPreview(it) }
            startLocationUpdates()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            Toast.makeText(this, "카메라, 오디오, 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Preview (GL pipeline always running while surface is alive)
    // ══════════════════════════════════════════════════════════════════════

    private fun initPreview(surface: android.view.Surface) {
        if (overlayRecorder != null) return   // already initialized
        val recorder = OverlayVideoRecorder(this, videoSize).also {
            it.overlayLocation = lastOverlayLocation
            it.overlayAddress  = lastOverlayAddress
            it.overlaySpeed    = lastOverlaySpeed
        }
        overlayRecorder = recorder
        // 'this' is a LifecycleOwner (AppCompatActivity implements it)
        recorder.prepare(surface, this) {
            // fired on main thread when CameraX is bound and first frame is flowing
            Log.d(TAG, "GL preview ready")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recording
    // ══════════════════════════════════════════════════════════════════════

    private fun startRecording() {
        val recorder = overlayRecorder ?: run {
            Toast.makeText(this, "카메라 준비 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        recorder.startRecording()
        isRecording = true
        updateRecordingUI()
        scheduleHideControls()
    }

    private fun stopRecording() {
        isRecording = false
        overlayRecorder?.stopRecording {
            // main thread
            stopBlinking()
            updateRecordingUI()
            showControls()
            Toast.makeText(this, "영상이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun updateRecordingUI() {
        if (isRecording) {
            btnRecord.setImageResource(R.drawable.ic_stop)
            tvRecording.visibility = View.VISIBLE
            startBlinking()
        } else {
            btnRecord.setImageResource(R.drawable.ic_record)
            tvRecording.visibility = View.GONE
        }
    }

    private fun showControls() {
        btnRecord.visibility = View.VISIBLE
        btnExit.visibility   = View.VISIBLE
        uiHandler.removeCallbacks(hideRunnable)
        if (isRecording) scheduleHideControls()
    }

    private fun hideControls() {
        if (isRecording) {
            btnRecord.visibility = View.GONE
            btnExit.visibility   = View.GONE
        }
    }

    private fun scheduleHideControls() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun startBlinking() {
        blinkAnimator = ObjectAnimator.ofFloat(tvRecording, "alpha", 1f, 0.1f).apply {
            duration    = 600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode  = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopBlinking() {
        blinkAnimator?.cancel(); blinkAnimator = null
        tvRecording.alpha = 1f
    }

    // ══════════════════════════════════════════════════════════════════════
    // Location
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!checkPermissions()) return
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateLocationUI(it) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun updateLocationUI(location: Location) {
        val lat = String.format("%.5f°", location.latitude)
        val lon = String.format("%.5f°", location.longitude)
        tvLocation.text = "$lat\n$lon"

        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toInt() else 0
        tvSpeed.text = "$speedKmh\nkm/h"

        lastOverlayLocation = "$lat\n$lon"
        lastOverlaySpeed    = "$speedKmh km/h"
        overlayRecorder?.overlayLocation = lastOverlayLocation
        overlayRecorder?.overlaySpeed    = lastOverlaySpeed

        fetchAddress(location)
    }

    private fun fetchAddress(location: Location) {
        if (lastGeocodedLocation != null && location.distanceTo(lastGeocodedLocation!!) < 50f) return
        lastGeocodedLocation = location
        if (!Geocoder.isPresent()) return

        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                val text = formatAddress(addresses.firstOrNull())
                runOnUiThread {
                    tvAddress.text = text
                    lastOverlayAddress = text
                    overlayRecorder?.overlayAddress = text
                }
            }
        } else {
            geocoderExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    val text = formatAddress(addresses?.firstOrNull())
                    runOnUiThread {
                        tvAddress.text = text
                        lastOverlayAddress = text
                        overlayRecorder?.overlayAddress = text
                    }
                } catch (e: Exception) { Log.e(TAG, "Geocoder error", e) }
            }
        }
    }

    private fun formatAddress(address: android.location.Address?): String {
        if (address == null) return ""
        val parts = mutableListOf<String>()
        address.adminArea?.let { parts.add(it) }
        address.subAdminArea?.takeIf { it != address.adminArea }?.let { parts.add(it) }
        address.thoroughfare?.let { parts.add(it) }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return address.getAddressLine(0)?.take(50) ?: ""
    }
}
