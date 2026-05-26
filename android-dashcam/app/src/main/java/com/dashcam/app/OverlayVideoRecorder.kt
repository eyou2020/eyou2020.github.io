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

/**
 * Records camera frames with GPS/time/speed burned in via an EGL → MediaCodec pipeline.
 *
 * Usage:
 *   val recorder = OverlayVideoRecorder(context, videoSize)
 *   recorder.start { camSurface ->
 *       // pass camSurface to Camera2 capture session
 *   }
 *   recorder.stop { /* save complete */ }
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
        private const val FRAME_RATE = 30
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000

        // ── GLSL shaders ──────────────────────────────────────────────────
        // Camera OES texture (applies SurfaceTexture transform matrix)
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

        // Plain 2-D texture (overlay bitmap — no matrix needed)
        private val VS_OVERLAY = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }""".trimIndent()

        private val FS_OVERLAY = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }""".trimIndent()
    }

    // Camera input surface — hand this to Camera2's capture session
    var cameraSurface: Surface? = null
        private set

    // Overlay data; set freely from any thread
    @Volatile var overlayLocation = ""
    @Volatile var overlayAddress  = ""
    @Volatile var overlaySpeed    = "0 km/h"

    private val running = AtomicBoolean(false)

    // Render thread (EGL + GL work must be on the same thread that holds the EGL context)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    // Audio thread
    private var audioThread: Thread? = null

    // EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    // GL objects
    private var camProgram     = 0
    private var overlayProgram = 0
    private var cameraTexId    = 0
    private var overlayTexId   = 0
    private lateinit var camSt: SurfaceTexture
    private val stMatrix = FloatArray(16)

    // Pre-allocated quad buffers (initialised in setupGL)
    private lateinit var vertBuf:     FloatBuffer  // full-screen quad positions
    private lateinit var texBuf:      FloatBuffer  // camera UV (Y-normal)
    private lateinit var texFlipBuf:  FloatBuffer  // overlay UV (Y-flipped for Canvas coords)

    // Overlay bitmap — reused across frames, rebuilt once per second
    private var overlayBitmap: Bitmap? = null
    private var frameCount = 0

    // Encoders / muxer
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var muxerStarted = false
    private var startNs = 0L
    private var outputPfd: ParcelFileDescriptor? = null

    private val vInfo = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()

    // ── Public API ────────────────────────────────────────────────────────

    /** Starts the recording pipeline. [onReady] is called (on the render thread) with the
     *  Surface that Camera2 should target. */
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

    /** Stops recording, flushes encoders, closes muxer, then calls [onDone] on the main thread. */
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

        // Output file
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

        // Video encoder — configure only; start happens after EGL surface is wired
        val vf = MediaFormat.createVideoFormat(MIME_VIDEO, videoSize.width, videoSize.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MIME_VIDEO).also {
            it.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // createInputSurface() must be called before start() → done in setupEGL()
        }

        // Audio encoder
        val af = MediaFormat.createAudioFormat(MIME_AUDIO, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioEncoder = MediaCodec.createEncoderByType(MIME_AUDIO).also {
            it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    // ── EGL ───────────────────────────────────────────────────────────────

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,   // required for MediaCodec surfaces
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, n, 0)

        val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)

        // Wire the encoder's input surface to EGL, then start encoder
        val encoderInputSurface = videoEncoder!!.createInputSurface()
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, cfgs[0], encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        videoEncoder!!.start()
    }

    // ── OpenGL setup ──────────────────────────────────────────────────────

    private fun setupGL() {
        camProgram     = buildProgram(VS_CAMERA,  FS_CAMERA)
        overlayProgram = buildProgram(VS_OVERLAY, FS_OVERLAY)

        val ids = IntArray(2); GLES20.glGenTextures(2, ids, 0)
        cameraTexId  = ids[0]; overlayTexId = ids[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Pre-allocate quad buffers (avoids per-frame GC pressure)
        vertBuf    = floatBuf(floatArrayOf(-1f,-1f,  1f,-1f,  -1f,1f,  1f,1f))
        texBuf     = floatBuf(floatArrayOf( 0f, 0f,  1f, 0f,   0f,1f,  1f,1f))
        texFlipBuf = floatBuf(floatArrayOf( 0f, 1f,  1f, 1f,   0f,0f,  1f,0f))
    }

    private fun setupCameraSurface() {
        camSt = SurfaceTexture(cameraTexId)
        camSt.setDefaultBufferSize(videoSize.width, videoSize.height)
        cameraSurface = Surface(camSt)
        camSt.setOnFrameAvailableListener({ renderFrame() }, renderHandler)
    }

    // ── Per-frame render ──────────────────────────────────────────────────

    private fun renderFrame() {
        if (!running.get()) return

        camSt.updateTexImage()
        camSt.getTransformMatrix(stMatrix)

        GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawCameraTexture()

        // Redraw overlay bitmap once per second (at FRAME_RATE cadence)
        frameCount++
        if (frameCount % FRAME_RATE == 1 || overlayBitmap == null) bakeOverlay()
        drawOverlayTexture()

        // Timestamp the frame for the encoder
        val nowNs = System.nanoTime()
        if (startNs == 0L) startNs = nowNs
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nowNs - startNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        drainVideo(eos = false)
    }

    private fun drawCameraTexture() {
        GLES20.glUseProgram(camProgram)
        val pos = GLES20.glGetAttribLocation(camProgram, "aPosition")
        val tex = GLES20.glGetAttribLocation(camProgram, "aTexCoord")
        val stm = GLES20.glGetUniformLocation(camProgram, "uSTMatrix")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glUniformMatrix4fv(stm, 1, false, stMatrix, 0)

        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
    }

    /** Draws overlay text onto a Bitmap, uploads it to GPU. Called once per second. */
    private fun bakeOverlay() {
        val w = videoSize.width; val h = videoSize.height
        if (overlayBitmap == null)
            overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val bmp = overlayBitmap!!
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val textSz  = h * 0.038f
        val margin  = w * 0.015f
        val lineH   = textSz * 1.35f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color    = Color.WHITE
            textSize = textSz
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }

        // ── Top-left: lat/lon + address ──────────────────────────────────
        var y = margin + textSz
        overlayLocation.split("\n").forEach { line ->
            canvas.drawText(line.trim(), margin, y, paint)
            y += lineH
        }
        if (overlayAddress.isNotEmpty()) {
            canvas.drawText(overlayAddress, margin, y, paint)
        }

        // ── Top-right: date/time ─────────────────────────────────────────
        val now = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText(now, w - paint.measureText(now) - margin, margin + textSz, paint)

        // ── Bottom-left: speed ───────────────────────────────────────────
        canvas.drawText(overlaySpeed, margin, h - margin, paint)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    private fun drawOverlayTexture() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(overlayProgram)
        val pos = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val tex = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)

        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, vertBuf)
        GLES20.glEnableVertexAttribArray(tex)
        // Y-flipped coords: GL's origin is bottom-left, Canvas origin is top-left
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texFlipBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
        GLES20.glDisable(GLES20.GL_BLEND)
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
                        muxer!!.writeSampleData(videoTrack, buf, vInfo)
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
                    totalSamples += n / 2   // 16-bit PCM: 2 bytes per sample
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
                        muxer!!.writeSampleData(audioTrack, buf, aInfo)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                }
            }
        }
    }

    /** Called on the render thread after the audio thread has joined. */
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
                        muxer!!.writeSampleData(audioTrack, buf, aInfo)
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
        }
    }

    // ── Release ───────────────────────────────────────────────────────────

    private fun releaseAll() {
        // EGL
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(eglDisplay, eglSurface); eglSurface = EGL14.EGL_NO_SURFACE }
        if (eglContext != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(eglDisplay, eglContext); eglContext = EGL14.EGL_NO_CONTEXT }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) { EGL14.eglTerminate(eglDisplay); eglDisplay = EGL14.EGL_NO_DISPLAY }

        // Camera surface / texture
        cameraSurface?.release(); cameraSurface = null
        if (::camSt.isInitialized) camSt.release()

        // Encoders
        try { videoEncoder?.stop() } catch (_: Exception) {}
        videoEncoder?.release(); videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Exception) {}
        audioEncoder?.release(); audioEncoder = null

        // Muxer
        try { if (muxerStarted) muxer?.stop(); muxer?.release() }
        catch (e: Exception) { Log.e(TAG, "muxer release", e) }
        muxer = null; muxerStarted = false

        outputPfd?.close(); outputPfd = null
        overlayBitmap?.recycle(); overlayBitmap = null
        renderThread?.quitSafely()
    }

    // ── GL helpers ────────────────────────────────────────────────────────

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vsSrc))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fsSrc))
            GLES20.glLinkProgram(it)
        }
    }

    private fun floatBuf(arr: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(arr); it.position(0) }
}
