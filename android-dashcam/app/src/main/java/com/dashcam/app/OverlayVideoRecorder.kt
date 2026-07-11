package com.dashcam.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
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
 *   Off-screen FBO: drawCameraTexture() + drawTexturedQuad(overlay) → compositeTexId
 *     ↓ blit (drawTexturedQuad) — same composite texture, twice
 *   displayEglSurface  → SurfaceView                    (optional, for live preview)
 *   encoderEglSurface  → MediaCodec → MediaMuxer → MP4  (while recording)
 *
 * Lifecycle:
 *   prepare()               → start camera + GL pipeline (no display, no encoder)
 *   attachDisplaySurface()  → start blitting the composite to a SurfaceView
 *   detachDisplaySurface()  → stop the preview blit; camera/recording continue
 *   startRecording()        → add encoder path
 *   stopRecording()         → finalize file, pipeline keeps running
 *   release()               → tear down everything
 *
 * The display surface is fully decoupled from camera capture and encoding, so
 * this class can run inside a foreground Service and keep recording after the
 * hosting Activity's SurfaceView is destroyed (app backgrounded).
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
        private const val DEFAULT_SEGMENT_MINUTES = 15

        // Flip to true → overlay solid red; verifies the FBO composite path reaches the encoder.
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

        // ── Generic 2-D shader (sampler2D) — used for overlay bitmap AND
        //    for blitting the FBO composite texture to display/encoder ────
        private val VS_TEX = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = aTextureCoord;
            }""".trimIndent()

        private val FS_TEX = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }""".trimIndent()
    }

    // ── State ──────────────────────────────────────────────────────────────
    private val isRunning  = AtomicBoolean(false)
    @Volatile private var recording = false
    val isRecording: Boolean get() = recording

    // ── Overlay text color (white = day, black = night; toggled by double-tap) ──
    @Volatile private var _overlayTextColor: Int = Color.WHITE
    var overlayTextColor: Int
        get() = _overlayTextColor
        set(v) { if (_overlayTextColor != v) { _overlayTextColor = v; overlayDirty.set(true) } }

    // ── Render thread ──────────────────────────────────────────────────────
    private var renderThread:  HandlerThread? = null
    private var renderHandler: Handler?       = null

    // ── EGL ────────────────────────────────────────────────────────────────
    private var eglDisplay        = EGL14.EGL_NO_DISPLAY
    private var eglContext        = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var displayEglSurface = EGL14.EGL_NO_SURFACE   // → SurfaceView (optional)
    private var pbufferEglSurface = EGL14.EGL_NO_SURFACE   // 1x1 off-screen fallback surface
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE   // → MediaCodec input
    private var displayWidth  = 0
    private var displayHeight = 0

    // ── GL programs & textures ─────────────────────────────────────────────
    private var camProgram     = 0
    private var texProgram     = 0   // generic sampler2D program (overlay bitmap + composite blit)
    private var cameraTexId    = 0   // OES — camera frames
    private var overlayTexId   = 0   // 2D  — Canvas-drawn GPS/speed/time bitmap
    private var compositeTexId = 0   // 2D  — FBO color attachment (camera + overlay merged)
    private var fboId          = 0
    private lateinit var camSt: SurfaceTexture
    private var cameraSurface: Surface? = null
    private val stMatrix = FloatArray(16)

    // Cached attribute / uniform locations (populated once in setupGL)
    private var camPosLoc  = -1;  private var camTexLoc  = -1
    private var camStmLoc  = -1;  private var camSampLoc = -1
    private var texPosLoc  = -1;  private var texTexLoc  = -1;  private var texSampLoc = -1

    // Vertex buffers
    private lateinit var vertBuf:         FloatBuffer   // camera quad position
    private lateinit var texBuf:          FloatBuffer   // camera quad UV
    private lateinit var overlayCoordBuf: FloatBuffer   // overlay bitmap quad (interleaved pos+uv, Y-flipped)
    private lateinit var blitCoordBuf:    FloatBuffer   // FBO composite blit quad (interleaved pos+uv, natural)

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
    private var currentOutputUri:  Uri?  = null   // API 29+ — used to move to Events folder
    private var currentOutputFile: File? = null   // pre-API 29
    private var audioThread: Thread? = null
    private val muxerLock = Any()
    private val vInfo = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()

    // ── Segmented (loop) recording ──────────────────────────────────────────
    @Volatile private var segmentDurationNs = DEFAULT_SEGMENT_MINUTES * 60 * 1_000_000_000L

    /** Max length of a single recorded file, in minutes. Takes effect from the next segment. */
    var segmentDurationMinutes: Int
        get() = (segmentDurationNs / 60_000_000_000L).toInt()
        set(minutes) { segmentDurationNs = minutes.toLong() * 60_000_000_000L }

    /** Called on the main thread whenever a new segment starts (auto-rotate or event save). */
    var onSegmentRotated: (() -> Unit)? = null

    // ── CameraX ────────────────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Start the GL/camera/encoder pipeline. No display surface is required —
     * camera capture and recording run independently of any preview surface,
     * so recording can continue while the app is backgrounded.
     * [onReady] fires on the main thread after CameraX is bound and frames are flowing.
     */
    fun prepare(
        lifecycleOwner: LifecycleOwner,
        onReady: () -> Unit
    ) {
        isRunning.set(true)
        renderThread  = HandlerThread("OVR-render").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler!!.post {
            setupEGL()
            setupGL()
            setupCameraSurface()
            Handler(Looper.getMainLooper()).post {
                bindCamera(lifecycleOwner)
                onReady()
            }
        }
    }

    /**
     * Attach (or replace) the SurfaceView surface used for live preview.
     * Safe to call repeatedly, e.g. each time the Activity's SurfaceView is recreated.
     */
    fun attachDisplaySurface(surface: Surface) {
        renderHandler?.post {
            if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferEglSurface, pbufferEglSurface, eglContext)
                EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
                displayEglSurface = EGL14.EGL_NO_SURFACE
            }
            displayEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig!!, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            val w = IntArray(1); val h = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_WIDTH,  w, 0)
            EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_HEIGHT, h, 0)
            displayWidth  = w[0].takeIf { it > 0 } ?: videoSize.width
            displayHeight = h[0].takeIf { it > 0 } ?: videoSize.height
            Log.d(TAG, "Display surface attached — ${displayWidth}x${displayHeight}")
        }
    }

    /**
     * Detach the live-preview surface (e.g. SurfaceView destroyed because the app
     * was backgrounded). Camera capture, GL composite and recording all continue
     * unaffected — only the on-screen preview blit (Pass 1) is skipped.
     */
    fun detachDisplaySurface() {
        renderHandler?.post {
            if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, pbufferEglSurface, pbufferEglSurface, eglContext)
                EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
                displayEglSurface = EGL14.EGL_NO_SURFACE
                Log.d(TAG, "Display surface detached")
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
            finalizeNormalSegment()   // IS_PENDING → 0, makes file visible in gallery
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /**
     * Loop recording: finalize the current file and immediately start a new
     * one. Runs on the render thread; [recording] stays true throughout.
     */
    private fun rotateSegment() {
        Log.d(TAG, "Segment duration reached — starting new file")
        drainVideo(eos = true)
        audioThread?.interrupt()
        audioThread?.join(2000)
        drainAudioFinal()
        finalizeEncoding()
        finalizeNormalSegment()   // IS_PENDING → 0 before starting next segment

        muxerStarted = false
        videoTrack = -1; audioTrack = -1
        setupEncoders()
        setupEncoderEGLSurface()
        startAudio()
        Handler(Looper.getMainLooper()).post { onSegmentRotated?.invoke() }
    }

    /**
     * Triggered by the event button while recording.
     * Finalizes the current segment and moves it to the "Events" sub-folder,
     * then seamlessly starts a new normal segment so recording continues.
     * [onDone] fires on the main thread when the move is complete.
     */
    fun saveEventSegment(onDone: () -> Unit) {
        renderHandler?.post {
            if (!recording) return@post
            Log.d(TAG, "Event triggered — saving current segment to Events folder")
            drainVideo(eos = true)
            audioThread?.interrupt()
            audioThread?.join(2000)
            drainAudioFinal()
            finalizeEncoding()
            // moveCurrentOutputToEvents sets RELATIVE_PATH=Events + IS_PENDING=0
            moveCurrentOutputToEvents()

            setupEncoders()
            setupEncoderEGLSurface()
            startAudio()
            startCamNs = camSt.timestamp

            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    /**
     * Move the just-finalized segment into the Events sub-folder and make it
     * visible by clearing IS_PENDING.  If the move fails, the file is kept in
     * the Normal folder (IS_PENDING cleared so it remains accessible).
     */
    private fun moveCurrentOutputToEvents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = currentOutputUri ?: return
            try {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam/Events")
                        put(MediaStore.Video.Media.IS_PENDING, 0)   // make file visible
                    },
                    null, null
                )
                Log.d(TAG, "Event segment moved to Movies/DashCam/Events")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move event segment; keeping in Normal", e)
                // Fallback: at minimum clear IS_PENDING so the file shows up in Normal
                try {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null, null
                    )
                } catch (_: Exception) {}
            }
            currentOutputUri = null
        } else {
            val src = currentOutputFile ?: return
            val eventDir = File(context.getExternalFilesDir(null), "DashCam/Events").also { it.mkdirs() }
            try {
                src.renameTo(File(eventDir, src.name))
                Log.d(TAG, "Event segment moved to ${eventDir.absolutePath}")
            } catch (e: Exception) { Log.e(TAG, "Failed to move event segment", e) }
            currentOutputFile = null
        }
    }

    /** Publish the finalized normal segment by clearing IS_PENDING (API 29+). */
    private fun finalizeNormalSegment() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = currentOutputUri ?: return
            try {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
                Log.d(TAG, "Normal segment published (IS_PENDING cleared)")
            } catch (e: Exception) { Log.e(TAG, "Failed to publish normal segment", e) }
            currentOutputUri = null
        }
        // pre-API 29: file was written directly, no pending flag needed
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
                try { finalizeNormalSegment() }    catch (_: Exception) {}
            }
            releaseAll()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // EGL setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupEGL() {
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

        // 1x1 off-screen surface — lets the context stay current for FBO/texture work
        // (camera capture, GL composite, encoding) even when no display Surface is
        // attached, e.g. while recording with the app in the background.
        val pbufferAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferEglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig!!, pbufferAttrs, 0)

        displayWidth  = videoSize.width
        displayHeight = videoSize.height

        EGL14.eglMakeCurrent(eglDisplay, pbufferEglSurface, pbufferEglSurface, eglContext)
        Log.d(TAG, "EGL ready — context=$eglContext")
    }

    private fun setupEncoderEGLSurface() {
        val encSurface = videoEncoder!!.createInputSurface()
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig!!, encSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        // MUST call start() AFTER createInputSurface() — encoder is inactive until then
        videoEncoder!!.start()
        Log.d(TAG, "EGL encoder surface ready — encoderEglSurface=$encoderEglSurface")
    }

    // ══════════════════════════════════════════════════════════════════════
    // OpenGL setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGL() {
        camProgram = buildProgram(VS_CAMERA, FS_CAMERA)
        texProgram = buildProgram(VS_TEX,    FS_TEX)

        val texIds = IntArray(3); GLES20.glGenTextures(3, texIds, 0)
        cameraTexId    = texIds[0]
        overlayTexId   = texIds[1]
        compositeTexId = texIds[2]
        Log.d(TAG, "glGenTextures → camera=$cameraTexId overlay=$overlayTexId composite=$compositeTexId")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // Camera OES texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Overlay bitmap texture (2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Composite texture — off-screen FBO color attachment (camera + overlay merged)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            videoSize.width, videoSize.height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )

        // FBO — camera + overlay are composited here once per frame
        val fboIds = IntArray(1); GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, compositeTexId, 0
        )
        val fboStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (fboStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete: 0x${fboStatus.toString(16)}")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Camera quad — separate position and UV buffers.
        // UV coords are rotated 90° relative to vertex positions: with
        // Preview.targetRotation = display.rotation (ROTATION_90), the camera
        // buffer (1920x1080, matches videoSize) arrives content-rotated 90° CW,
        // so we counter-rotate the sampling by 90° CCW here.
        vertBuf = floatBuf(floatArrayOf(-1f,-1f,  1f,-1f,  -1f,1f,  1f,1f))
        texBuf  = floatBuf(floatArrayOf( 0f, 1f,  0f, 0f,   1f,1f,  1f,0f))

        // Overlay bitmap quad — interleaved (x, y, u, v). Canvas row 0 (top of bitmap) → V=1
        overlayCoordBuf = floatBuf(floatArrayOf(
            -1f, -1f,  0f, 1f,
             1f, -1f,  1f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f
        ))

        // Composite blit quad — natural mapping (FBO texture origin matches NDC origin)
        blitCoordBuf = floatBuf(floatArrayOf(
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f
        ))

        // Cache program handles (once, after link)
        camPosLoc  = GLES20.glGetAttribLocation(camProgram,  "aPosition")
        camTexLoc  = GLES20.glGetAttribLocation(camProgram,  "aTexCoord")
        camStmLoc  = GLES20.glGetUniformLocation(camProgram, "uSTMatrix")
        camSampLoc = GLES20.glGetUniformLocation(camProgram, "uTexture")
        Log.d(TAG, "camera locs — pos=$camPosLoc tex=$camTexLoc stm=$camStmLoc samp=$camSampLoc")

        texPosLoc  = GLES20.glGetAttribLocation(texProgram,  "aPosition")
        texTexLoc  = GLES20.glGetAttribLocation(texProgram,  "aTextureCoord")
        texSampLoc = GLES20.glGetUniformLocation(texProgram, "uTexture")
        Log.d(TAG, "tex locs — pos=$texPosLoc tex=$texTexLoc samp=$texSampLoc")
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

    @Suppress("DEPRECATION")
    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()

            // The Activity is locked to landscape (see AndroidManifest). Without an explicit
            // targetRotation, CameraX assumes the default (portrait) display rotation and
            // the SurfaceTexture transform matrix ends up rotating frames 90° — producing
            // a stretched/rotated composite once blitted into our landscape-sized FBO.
            // windowManager.defaultDisplay works from a Service context too, so this keeps
            // the correct rotation even when recording runs without an Activity in front.
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetResolution(videoSize)
                .setTargetRotation(rotation)
                .build()

            preview.setSurfaceProvider { request ->
                Log.d(TAG, "SurfaceRequest resolution=${request.resolution} rotation=$rotation")
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

        val diag = frameCount <= 3 || frameCount % FRAME_RATE == 1

        // ── Compose once: camera + overlay → off-screen FBO (compositeTexId) ──
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        if (diag) {
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            Log.d(TAG, "frame=$frameCount FBO status=0x${st.toString(16)}")
        }
        GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCameraTexture()
        if (diag) checkGlError("drawCameraTexture (frame=$frameCount)")
        drawTexturedQuad(overlayTexId, overlayCoordBuf, blend = true)
        if (diag) {
            checkGlError("drawOverlay (frame=$frameCount)")
            logCenterPixel(videoSize.width, videoSize.height, "FBO(frame=$frameCount)")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // ── Pass 1: blit composite → display surface (only while attached) ─
        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            val makeDisplayOk = EGL14.eglMakeCurrent(eglDisplay, displayEglSurface, displayEglSurface, eglContext)
            if (!makeDisplayOk) Log.e(TAG, "eglMakeCurrent(display) failed: 0x${EGL14.eglGetError().toString(16)}")
            GLES20.glViewport(0, 0, displayWidth, displayHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawTexturedQuad(compositeTexId, blitCoordBuf, blend = false)
            if (diag) logCenterPixel(displayWidth, displayHeight, "Display(frame=$frameCount)")
            EGL14.eglSwapBuffers(eglDisplay, displayEglSurface)
        }

        // ── Pass 2: blit composite → encoder surface (only while recording) ─
        if (recording && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            // PTS MUST be set BEFORE any draw calls on the encoder surface
            if (startCamNs == 0L) startCamNs = camSt.timestamp

            // Loop recording: once the current file reaches the configured
            // length, finalize it and seamlessly start a new one.
            if (camSt.timestamp - startCamNs >= segmentDurationNs) {
                rotateSegment()
                startCamNs = camSt.timestamp
            }

            val makeEncOk = EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            if (!makeEncOk) Log.e(TAG, "eglMakeCurrent(encoder) failed: 0x${EGL14.eglGetError().toString(16)}")

            EGLExt.eglPresentationTimeANDROID(
                eglDisplay, encoderEglSurface, camSt.timestamp - startCamNs
            )

            GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawTexturedQuad(compositeTexId, blitCoordBuf, blend = false)
            if (diag) logCenterPixel(videoSize.width, videoSize.height, "Encoder(frame=$frameCount)")

            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
            drainVideo(eos = false)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // GL draw calls
    // ══════════════════════════════════════════════════════════════════════

    /** Pass 1 of FBO composite — draws the camera OES texture (full quad, with STMatrix). */
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
        // Unbind OES so the following sampler2D draw sees only the 2D binding on unit 0
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Generic textured fullscreen quad using [texProgram].
     * Used for: (a) overlay bitmap → FBO (blended), (b) composite FBO texture → display/encoder (opaque).
     */
    private fun drawTexturedQuad(texId: Int, coordBuf: FloatBuffer, blend: Boolean) {
        if (texPosLoc < 0 || texTexLoc < 0) {
            Log.w(TAG, "drawTexturedQuad: invalid attrib locs — skip")
            return
        }

        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES20.glUseProgram(texProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(texSampLoc, 0)

        val stride = 16
        coordBuf.position(0)
        GLES20.glVertexAttribPointer(texPosLoc, 2, GLES20.GL_FLOAT, false, stride, coordBuf)
        GLES20.glEnableVertexAttribArray(texPosLoc)

        coordBuf.position(2)
        GLES20.glVertexAttribPointer(texTexLoc, 2, GLES20.GL_FLOAT, false, stride, coordBuf)
        GLES20.glEnableVertexAttribArray(texTexLoc)

        coordBuf.rewind()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("drawTexturedQuad")

        GLES20.glDisableVertexAttribArray(texPosLoc)
        GLES20.glDisableVertexAttribArray(texTexLoc)
        if (blend) GLES20.glDisable(GLES20.GL_BLEND)
        coordBuf.rewind()
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
            val cx = bmp.getPixel(w / 2, h / 2)
            Log.d(TAG, "bakeOverlay DEBUG_RED — bitmap ${bmp.width}x${bmp.height} centerPixel=0x${Integer.toHexString(cx)}")
            uploadOverlayBitmap(bmp)
            return
        }

        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        val textSz = h * 0.0325f
        val margin = w * 0.015f
        val lineH  = textSz * 1.35f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style    = Paint.Style.FILL
            color    = _overlayTextColor
            textSize = textSz
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        fun drawLabeled(text: String, x: Float, y: Float) {
            if (text.isBlank()) return
            canvas.drawText(text, x, y, paint)
        }

        // Baseline placement via fontMetrics — text never clips outside bitmap
        val fm = paint.fontMetrics

        // Top-left: address + GPS coordinates (text top at `margin`)
        var y = margin - fm.top
        if (_overlayAddress.isNotEmpty()) {
            wrapText(_overlayAddress, paint, w - margin - margin).forEach { line ->
                drawLabeled(line, margin, y)
                y += lineH
            }
        }
        _overlayLocation.split("\n").forEach { line ->
            if (line.isNotBlank()) { drawLabeled(line.trim(), margin, y); y += lineH }
        }

        // Top-right: date / time, 2 lines (text top at `margin`)
        val now = Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        var yRight = margin - fm.top
        drawLabeled(dateStr, w - paint.measureText(dateStr) - margin, yRight)
        yRight += lineH
        drawLabeled(timeStr, w - paint.measureText(timeStr) - margin, yRight)

        // Bottom-left: speed (text bottom at `h - margin`)
        drawLabeled(_overlaySpeed, margin, h - margin - fm.bottom)

        uploadOverlayBitmap(bmp)
    }

    /** Toggle overlay text color between white and black (user double-tap). */
    fun toggleTextColor() {
        overlayTextColor = if (_overlayTextColor == Color.WHITE) Color.BLACK else Color.WHITE
    }

    /** Greedy word-wrap of [text] into lines no wider than [maxWidth] under [paint]. */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        var current = words[0]
        for (word in words.drop(1)) {
            val candidate = "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                lines.add(current)
                current = word
            }
        }
        lines.add(current)
        return lines
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
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam/Normal")
                put(MediaStore.Video.Media.IS_PENDING, 1)   // hidden while writing
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
            currentOutputUri  = uri
            currentOutputFile = null
            outputPfd = context.contentResolver.openFileDescriptor(uri, "w")
            muxer = MediaMuxer(outputPfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            val dir  = File(context.getExternalFilesDir(null), "DashCam/Normal").also { it.mkdirs() }
            val file = File(dir, "DashCam_$ts.mp4")
            currentOutputUri  = null
            currentOutputFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        if (pbufferEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferEglSurface)
            pbufferEglSurface = EGL14.EGL_NO_SURFACE
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

    private val pixelBuf: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    /** Diagnostic: read back the center pixel of the currently-bound framebuffer and log it. */
    private fun logCenterPixel(width: Int, height: Int, label: String) {
        pixelBuf.clear()
        GLES20.glReadPixels(width / 2, height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$label glReadPixels error: 0x${err.toString(16)}")
            return
        }
        val r = pixelBuf.get(0).toInt() and 0xFF
        val g = pixelBuf.get(1).toInt() and 0xFF
        val b = pixelBuf.get(2).toInt() and 0xFF
        val a = pixelBuf.get(3).toInt() and 0xFF
        Log.d(TAG, "$label centerPixel RGBA=($r,$g,$b,$a)")
    }

    private fun floatBuf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(arr); it.position(0) }
}
