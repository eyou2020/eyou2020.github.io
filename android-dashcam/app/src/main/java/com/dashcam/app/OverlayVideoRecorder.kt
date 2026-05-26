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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class OverlayVideoRecorder(
    private val context: Context,
    private val videoSize: Size
) {
    companion object {
        private const val TAG = "OverlayRecorder"
        private const val MIME_VIDEO = "video/avc"
        private const val MIME_AUDIO = "audio/mp4a-latm"
        private const val VIDEO_BITRATE = 8_000_000
        private const val FRAME_RATE = 30
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000

        // Set true to fill the overlay with solid RED instead of text.
        // If the video turns red → GL pipeline is correct, fix is the blend formula / text paint.
        // If the video stays camera-only → Pass 2 is not reaching the encoder at all.
        private const val DEBUG_RED_OVERLAY = true

        // Camera OES texture — applies SurfaceTexture transform matrix
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

        // Plain 2-D overlay texture — guide's exact variable names to avoid confusion with camera shader
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

    // Camera input surface — hand this to Camera2's capture session
    var cameraSurface: Surface? = null
        private set

    // ── Overlay data with dirty-flag backing ──────────────────────────────
    // Written from any thread; render thread checks overlayDirty each frame.
    private val overlayDirty = AtomicBoolean(true)

    @Volatile private var _overlayLocation = ""
    @Volatile private var _overlayAddress  = ""
    @Volatile private var _overlaySpeed    = "0 km/h"

    var overlayLocation: String
        get() = _overlayLocation
        set(value) { _overlayLocation = value; overlayDirty.set(true) }
    var overlayAddress: String
        get() = _overlayAddress
        set(value) { _overlayAddress = value; overlayDirty.set(true) }
    var overlaySpeed: String
        get() = _overlaySpeed
        set(value) { _overlaySpeed = value; overlayDirty.set(true) }

    private val running = AtomicBoolean(false)

    // Render thread (all EGL + GL work must be on the thread that owns the context)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    // Audio thread
    private var audioThread: Thread? = null

    // EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE   // ← encoder input surface

    // GL objects
    private var camProgram     = 0
    private var overlayProgram = 0
    private var cameraTexId    = 0
    private var overlayTexId   = 0
    private lateinit var camSt: SurfaceTexture
    private val stMatrix = FloatArray(16)

    // Cached camera program handles — populated once in setupGL() after link.
    private var camPosLoc  = -1   // "aPosition"
    private var camTexLoc  = -1   // "aTexCoord"
    private var camStmLoc  = -1   // "uSTMatrix"
    private var camSampLoc = -1   // "uTexture"

    // Cached overlay program handles — populated once in setupGL() after link.
    // Querying these per-frame with glGetAttribLocation is expensive and error-prone.
    private var ovPosLoc    = -1   // "aPosition"
    private var ovTexLoc    = -1   // "aTextureCoord"
    private var ovSampLoc   = -1   // "uOverlayTexture"

    // Camera quad buffers (separate position + UV, camera STMatrix handles orientation)
    private lateinit var vertBuf: FloatBuffer
    private lateinit var texBuf:  FloatBuffer

    // Overlay quad — interleaved (x,y, u,v) per vertex as recommended by the guide.
    // Both position and texcoord in one buffer eliminates any per-attribute pointer misalignment.
    //   NDC (-1,-1)=screen-bottom-left  →  UV (0,1)=canvas-bottom-left  (speed text)
    //   NDC (-1, 1)=screen-top-left     →  UV (0,0)=canvas-top-left     (GPS text)
    private lateinit var overlayCoordBuf: FloatBuffer

    // Overlay bitmap — reused across frames, rebuilt when dirty or ~once per second
    private var overlayBitmap: Bitmap? = null
    private var frameCount = 0

    // Encoders / muxer
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var muxerStarted = false
    // First camera frame's SurfaceTexture timestamp — used to zero-base video PTS so
    // that video and audio tracks start at the same reference point in the muxer.
    private var startCamNs = 0L
    private var outputPfd: ParcelFileDescriptor? = null

    // Single lock for MediaMuxer — it is NOT thread-safe
    private val muxerLock = Any()

    private val vInfo = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()

    // ── Public API ────────────────────────────────────────────────────────

    fun start(onReady: (Surface) -> Unit) {
        running.set(true)
        renderThread = HandlerThread("OVR-render").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        renderHandler!!.post {
            setupEncoders()
            setupEGL()
            setupGL()
            setupCameraSurface()
            startAudio()
            onReady(cameraSurface!!)
        }
    }

    fun stop(onDone: () -> Unit) {
        running.set(false)
        renderHandler?.post {
            drainVideo(eos = true)
            audioThread?.join(3000)
            drainAudioFinal()
            releaseAll()
            Handler(Looper.getMainLooper()).post(onDone)
        }
    }

    // ── Encoder / Muxer setup ─────────────────────────────────────────────

    private fun setupEncoders() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DashCam_$ts.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv
            )!!
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
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MIME_VIDEO).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val af = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioEncoder = MediaCodec.createEncoderByType(MIME_AUDIO).also {
            it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    // ── EGL — single surface wired to the encoder input ───────────────────

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, n, 0)

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)

        // Wire the encoder's input surface to EGL — ALL eglSwapBuffers calls go to the encoder
        val encoderInputSurface = videoEncoder!!.createInputSurface()
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, cfgs[0], encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        // One EGL surface only — both Pass 1 (camera) and Pass 2 (overlay) render here.
        // There is no display/preview surface in this EGL context; preview is handled by
        // Camera2 → TextureView on the main thread, completely separate from recording.
        Log.d(TAG, "EGL ready — single encoderSurface=$eglSurface  context=$eglContext")

        videoEncoder!!.start()
    }

    // ── OpenGL setup ──────────────────────────────────────────────────────

    private fun setupGL() {
        camProgram     = buildProgram(VS_CAMERA,  FS_CAMERA)
        overlayProgram = buildProgram(VS_OVERLAY, FS_OVERLAY)

        val ids = IntArray(2); GLES20.glGenTextures(2, ids, 0)
        cameraTexId  = ids[0]
        overlayTexId = ids[1]
        Log.d(TAG, "glGenTextures → cameraTexId=$cameraTexId  overlayTexId=$overlayTexId")
        checkGlError("glGenTextures")

        // Both textures live on unit 0.
        // GL_TEXTURE_EXTERNAL_OES and GL_TEXTURE_2D are separate targets on the same unit;
        // the sampler type in the active program selects which binding is read.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        checkGlError("camera OES tex params")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("overlay 2D tex params")

        // Camera quad — separate position and UV buffers (STMatrix in VS handles orientation)
        vertBuf = floatBuf(floatArrayOf(-1f,-1f,  1f,-1f,  -1f,1f,  1f,1f))
        texBuf  = floatBuf(floatArrayOf( 0f, 0f,  1f, 0f,   0f,1f,  1f,1f))

        // Overlay quad — interleaved (x, y, u, v), four vertices × 4 floats = 16 bytes stride
        overlayCoordBuf = floatBuf(floatArrayOf(
            -1f, -1f,  0f, 1f,   // bottom-left screen  → canvas bottom-left (speed)
             1f, -1f,  1f, 1f,   // bottom-right screen → canvas bottom-right
            -1f,  1f,  0f, 0f,   // top-left screen     → canvas top-left    (GPS / time)
             1f,  1f,  1f, 0f    // top-right screen    → canvas top-right
        ))

        // Cache camera program handles
        camPosLoc  = GLES20.glGetAttribLocation(camProgram,  "aPosition")
        camTexLoc  = GLES20.glGetAttribLocation(camProgram,  "aTexCoord")
        camStmLoc  = GLES20.glGetUniformLocation(camProgram, "uSTMatrix")
        camSampLoc = GLES20.glGetUniformLocation(camProgram, "uTexture")
        Log.d(TAG, "camera  program locs — pos=$camPosLoc  tex=$camTexLoc  stm=$camStmLoc  samp=$camSampLoc")

        // Cache overlay program handles
        ovPosLoc  = GLES20.glGetAttribLocation(overlayProgram,  "aPosition")
        ovTexLoc  = GLES20.glGetAttribLocation(overlayProgram,  "aTextureCoord")
        ovSampLoc = GLES20.glGetUniformLocation(overlayProgram, "uOverlayTexture")
        Log.d(TAG, "overlay program locs — pos=$ovPosLoc  tex=$ovTexLoc  samp=$ovSampLoc")
    }

    private fun setupCameraSurface() {
        // Ensure the OES texture is on unit 0 before SurfaceTexture is created — some
        // drivers call glBindTexture internally during the first updateTexImage().
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        camSt = SurfaceTexture(cameraTexId)
        camSt.setDefaultBufferSize(videoSize.width, videoSize.height)
        cameraSurface = Surface(camSt)
        camSt.setOnFrameAvailableListener({ renderFrame() }, renderHandler)
    }

    // ── Per-frame render ──────────────────────────────────────────────────

    private fun renderFrame() {
        if (!running.get()) return

        // updateTexImage() calls glBindTexture(GL_TEXTURE_EXTERNAL_OES, ...) on whatever
        // unit is active — always force unit 0 first so the OES bind lands in the right place.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        camSt.updateTexImage()
        camSt.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawCameraTexture()   // Pass 1: camera → encoder surface

        // Bake overlay when data changed (dirty flag) or once per second (clock tick)
        frameCount++
        if (overlayDirty.getAndSet(false) || frameCount % FRAME_RATE == 0 || overlayBitmap == null) {
            bakeOverlay()
        }

        // Re-assert viewport before Pass 2 — some drivers shrink or invalidate it
        // after the first draw call when the encoder surface dimensions differ from display.
        GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
        drawOverlayTexture()  // Pass 2: GPS/time/speed on top

        // Use the camera SurfaceTexture hardware timestamp (nanoseconds, monotonically
        // increasing) as the video PTS.  Zero-base it against the first frame so that
        // video and audio tracks both start at ~0 µs, preventing MediaMuxer from
        // misinterpreting the huge absolute clock value as a multi-day offset.
        if (startCamNs == 0L) startCamNs = camSt.timestamp
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, camSt.timestamp - startCamNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)  // delivers completed frame to encoder

        drainVideo(eos = false)
    }

    private fun drawCameraTexture() {
        GLES20.glUseProgram(camProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniform1i(camSampLoc, 0)
        GLES20.glUniformMatrix4fv(camStmLoc, 1, false, stMatrix, 0)

        // Pointer-before-Enable (same order as overlay pass for consistency)
        vertBuf.rewind()
        GLES20.glVertexAttribPointer(camPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(camPosLoc)
        texBuf.rewind()
        GLES20.glVertexAttribPointer(camTexLoc, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(camTexLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(camPosLoc)
        GLES20.glDisableVertexAttribArray(camTexLoc)

        // Unbind the OES target from unit 0 after Pass 1 so that Pass 2's sampler2D
        // reads only the 2D binding — having both targets bound on the same unit while
        // sampling with sampler2D is undefined on some drivers.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun bakeOverlay() {
        val w = videoSize.width; val h = videoSize.height
        if (overlayBitmap == null)
            overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val bmp = overlayBitmap!!
        val canvas = Canvas(bmp)

        // ── Diagnostic: fill solid red to verify the GL pipeline reaches the encoder ──
        // If the recorded video turns completely red, Pass 2 is rendering correctly and
        // the issue is purely in the blend formula or text drawing.  Flip to false when done.
        if (DEBUG_RED_OVERLAY) {
            canvas.drawColor(Color.RED)
            uploadOverlayBitmap(bmp)
            return
        }

        // eraseColor is a direct pixel fill — no xfermode, no Porter-Duff compositing path,
        // guaranteed to produce a fully-transparent bitmap even on Canvas edge cases.
        bmp.eraseColor(Color.TRANSPARENT)

        val textSz = h * 0.065f
        val margin = w * 0.015f
        val lineH  = textSz * 1.35f

        // Paint: FILL style, no xfermode, anti-aliased — works on software Bitmap Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style    = Paint.Style.FILL
            color    = Color.WHITE
            textSize = textSz
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // Shadow is ignored on software canvas; use a stroke outline pass instead
        }

        // Semi-transparent black outline drawn first for readability on any background
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

        // Use fontMetrics for precise baseline placement so text never clips outside the bitmap.
        // fm.top is negative (distance from baseline to highest ascender).
        // fm.bottom is positive (distance from baseline to lowest descender).
        // → To place the text *top* at pixel `py`: baseline = py - fm.top
        // → To place the text *bottom* at pixel `py`: baseline = py - fm.bottom
        val fm = paint.fontMetrics

        // Top-left: lat/lon + address — top of first line at `margin`
        var y = margin - fm.top
        _overlayLocation.split("\n").forEach { line ->
            if (line.isNotBlank()) { drawLabeled(line.trim(), margin, y); y += lineH }
        }
        if (_overlayAddress.isNotEmpty()) drawLabeled(_overlayAddress, margin, y)

        // Top-right: date/time — top of text at `margin`
        val now = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
        drawLabeled(now, w - paint.measureText(now) - margin, margin - fm.top)

        // Bottom-left: speed — bottom of text at `h - margin`
        drawLabeled(_overlaySpeed, margin, h - margin - fm.bottom)

        uploadOverlayBitmap(bmp)
    }

    private fun uploadOverlayBitmap(bmp: Bitmap) {
        // OES was already unbound after Pass 1; bind 2D target on unit 0 for upload
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        // Re-assert filter/wrap params every upload — some drivers reset them after texImage2D
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        checkGlError("texImage2D overlay")
    }

    private fun drawOverlayTexture() {
        // Rewind guarantees position=0 and limit=capacity regardless of any earlier
        // position() calls that may have been left in a partial state.
        overlayCoordBuf.rewind()

        // Skip draw if program failed to link (cached handles would be -1)
        if (ovPosLoc < 0 || ovTexLoc < 0) {
            Log.w(TAG, "drawOverlayTexture: invalid cached attrib locs — skip")
            return
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        // Android Bitmap.ARGB_8888 uses pre-multiplied alpha: GL_ONE avoids double-multiplication
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(overlayProgram)

        // Bind 2D texture on unit 0 (OES was unbound in drawCameraTexture)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glUniform1i(ovSampLoc, 0)

        // Interleaved buffer: stride = 4 floats × 4 bytes = 16 bytes.
        // Guide recommends setting the pointer BEFORE enabling the array.
        val stride = 16
        overlayCoordBuf.position(0)
        GLES20.glVertexAttribPointer(ovPosLoc, 2, GLES20.GL_FLOAT, false, stride, overlayCoordBuf)
        GLES20.glEnableVertexAttribArray(ovPosLoc)

        overlayCoordBuf.position(2)   // skip 2 position floats (8 bytes) to reach UV offset
        GLES20.glVertexAttribPointer(ovTexLoc, 2, GLES20.GL_FLOAT, false, stride, overlayCoordBuf)
        GLES20.glEnableVertexAttribArray(ovTexLoc)

        overlayCoordBuf.rewind()   // position(0) before draw so captured offset is 0
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("drawOverlayTexture")

        GLES20.glDisableVertexAttribArray(ovPosLoc)
        GLES20.glDisableVertexAttribArray(ovTexLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
        overlayCoordBuf.rewind()   // reset for next frame
    }

    // ── Video codec drain ─────────────────────────────────────────────────

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
                        synchronized(muxerLock) {
                            muxer!!.writeSampleData(videoTrack, buf, vInfo)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        }
    }

    // ── Audio thread ──────────────────────────────────────────────────────

    private fun startAudio() {
        audioThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            @SuppressLint("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
            rec.startRecording()
            val buf = ByteArray(minBuf)
            var totalSamples = 0L

            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val ptsUs = totalSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                    encodeAudio(buf, n, ptsUs, eos = false)
                    totalSamples += n / 2   // 16-bit PCM → 2 bytes per sample
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
                        synchronized(muxerLock) {
                            muxer!!.writeSampleData(audioTrack, buf, aInfo)
                        }
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
                        synchronized(muxerLock) {
                            muxer!!.writeSampleData(audioTrack, buf, aInfo)
                        }
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
            Log.d(TAG, "Muxer started — videoTrack=$videoTrack audioTrack=$audioTrack")
        }
    }

    // ── Release ───────────────────────────────────────────────────────────

    private fun releaseAll() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(eglDisplay, eglSurface); eglSurface = EGL14.EGL_NO_SURFACE }
        if (eglContext != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(eglDisplay, eglContext); eglContext = EGL14.EGL_NO_CONTEXT }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) { EGL14.eglTerminate(eglDisplay); eglDisplay = EGL14.EGL_NO_DISPLAY }

        cameraSurface?.release(); cameraSurface = null
        if (::camSt.isInitialized) camSt.release()

        try { videoEncoder?.stop() } catch (_: Exception) {}
        videoEncoder?.release(); videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        audioEncoder?.release(); audioEncoder = null

        try { if (muxerStarted) muxer?.stop(); muxer?.release() }
        catch (e: Exception) { Log.e(TAG, "muxer release", e) }
        muxer = null; muxerStarted = false

        outputPfd?.close(); outputPfd = null
        overlayBitmap?.recycle(); overlayBitmap = null
        renderThread?.quitSafely()
    }

    // ── GL helpers ────────────────────────────────────────────────────────

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            }
            return shader
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vsSrc))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
        }
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
