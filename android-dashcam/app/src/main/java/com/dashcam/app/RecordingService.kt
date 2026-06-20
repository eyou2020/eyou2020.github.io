package com.dashcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.Color
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service hosting the camera/GL/encoder pipeline ([OverlayVideoRecorder])
 * and GPS overlay updates. Runs independently of MainActivity's lifecycle so that
 * recording — and the live GPS/speed/address/datetime overlay baked into it —
 * continues while the app is backgrounded.
 *
 * MainActivity binds to this service to attach/detach its SurfaceView for live
 * preview and to control recording, but the service itself is started
 * (startForegroundService) so it outlives the binding.
 */
class RecordingService : LifecycleService() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "dashcam_recording"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS_NAME = "dashcam_settings"
        private const val KEY_SEGMENT_MINUTES   = "segment_minutes"
        private const val DEFAULT_SEGMENT_MINUTES = 15

        private const val KEY_DAYTIME_ENABLED   = "daytime_enabled"
        private const val KEY_DAYTIME_FROM_HOUR = "daytime_from_hour"
        private const val KEY_DAYTIME_FROM_MIN  = "daytime_from_min"
        private const val KEY_DAYTIME_TO_HOUR   = "daytime_to_hour"
        private const val KEY_DAYTIME_TO_MIN    = "daytime_to_min"

        private val VIDEO_SIZE = Size(1920, 1080)
    }

    inner class RecordingBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }
    private val binder = RecordingBinder()

    private var overlayRecorder: OverlayVideoRecorder? = null
    val isRecording: Boolean get() = overlayRecorder?.isRecording ?: false

    private var recordingStateListener: ((Boolean) -> Unit)? = null
    fun setRecordingStateListener(listener: ((Boolean) -> Unit)?) {
        recordingStateListener = listener
    }

    // ── Daytime text-color settings ──────────────────────────────────────
    private var daytimeEnabled  = false
    private var daytimeFromHour = 6
    private var daytimeFromMin  = 0
    private var daytimeToHour   = 20
    private var daytimeToMin    = 0
    @Volatile private var manualColorOverride = false

    // ── Location / overlay updates ───────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastGeocodedLocation: Location? = null
    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        overlayRecorder = OverlayVideoRecorder(this, VIDEO_SIZE).also {
            it.segmentDurationMinutes = loadSegmentMinutes()
        }
        overlayRecorder?.prepare(this) {
            Log.d(TAG, "Camera/GL pipeline ready")
        }

        loadDaytimeSettings()
        applyDaytimeColor()

        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stopLocationUpdates()
        geocoderExecutor.shutdown()
        overlayRecorder?.release()
        overlayRecorder = null
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Display surface (attached/detached by MainActivity's SurfaceView)
    // ══════════════════════════════════════════════════════════════════════

    fun attachDisplaySurface(surface: Surface) {
        overlayRecorder?.attachDisplaySurface(surface)
    }

    fun detachDisplaySurface() {
        overlayRecorder?.detachDisplaySurface()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recording control
    // ══════════════════════════════════════════════════════════════════════

    fun startRecording() {
        overlayRecorder?.startRecording()
        recordingStateListener?.invoke(true)
        updateNotification(recording = true)
    }

    fun stopRecording(onDone: () -> Unit) {
        overlayRecorder?.stopRecording {
            recordingStateListener?.invoke(false)
            updateNotification(recording = false)
            onDone()
        }
    }

    fun saveEventSegment(onDone: () -> Unit) {
        overlayRecorder?.saveEventSegment(onDone)
    }

    fun toggleTextColor() {
        manualColorOverride = true
        overlayRecorder?.toggleTextColor()
    }

    fun setDaytimeSettings(enabled: Boolean, fromHour: Int, fromMin: Int, toHour: Int, toMin: Int) {
        daytimeEnabled  = enabled
        daytimeFromHour = fromHour
        daytimeFromMin  = fromMin
        daytimeToHour   = toHour
        daytimeToMin    = toMin
        manualColorOverride = false
        prefs().edit()
            .putBoolean(KEY_DAYTIME_ENABLED,   enabled)
            .putInt(KEY_DAYTIME_FROM_HOUR, fromHour)
            .putInt(KEY_DAYTIME_FROM_MIN,  fromMin)
            .putInt(KEY_DAYTIME_TO_HOUR,   toHour)
            .putInt(KEY_DAYTIME_TO_MIN,    toMin)
            .apply()
        applyDaytimeColor()
    }

    fun getDaytimeSettings(): IntArray = intArrayOf(
        if (daytimeEnabled) 1 else 0,
        daytimeFromHour, daytimeFromMin,
        daytimeToHour,   daytimeToMin
    )

    fun setSegmentDurationMinutes(minutes: Int) {
        overlayRecorder?.segmentDurationMinutes = minutes
        prefs().edit().putInt(KEY_SEGMENT_MINUTES, minutes).apply()
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadSegmentMinutes(): Int =
        prefs().getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)

    private fun loadDaytimeSettings() {
        val p = prefs()
        daytimeEnabled  = p.getBoolean(KEY_DAYTIME_ENABLED,   false)
        daytimeFromHour = p.getInt(KEY_DAYTIME_FROM_HOUR, 6)
        daytimeFromMin  = p.getInt(KEY_DAYTIME_FROM_MIN,  0)
        daytimeToHour   = p.getInt(KEY_DAYTIME_TO_HOUR,  20)
        daytimeToMin    = p.getInt(KEY_DAYTIME_TO_MIN,    0)
    }

    private fun applyDaytimeColor() {
        if (!daytimeEnabled || manualColorOverride) return
        val cal  = Calendar.getInstance()
        val now  = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val from = daytimeFromHour * 60 + daytimeFromMin
        val to   = daytimeToHour   * 60 + daytimeToMin
        val inDaytime = if (from <= to) now in from..to else now >= from || now <= to
        overlayRecorder?.overlayTextColor = if (inDaytime) Color.WHITE else Color.BLACK
    }

    // ══════════════════════════════════════════════════════════════════════
    // Location → overlay
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateOverlayLocation(it) }
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

    private fun updateOverlayLocation(location: Location) {
        val lat = String.format("%.5f°", location.latitude)
        val lon = String.format("%.5f°", location.longitude)
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toInt() else 0

        overlayRecorder?.overlayLocation = "$lat\n$lon"
        overlayRecorder?.overlaySpeed    = "$speedKmh km/h"

        fetchAddress(location)
        applyDaytimeColor()
    }

    private fun fetchAddress(location: Location) {
        if (lastGeocodedLocation != null && location.distanceTo(lastGeocodedLocation!!) < 50f) return
        lastGeocodedLocation = location
        if (!Geocoder.isPresent()) return

        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                overlayRecorder?.overlayAddress = formatAddress(addresses.firstOrNull())
            }
        } else {
            geocoderExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    overlayRecorder?.overlayAddress = formatAddress(addresses?.firstOrNull())
                } catch (e: Exception) { Log.e(TAG, "Geocoder error", e) }
            }
        }
    }

    private fun formatAddress(address: android.location.Address?): String {
        if (address == null) return ""
        val parts = mutableListOf<String>()
        fun add(value: String?) {
            if (!value.isNullOrBlank() && value !in parts) parts.add(value)
        }
        add(address.adminArea)
        add(address.subAdminArea)
        add(address.locality)
        add(address.subLocality)
        add(address.thoroughfare)
        add(address.subThoroughfare)
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return address.getAddressLine(0) ?: ""
    }

    // ══════════════════════════════════════════════════════════════════════
    // Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(recording: Boolean): Notification {
        val text = getString(
            if (recording) R.string.notification_recording else R.string.notification_standby
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(recording: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(recording))
    }
}
