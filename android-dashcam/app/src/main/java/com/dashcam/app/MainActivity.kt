package com.dashcam.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val HIDE_DELAY_MS = 3000L

        private const val PREFS_NAME = "dashcam_settings"
        private const val KEY_SEGMENT_MINUTES   = "segment_minutes"
        private const val DEFAULT_SEGMENT_MINUTES = 15
        private const val MIN_SEGMENT_MINUTES = 1
        private const val MAX_SEGMENT_MINUTES = 60

        private const val KEY_DAYTIME_ENABLED   = "daytime_enabled"
        private const val KEY_DAYTIME_FROM_HOUR = "daytime_from_hour"
        private const val KEY_DAYTIME_FROM_MIN  = "daytime_from_min"
        private const val KEY_DAYTIME_TO_HOUR   = "daytime_to_hour"
        private const val KEY_DAYTIME_TO_MIN    = "daytime_to_min"

        private val REQUIRED_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // ── UI ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView:  SurfaceView
    private lateinit var btnEvent:     ImageButton
    private lateinit var btnRecord:    ImageButton
    private lateinit var btnExit:      ImageButton
    private lateinit var btnSettings:  ImageButton
    private lateinit var btnDaytime:   ImageButton
    private lateinit var tvRecording:  TextView

    // ── Recording service (hosts camera/GL/encoder pipeline) ────────────────
    private var recordingService: RecordingService? = null
    private var serviceBound = false
    private var pendingDisplaySurface: Surface? = null
    private var isRecording = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RecordingService.RecordingBinder).service
            recordingService = svc
            svc.setRecordingStateListener { recording ->
                runOnUiThread {
                    isRecording = recording
                    updateRecordingUI()
                }
            }
            isRecording = svc.isRecording
            updateRecordingUI()
            pendingDisplaySurface?.let { svc.attachDisplaySurface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
        }
    }

    // ── Blink animator ─────────────────────────────────────────────────────
    private var blinkAnimator: ObjectAnimator? = null

    // ── UI hide ──────────────────────────────────────────────────────────────
    private val uiHandler  = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }

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
        btnEvent     = findViewById(R.id.btnEvent)
        btnRecord    = findViewById(R.id.btnRecord)
        btnExit      = findViewById(R.id.btnExit)
        btnSettings  = findViewById(R.id.btnSettings)
        btnDaytime   = findViewById(R.id.btnDaytime)
        tvRecording  = findViewById(R.id.tvRecording)

        btnEvent.setOnClickListener { onEventClicked() }
        btnRecord.setOnClickListener { onRecordClicked() }
        btnExit.setOnClickListener { onExitClicked() }
        btnSettings.setOnClickListener { showSegmentDurationDialog() }
        btnDaytime.setOnClickListener { showDaytimeDialog() }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                recordingService?.toggleTextColor()
                return true
            }
        })
        surfaceView.setOnClickListener { showControls() }
        @Suppress("ClickableViewAccessibility")
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // SurfaceHolder callback — attach/detach the live-preview surface.
        // Camera capture and recording live in RecordingService and are
        // unaffected by the surface being destroyed (e.g. app backgrounded).
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingDisplaySurface = holder.surface
                recordingService?.attachDisplaySurface(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingDisplaySurface = null
                recordingService?.detachDisplaySurface()
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
        })

        if (!checkPermissions()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (checkPermissions()) startAndBindService()
    }

    override fun onStop() {
        if (serviceBound) {
            recordingService?.setRecordingStateListener(null)
            unbindService(serviceConnection)
            serviceBound = false
            recordingService = null
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (isRecording) scheduleHideControls()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(hideRunnable)
        super.onPause()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun checkPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        androidx.core.app.ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && checkPermissions()) {
            startAndBindService()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            Toast.makeText(this, "카메라, 오디오, 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recording service binding
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start [RecordingService] as a foreground service (so it outlives this
     * Activity) and bind to it for UI control / live preview.
     */
    private fun startAndBindService() {
        if (serviceBound) return
        val intent = Intent(this, RecordingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBound = true
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recording controls
    // ══════════════════════════════════════════════════════════════════════

    private fun onEventClicked() {
        val svc = recordingService ?: return
        btnEvent.isEnabled = false
        svc.saveEventSegment {
            btnEvent.isEnabled = true
            Toast.makeText(this, "이벤트 영상이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onRecordClicked() {
        val svc = recordingService ?: run {
            Toast.makeText(this, "카메라 준비 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) {
            svc.stopRecording {
                showControls()
                Toast.makeText(this, "영상이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
        } else {
            svc.startRecording()
            Toast.makeText(this, "녹화를 시작합니다.", Toast.LENGTH_SHORT).show()
            scheduleHideControls()
        }
    }

    private fun onExitClicked() {
        val svc = recordingService
        val finish = {
            stopService(Intent(this, RecordingService::class.java))
            finishAndRemoveTask()
        }
        if (svc != null && isRecording) {
            svc.stopRecording { finish() }
        } else {
            finish()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun updateRecordingUI() {
        if (isRecording) {
            btnRecord.setImageResource(R.drawable.ic_stop)
            tvRecording.visibility = View.VISIBLE
            btnEvent.visibility    = View.VISIBLE
            startBlinking()
        } else {
            btnRecord.setImageResource(R.drawable.ic_record)
            tvRecording.visibility = View.GONE
            btnEvent.visibility    = View.GONE
            stopBlinking()
        }
    }

    private fun showControls() {
        if (isRecording) btnEvent.visibility = View.VISIBLE
        btnRecord.visibility   = View.VISIBLE
        btnExit.visibility     = View.VISIBLE
        btnSettings.visibility = View.VISIBLE
        btnDaytime.visibility  = View.VISIBLE
        uiHandler.removeCallbacks(hideRunnable)
        if (isRecording) scheduleHideControls()
    }

    private fun hideControls() {
        if (isRecording) {
            btnEvent.visibility    = View.GONE
            btnRecord.visibility   = View.GONE
            btnExit.visibility     = View.GONE
            btnSettings.visibility = View.GONE
            btnDaytime.visibility  = View.GONE
        }
    }

    private fun scheduleHideControls() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun startBlinking() {
        if (blinkAnimator != null) return
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
    // Settings — recording segment (loop) duration
    // ══════════════════════════════════════════════════════════════════════

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadSegmentMinutes(): Int =
        prefs().getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)

    private fun showSegmentDurationDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(loadSegmentMinutes().toString())
            setSelection(text.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.segment_duration_title)
            .setMessage(R.string.segment_duration_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes < MIN_SEGMENT_MINUTES || minutes > MAX_SEGMENT_MINUTES) {
                    Toast.makeText(this, R.string.segment_duration_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    recordingService?.setSegmentDurationMinutes(minutes)
                    Toast.makeText(this, getString(R.string.segment_duration_saved, minutes), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Settings — daytime text-color hours
    // ══════════════════════════════════════════════════════════════════════

    private fun showDaytimeDialog() {
        val settings = recordingService?.getDaytimeSettings() ?: run {
            val p = prefs()
            intArrayOf(
                if (p.getBoolean(KEY_DAYTIME_ENABLED, false)) 1 else 0,
                p.getInt(KEY_DAYTIME_FROM_HOUR, 6), p.getInt(KEY_DAYTIME_FROM_MIN, 0),
                p.getInt(KEY_DAYTIME_TO_HOUR, 20),  p.getInt(KEY_DAYTIME_TO_MIN,  0)
            )
        }
        val fromH = settings[1]; val fromM = settings[2]
        val toH   = settings[3]; val toM   = settings[4]

        TimePickerDialog(this, { _, h, m ->
            TimePickerDialog(this, { _, h2, m2 ->
                saveDaytimeSettings(h, m, h2, m2)
            }, toH, toM, true).apply {
                setTitle(getString(R.string.daytime_to_title))
                show()
            }
        }, fromH, fromM, true).apply {
            setTitle(getString(R.string.daytime_from_title))
            show()
        }
    }

    private fun saveDaytimeSettings(fromH: Int, fromM: Int, toH: Int, toM: Int) {
        val fromStr = String.format("%02d:%02d", fromH, fromM)
        val toStr   = String.format("%02d:%02d", toH,   toM)
        if (recordingService != null) {
            recordingService!!.setDaytimeSettings(true, fromH, fromM, toH, toM)
        } else {
            prefs().edit()
                .putBoolean(KEY_DAYTIME_ENABLED,   true)
                .putInt(KEY_DAYTIME_FROM_HOUR, fromH).putInt(KEY_DAYTIME_FROM_MIN, fromM)
                .putInt(KEY_DAYTIME_TO_HOUR,   toH).putInt(KEY_DAYTIME_TO_MIN,   toM)
                .apply()
        }
        Toast.makeText(this, getString(R.string.daytime_saved, fromStr, toStr), Toast.LENGTH_SHORT).show()
    }
}
