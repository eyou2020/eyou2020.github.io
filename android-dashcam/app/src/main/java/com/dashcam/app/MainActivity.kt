package com.dashcam.app

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.*
import android.location.Geocoder
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
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
import java.io.File
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

    // UI
    private lateinit var textureView: TextureView
    private lateinit var tvLocation: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDateTime: TextView
    private lateinit var btnRecord: ImageButton
    private lateinit var btnExit: ImageButton
    private lateinit var tvRecording: TextView

    // Camera2
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var videoSize = Size(1920, 1080)

    // 녹화: 카메라 프레임 + 오버레이(Canvas DrawText)를 OpenGL ES로 합성하여 MediaCodec으로 인코딩
    private val overlayRecorder = OverlayRecorder()
    private var isRecording = false
    private var currentVideoUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var blinkAnimator: ObjectAnimator? = null

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastGeocodedLocation: Location? = null
    private val geocoderExecutor = Executors.newSingleThreadExecutor()

    // UI hide logic
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideRecordButton() }

    // Time update
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            tvDateTime.text = now
            if (isRecording) overlayRecorder.updateOverlay(buildOverlayBitmap())
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            configureTransform(w, h)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); cameraDevice = null
            Log.e(TAG, "Camera error: $error")
        }
    }

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

        textureView = findViewById(R.id.textureView)
        tvLocation = findViewById(R.id.tvLocation)
        tvAddress = findViewById(R.id.tvAddress)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDateTime = findViewById(R.id.tvDateTime)
        btnRecord = findViewById(R.id.btnRecord)
        btnExit = findViewById(R.id.btnExit)
        tvRecording = findViewById(R.id.tvRecording)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        btnExit.setOnClickListener {
            if (isRecording) stopRecording()
            finishAndRemoveTask()
        }

        textureView.setOnClickListener {
            if (isRecording) showRecordButton()
        }

        if (!checkPermissions()) requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) openCamera()
        else textureView.surfaceTextureListener = surfaceTextureListener
        startLocationUpdates()
        timeHandler.post(timeRunnable)
    }

    override fun onPause() {
        timeHandler.removeCallbacks(timeRunnable)
        stopLocationUpdates()
        if (isRecording) stopRecording()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        geocoderExecutor.shutdown()
        super.onDestroy()
    }

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
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                if (textureView.isAvailable) openCamera()
                startLocationUpdates()
            } else {
                Toast.makeText(this, "카메라, 오디오, 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Background thread ──────────────────────────────────────────────────

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: InterruptedException) { Log.e(TAG, "Thread join error", e) }
        backgroundThread = null
        backgroundHandler = null
    }

    // ── Camera ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!checkPermissions()) return
        try {
            val cameraId = getBackCameraId() ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            videoSize = chooseVideoSize(sizes)
            configureTransform(textureView.width, textureView.height)
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot open camera", e)
        }
    }

    private fun getBackCameraId(): String? {
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
                return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseVideoSize(sizes: Array<Size>): Size {
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        return sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)
    }

    private fun startPreview() {
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(videoSize.width, videoSize.height)
        val surface = Surface(st)
        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
                        catch (e: CameraAccessException) { Log.e(TAG, "Preview error", e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview config failed")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) { Log.e(TAG, "startPreview error", e) }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        if (overlayRecorder.isRecording) overlayRecorder.stop()
    }

    @Suppress("DEPRECATION")
    private fun configureTransform(vw: Int, vh: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, vw.toFloat(), vh.toFloat())
        val bufferRect = RectF(0f, 0f, videoSize.height.toFloat(), videoSize.width.toFloat())
        val cx = viewRect.centerX(); val cy = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(vh.toFloat() / videoSize.height, vw.toFloat() / videoSize.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), cx, cy)
        }
        textureView.setTransform(matrix)
    }

    // ── Recording ──────────────────────────────────────────────────────────

    private fun startRecording() {
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(videoSize.width, videoSize.height)
        val previewSurface = Surface(st)

        try {
            createOutputFile()
            overlayRecorder.start(videoSize, currentPfd!!.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Recorder setup failed", e)
            Toast.makeText(this, "녹화 준비 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        // 카메라 프레임은 GL로 합성(OverlayRecorder)되어 인코더로 들어간다.
        val recorderSurface = overlayRecorder.cameraInputSurface!!

        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            captureSession?.close()
            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            runOnUiThread {
                                isRecording = true
                                updateRecordingUI()
                                scheduleHideRecordButton()
                                overlayRecorder.updateOverlay(buildOverlayBitmap())
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Recording session error", e)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "녹화 세션 설정 실패", Toast.LENGTH_SHORT).show() }
                        Log.e(TAG, "Recording session config failed")
                    }
                }, backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            overlayRecorder.stop()
            currentPfd?.close(); currentPfd = null
        }
    }

    /** MediaMuxer 출력 대상 파일을 만들고 [currentPfd]에 연다 */
    private fun createOutputFile() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DashCam_$timestamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam")
            }
            currentVideoUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            currentPfd = contentResolver.openFileDescriptor(currentVideoUri!!, "w")
        } else {
            val dir = File(getExternalFilesDir(null), "DashCam").also { it.mkdirs() }
            val file = File(dir, "DashCam_$timestamp.mp4")
            currentPfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            )
        }
    }

    private fun stopRecording() {
        isRecording = false
        uiHandler.removeCallbacks(hideRunnable)

        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Stop repeating error", e)
        }

        // OverlayRecorder.stop()은 인코더 flush + muxer 종료까지 동기 대기하므로 백그라운드 스레드에서 실행
        Thread {
            overlayRecorder.stop()
            currentPfd?.close(); currentPfd = null

            runOnUiThread {
                stopBlinking()
                updateRecordingUI()
                showRecordButton()
                Toast.makeText(this, "영상이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            startPreview()
        }.start()
    }

    // ── 오버레이(Canvas DrawText) 비트맵 생성 ─────────────────────────────────
    // 카메라 프레임 위에 OpenGL로 합성되어 녹화 파일에 그대로 저장된다.

    private fun buildOverlayBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(videoSize.width, videoSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val textSize = videoSize.height * 0.045f
        val margin = videoSize.height * 0.025f

        val paint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.MONOSPACE
            isFakeBoldText = true
            isAntiAlias = true
            setShadowLayer(6f, 2f, 2f, Color.argb(204, 0, 0, 0))
        }

        // 좌상단: GPS 좌표 + 주소
        var y = margin + textSize
        for (line in tvLocation.text.toString().split("\n")) {
            canvas.drawText(line, margin, y, paint)
            y += textSize * 1.2f
        }
        val address = tvAddress.text.toString()
        if (address.isNotEmpty()) {
            canvas.drawText(address, margin, y, paint)
        }

        // 우상단: 날짜/시간
        val dateTime = tvDateTime.text.toString()
        canvas.drawText(dateTime, videoSize.width - margin - paint.measureText(dateTime), margin + textSize, paint)

        // 좌하단: 속도
        val speedLines = tvSpeed.text.toString().split("\n")
        var sy = videoSize.height - margin - (speedLines.size - 1) * textSize * 1.2f
        for (line in speedLines) {
            canvas.drawText(line, margin, sy, paint)
            sy += textSize * 1.2f
        }

        // 우하단: REC 표시
        val recPaint = Paint(paint).apply {
            color = Color.RED
            this.textSize = textSize * 0.7f
        }
        val recText = "● REC"
        canvas.drawText(
            recText,
            videoSize.width - margin - recPaint.measureText(recText),
            videoSize.height - margin,
            recPaint
        )

        return bitmap
    }

    // ── Recording UI ───────────────────────────────────────────────────────

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

    private fun showRecordButton() {
        btnRecord.visibility = View.VISIBLE
        btnExit.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideRunnable)
        if (isRecording) scheduleHideRecordButton()
    }

    private fun hideRecordButton() {
        if (isRecording) {
            btnRecord.visibility = View.GONE
            btnExit.visibility = View.GONE
        }
    }

    private fun scheduleHideRecordButton() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun startBlinking() {
        blinkAnimator = ObjectAnimator.ofFloat(tvRecording, "alpha", 1f, 0.1f).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopBlinking() {
        blinkAnimator?.cancel(); blinkAnimator = null
        tvRecording.alpha = 1f
    }

    // ── Location ───────────────────────────────────────────────────────────

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

        if (isRecording) overlayRecorder.updateOverlay(buildOverlayBitmap())

        fetchAddress(location)
    }

    private fun fetchAddress(location: Location) {
        // 50m 이상 이동했을 때만 역지오코딩 (네트워크 절약)
        if (lastGeocodedLocation != null && location.distanceTo(lastGeocodedLocation!!) < 50f) return
        lastGeocodedLocation = location

        if (!Geocoder.isPresent()) return

        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                val text = formatAddress(addresses.firstOrNull())
                runOnUiThread {
                    tvAddress.text = text
                    if (isRecording) overlayRecorder.updateOverlay(buildOverlayBitmap())
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
                        if (isRecording) overlayRecorder.updateOverlay(buildOverlayBitmap())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Geocoder error", e)
                }
            }
        }
    }

    private fun formatAddress(address: android.location.Address?): String {
        if (address == null) return ""
        // 시/도 + 구/군 + 도로명 순으로 조합
        val parts = mutableListOf<String>()
        address.adminArea?.let { parts.add(it) }
        address.subAdminArea?.takeIf { it != address.adminArea }?.let { parts.add(it) }
        address.thoroughfare?.let { parts.add(it) }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        // 폴백: 첫 번째 주소 라인
        return address.getAddressLine(0)?.take(50) ?: ""
    }
}
