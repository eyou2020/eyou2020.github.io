package com.dashcam.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.opengl.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX + OpenGL overlay burn-in recorder.
 *
 * Architecture:
 *   CameraX Preview → camSt (OES texture)
 *     ↓ renderFrame() on render thread
 *   GL Pass 1 (camera OES) + Pass 2 (overlay bitmap)
 *     ↓ eglSwapBuffers
 *   encoderEglSurface → MediaCodec → MediaMuxer → MP4  (while recording)
 *   displayEglSurface → SurfaceView                    (always, for preview)
 *
 * Lifecycle:
 *   prepare()        → start GL preview (no encoder)
 *   startRecording() → add encoder path
 *   stopRecording()  → finalize file, continue GL preview
 *   release()        → tear down everything
 */
class OverlayVideoRecorder(
    private val context: Context,
    private val videoSize: Size
) {
    companion object {
        private const val TAG = "OverlayRecorder"
        private const val MIME_VIDEO = "video/avc"
        private const val MIME_AUDIO = "audio/mp4a-latm"
        private const val VIDEO_BITRATE = 8_000_000
        private const val FRAME_RATE   = 30
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE     = 128_000

        // Flip to true → overlay solid red; verifies Pass 2 reaches encoder.
        private const val DEBUG_RED_OVERLAY = false

        // ── Camera OES shader (samplerExternalOES + STMatrix) ──────────────
        private val VS_CAMERA = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }""".trimIndent()

        private val FS_CAMERA = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }""".trimIndent()

        // ── Overlay 2-D shader (sampler2D, no STMatrix) ────────────────────
        private val VS_OVERLAY = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = aTextureCoord;
            }""".trimIndent()

        private val FS_OVERLAY = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uOverlayTexture;
            void main() {
                gl_FragColor = texture2D(uOverlayTexture, vTextureCoord);
            }""".trimIndent()
    }

    // ── State ──────────────────────────────────────────────────────────────
    private val isRunning  = AtomicBoolean(false)
    @Volatile private var recording = false

    // ── Render thread ──────────────────────────────────────────────────────
    private var renderThread:  HandlerThread? = null
    private var renderHandler: Handler?       = null

    // ── EGL ────────────────────────────────────────────────────────────────
    private var eglDisplay        = EGL14.EGL_NO_DISPLAY
    private var eglContext        = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var displayEglSurface = EGL14.EGL_NO_SURFACE   // → SurfaceView
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE   // → MediaCodec input
    private var displayWidth  = 0
    private var displayHeight = 0

    // ── GL programs & textures ─────────────────────────────────────────────
    private var camProgram     = 0
    private var overlayProgram = 0
    private var cameraTexId    = 0
    private var overlayTexId   = 0
    private lateinit var camSt: SurfaceTexture
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    // Cached attribute / uniform locations (populated once in setupGL)
    private var camPosLoc  = -1;  private var camTexLoc  = -1
    private var camStmLoc  = -1;  private var camSampLoc = -1
    private var ovPosLoc   = -1;  private var ovTexLoc   = -1;  private var ovSampLoc = -1

    // Vertex buffers — camera quad (separate pos+uv) and overlay quad (interleaved)
    private lateinit var vertBuf:         FloatBuffer
    private lateinit var texBuf:          FloatBuffer
    private lateinit var overlayCoordBuf: FloatBuffer

    // ── Overlay data ───────────────────────────────────────────────────────
    private val overlayDirty = AtomicBoolean(true)
    @Volatile private var _overlayLocation = ""
    @Volatile private var _overlayAddress  = ""
    @Volatile private var _overlaySpeed    = "0 km/h"

    var overlayLocation: String
        get() = _overlayLocation
        set(v) { _overlayLocation = v; overlayDirty.set(true) }
    var overlayAddress: String
        get() = _overlayAddress
        set(v) { _overlayAddress = v; overlayDirty.set(true) }
    var overlaySpeed: String
        get() = _overlaySpeed
        set(v) { _overlaySpeed = v; overlayDirty.set(true) }

    private var overlayBitmap: Bitmap? = null
    private var frameCount = 0

    // ── Encoder / Muxer ────────────────────────────────────────────────────
    private var videoEncoder: MediaCodec?    = null
    private var audioEncoder: MediaCodec?    = null
    private var muxer:        MediaMuxer?    = null
    private var videoTrack   = -1;  private var audioTrack = -1
    @Volatile private var muxerStarted = false
    private var startCamNs  = 0L
    private var outputPfd:  ParcelFileDescriptor? = null
    private var audioThread: Thread? = null
    private val muxerLock = Any()
    private val vInfo = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()

    // ── CameraX ────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start GL preview (no encoder).  Call once when the SurfaceView's surface is ready.
     * [onReady] fires on the main thread after CameraX is bound and frames are flowing.
     */
    fun prepare(
        displaySurface: Surface,
        lifecycleOwner: LifecycleOwner,
        onReady: () -> Unit
    ) {
        isRunning.set(true)
        renderThread  = HandlerThread("OVR-render").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler!!.post {
            setupEGL(displaySurface)
            setupGL()
            setupCameraSurface()
            Handler(Looper.getMainLooper()).post {
                bindCamera(lifecycleOwner)
                onReady()
            }
        }
    }

    /** Add encoder path and begin writing to an MP4 file. */
    fun startRecording() {
        renderHandler?.post {
            startCamNs = 0L           // reset PTS origin for new file
            muxerStarted = false
            videoTrack = -1;  audioTrack = -1
            setupEncoders()
            setupEncoderEGLSurface()
            startAudio()
            recording = true
            Log.d(TAG, "Recording started")
        }
    }

    /** Finalize the MP4 and stop the encoder.  GL preview keeps running. */
    fun stopRecording(onDone: () -> Unit) {
        recording = false
        renderHandler?.post {
            drainVideo(eos = true)
            audioThread?.join(3000)
            drainAudioFinal()
            finalizeEncoding()
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /** Tear down the entire pipeline (call from onDestroy / surfaceDestroyed). */
    fun release() {
        val wasRecording = recording
        isRunning.set(false)
        recording = false
        cameraProvider?.unbindAll()
        renderHandler?.post {
            if (wasRecording) {
                try { drainVideo(eos = true) }     catch (_: Exception) {}
                audioThread?.interrupt()
                try { audioThread?.join(2000) }    catch (_: Exception) {}
                try { drainAudioFinal() }          catch (_: Exception) {}
                try { finalizeEncoding() }         catch (_: Exception) {}
            }
            releaseAll()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EGL setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupEGL(displaySurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE,   8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, n, 0)
        eglConfig = cfgs[0]

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig!!, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)

        displayEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig!!, displaySurface, intArrayOf(EGL14.EGL_NONE), 0
        )

        // Cache display dimensions for glViewport
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_WIDTH,  w, 0)
        EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_HEIGHT, h, 0)
        displayWidth  = w[0].takeIf { it > 0 } ?: videoSize.width
        displayHeight = h[0].takeIf { it > 0 } ?: videoSize.height

        EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)
        Log.d(TAG, "EGL ready — display=${displayWidth}x${displayHeight}  context=$eglContext")
    }

    private fun setupEncoderEGLSurface() {
        val encSurface = videoEncoder!!.createInputSurface()
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig!!, encSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        Log.d(TAG, "EGL encoder surface ready — encoderEglSurface=$encoderEglSurface")
    }

    // ══════════════════════════════════════════════════════════════════════
    // OpenGL setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGL() {
        camProgram     = buildProgram(VS_CAMERA,  FS_CAMERA)
        overlayProgram = buildProgram(VS_OVERLAY, FS_OVERLAY)

        val ids = IntArray(2); GLES20.glGenTextures(2, ids, 0)
        cameraTexId  = ids[0]
        overlayTexId = ids[1]
        Log.d(TAG, "glGenTextures → camera=$cameraTexId  overlay=$overlayTexId")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Camera quad — separate position and UV buffers
        vertBuf = floatBuf(floatArrayOf(-1f,-1f,  1f,-1f,  -1f,1f,  1f,1f))
        texBuf  = floatBuf(floatArrayOf( 0f, 0f,  1f, 0f,   0f,1f,  1f,1f))

        // Overlay quad — interleaved (x, y, u, v), stride = 16 bytes
        overlayCoordBuf = floatBuf(floatArrayOf(
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f
        ))

        // Cache program handles (once, after link)
        camPosLoc  = GLES20.glGetAttribLocation(camProgram,  "aPosition")
        camTexLoc  = GLES20.glGetAttribLocation(camProgram,  "aTexCoord")
        camStmLoc  = GLES20.glGetUniformLocation(camProgram, "uSTMatrix")
        camSampLoc = GLES20.glGetUniformLocation(camProgram, "uTexture")
        Log.d(TAG, "camera  locs — pos=$camPosLoc tex=$camTexLoc stm=$camStmLoc samp=$camSampLoc")

        ovPosLoc  = GLES20.glGetAttribLocation(overlayProgram,  "aPosition")
        ovTexLoc  = GLES20.glGetAttribLocation(overlayProgram,  "aTextureCoord")
        ovSampLoc = GLES20.glGetUniformLocation(overlayProgram, "uOverlayTexture")
        Log.d(TAG, "overlay locs — pos=$ovPosLoc tex=$ovTexLoc samp=$ovSampLoc")
    }

    private fun setupCameraSurface() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        camSt = SurfaceTexture(cameraTexId)
        camSt.setDefaultBufferSize(videoSize.width, videoSize.height)
        cameraSurface = Surface(camSt)
        camSt.setOnFrameAvailableListener({ renderFrame() }, renderHandler)
    }

    // ══════════════════════════════════════════════════════════════════════
    // CameraX binding
    // ══════════════════════════════════════════════════════════════════════

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .setTargetResolution(videoSize)
                .build()

            preview.setSurfaceProvider { request ->
                // Update buffer size to match what CameraX actually resolved
                camSt.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                request.provideSurface(
                    cameraSurface!!,
                    ContextCompat.getMainExecutor(context)
                ) { /* surface released */ }
            }

            cameraProvider!!.unbindAll()
            cameraProvider!!.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
            Log.d(TAG, "CameraX Preview bound to lifecycle")
        }, ContextCompat.getMainExecutor(context))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Per-frame render
    // ══════════════════════════════════════════════════════════════════════

    private fun renderFrame() {
        if (!isRunning.get()) return

        // updateTexImage must be on the thread that owns the GL context
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        camSt.updateTexImage()
        camSt.getTransformMatrix(stMatrix)

        // Bake overlay bitmap when data changed or once per second
        frameCount++
        if (overlayDirty.getAndSet(false) || frameCount % FRAME_RATE == 0 || overlayBitmap == null) {
            bakeOverlay()
        }

        // ── Pass to encoder (only while recording) ─────────────────────────
        if (recording && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCameraTexture()
            GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
            drawOverlayTexture()
            if (startCamNs == 0L) startCamNs = camSt.timestamp
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, camSt.timestamp - startCamNs)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
            drainVideo(eos = false)
        }

        // ── Pass to display (always — camera preview + overlay on screen) ──
        EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)
        GLES20.glViewport(0, 0, displayWidth, displayHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCameraTexture()
        drawOverlayTexture()
        EGL14.eglSwapBuffers(eglDisplay, displayEglSurface)
    }

    // ══════════════════════════════════════════════════════════════════════
    // GL draw calls
    // ══════════════════════════════════════════════════════════════════════

    private fun drawCameraTexture() {
        GLES20.glUseProgram(camProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(camSampLoc, 0)
        GLES20.glUniformMatrix4fv(camStmLoc, 1, false, stMatrix, 0)

        vertBuf.rewind()
        GLES20.glVertexAttribPointer(camPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(camPosLoc)
        texBuf.rewind()
        GLES20.glVertexAttribPointer(camTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(camTexLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(camPosLoc)
        GLES20.glDisableVertexAttribArray(camTexLoc)
        // Unbind OES so Pass 2's sampler2D sees only the 2D binding on unit 0
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun drawOverlayTexture() {
        overlayCoordBuf.rewind()
        if (ovPosLoc < 0 || ovTexLoc < 0) {
            Log.w(TAG, "drawOverlayTexture: invalid attrib locs — skip")
            return
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glUniform1i(ovSampLoc, 0)

        val stride = 16
        overlayCoordBuf.position(0)
        GLES20.glVertexAttribPointer(ovPosLoc, 2, GLES20.GL_FLOAT, false, stride, overlayCoordBuf)
        GLES20.glEnableVertexAttribArray(ovPosLoc)

        overlayCoordBuf.position(2)
        GLES20.glVertexAttribPointer(ovTexLoc, 2, GLES20.GL_FLOAT, false, stride, overlayCoordBuf)
        GLES20.glEnableVertexAttribArray(ovTexLoc)

        overlayCoordBuf.rewind()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("drawOverlayTexture")

        GLES20.glDisableVertexAttribArray(ovPosLoc)
        GLES20.glDisableVertexAttribArray(ovTexLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
        overlayCoordBuf.rewind()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Overlay bitmap baking
    // ══════════════════════════════════════════════════════════════════════

    private fun bakeOverlay() {
        val w = videoSize.width; val h = videoSize.height
        if (overlayBitmap == null)
            overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val bmp = overlayBitmap!!

        if (DEBUG_RED_OVERLAY) {
            bmp.eraseColor(Color.RED)
            uploadOverlayBitmap(bmp)
            return
        }

        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        val textSz = h * 0.065f
        val margin = w * 0.015f
        val lineH  = textSz * 1.35f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style    = Paint.Style.FILL
            color    = Color.WHITE
            textSize = textSz
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val stroke = Paint(paint).apply {
            style       = Paint.Style.STROKE
            color       = Color.BLACK
            strokeWidth = textSz * 0.08f
            alpha       = 200
        }

        fun drawLabeled(text: String, x: Float, y: Float) {
            if (text.isBlank()) return
            canvas.drawText(text, x, y, stroke)
            canvas.drawText(text, x, y, paint)
        }

        // Baseline placement via fontMetrics — text never clips outside bitmap
        val fm = paint.fontMetrics

        // Top-left: GPS coordinates + address (text top at `margin`)
        var y = margin - fm.top
        _overlayLocation.split("\n").forEach { line ->
            if (line.isNotBlank()) { drawLabeled(line.trim(), margin, y); y += lineH }
        }
        if (_overlayAddress.isNotEmpty()) drawLabeled(_overlayAddress, margin, y)

        // Top-right: date/time (text top at `margin`)
        val now = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
        drawLabeled(now, w - paint.measureText(now) - margin, margin - fm.top)

        // Bottom-left: speed (text bottom at `h - margin`)
        drawLabeled(_overlaySpeed, margin, h - margin - fm.bottom)

        uploadOverlayBitmap(bmp)
    }

    private fun uploadOverlayBitmap(bmp: Bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        // Re-assert params before upload — some drivers reset them after texImage2D
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        checkGlError("texImage2D overlay")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Encoder / Muxer setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupEncoders() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DashCam_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
            outputPfd = context.contentResolver.openFileDescriptor(uri, "w")
            muxer = MediaMuxer(outputPfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            val dir = File(context.getExternalFilesDir(null), "DashCam").also { it.mkdirs() }
            muxer = MediaMuxer(
                "${dir.absolutePath}/DashCam_$ts.mp4",
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }

        val vf = MediaFormat.createVideoFormat(MIME_VIDEO, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,     VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE,   FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MIME_VIDEO).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // Input surface is created AFTER configure — see setupEncoderEGLSurface()
        }

        val af = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,       AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioEncoder = MediaCodec.createEncoderByType(MIME_AUDIO).also {
            it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Video drain
    // ══════════════════════════════════════════════════════════════════════

    private fun drainVideo(eos: Boolean) {
        val enc = videoEncoder ?: return
        if (eos) enc.signalEndOfInputStream()
        loop@ while (true) {
            val idx = enc.dequeueOutputBuffer(vInfo, if (eos) 100_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (eos) continue@loop else break@loop
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer!!.addTrack(enc.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) vInfo.size = 0
                    if (vInfo.size > 0 && muxerStarted) {
                        buf.position(vInfo.offset); buf.limit(vInfo.offset + vInfo.size)
                        synchronized(muxerLock) { muxer!!.writeSampleData(videoTrack, buf, vInfo) }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Audio thread
    // ══════════════════════════════════════════════════════════════════════

    private fun startAudio() {
        audioThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            @SuppressLint("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
            rec.startRecording()
            val buf = ByteArray(minBuf)
            var totalSamples = 0L
            while (!Thread.currentThread().isInterrupted && recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val ptsUs = totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                    encodeAudio(buf, n, ptsUs, eos = false)
                    totalSamples += n / 2
                }
            }
            rec.stop(); rec.release()
            encodeAudio(ByteArray(0), 0, 0, eos = true)
        }.also { it.start() }
    }

    private fun encodeAudio(data: ByteArray, size: Int, ptsUs: Long, eos: Boolean) {
        val enc = audioEncoder ?: return
        val idx = enc.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val buf = enc.getInputBuffer(idx)!!; buf.clear()
            if (size > 0) buf.put(data, 0, size)
            enc.queueInputBuffer(idx, 0, size, ptsUs,
                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        }
        drainAudio(enc)
    }

    private fun drainAudio(enc: MediaCodec) {
        loop@ while (true) {
            val idx = enc.dequeueOutputBuffer(aInfo, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break@loop
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioTrack = muxer!!.addTrack(enc.outputFormat)
                    maybeStartMuxer()
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        aInfo.size > 0 && muxerStarted) {
                        buf.position(aInfo.offset); buf.limit(aInfo.offset + aInfo.size)
                        synchronized(muxerLock) { muxer!!.writeSampleData(audioTrack, buf, aInfo) }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        }
    }

    private fun drainAudioFinal() {
        val enc = audioEncoder ?: return
        var more = true
        while (more) {
            val idx = enc.dequeueOutputBuffer(aInfo, 50_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> more = false
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        aInfo.size > 0 && muxerStarted) {
                        buf.position(aInfo.offset); buf.limit(aInfo.offset + aInfo.size)
                        synchronized(muxerLock) { muxer!!.writeSampleData(audioTrack, buf, aInfo) }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) more = false
                }
            }
        }
    }

    @Synchronized
    private fun maybeStartMuxer() {
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            muxer!!.start(); muxerStarted = true
            Log.d(TAG, "Muxer started — video=$videoTrack  audio=$audioTrack")
        }
    }

    private fun finalizeEncoding() {
        try { if (muxerStarted) muxer?.stop(); muxer?.release() }
        catch (e: Exception) { Log.e(TAG, "muxer finalize", e) }
        muxer = null; muxerStarted = false; videoTrack = -1; audioTrack = -1

        try { videoEncoder?.stop() } catch (_: Exception) {}
        videoEncoder?.release(); videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        audioEncoder?.release(); audioEncoder = null

        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
        outputPfd?.close(); outputPfd = null
        Log.d(TAG, "Encoding finalized")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Release
    // ══════════════════════════════════════════════════════════════════════

    private fun releaseAll() {
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        cameraSurface?.release(); cameraSurface = null
        if (::camSt.isInitialized) camSt.release()

        overlayBitmap?.recycle(); overlayBitmap = null
        renderThread?.quitSafely()
        Log.d(TAG, "Released")
    }

    // ══════════════════════════════════════════════════════════════════════
    // GL helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val st = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) Log.e(TAG, "Shader compile: ${GLES20.glGetShaderInfoLog(shader)}")
            return shader
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER,   vsSrc))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(prog)
        val st = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) Log.e(TAG, "Program link: ${GLES20.glGetProgramInfoLog(prog)}")
        return prog
    }

    private fun checkGlError(op: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) Log.e(TAG, "GL error after $op: 0x${err.toString(16)}")
    }

    private fun floatBuf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(arr); it.position(0) }
}
