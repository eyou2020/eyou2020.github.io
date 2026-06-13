package com.dashcam.app

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import com.dashcam.app.encoder.AudioEncoderCore
import com.dashcam.app.encoder.MuxerWrapper
import com.dashcam.app.encoder.VideoEncoderCore
import com.dashcam.app.gl.EglCore
import com.dashcam.app.gl.FullFrameRect
import com.dashcam.app.gl.GlUtil
import com.dashcam.app.gl.Texture2dProgram
import com.dashcam.app.gl.WindowSurface
import java.io.FileDescriptor

/**
 * 카메라 프레임(SurfaceTexture, GL_TEXTURE_EXTERNAL_OES)과
 * Canvas로 그린 오버레이 비트맵(GL_TEXTURE_2D, DrawText 결과)을
 * OpenGL ES로 합성(GPU 단)하여 MediaCodec 인코더 입력 Surface에 그린다.
 * → 위치/속도/시간 등 오버레이 그래픽이 저장되는 영상 파일에 그대로 포함된다.
 */
class OverlayRecorder {

    companion object {
        private const val TAG = "OverlayRecorder"
        private const val VIDEO_BIT_RATE = 10_000_000
    }

    @Volatile private var thread: RecorderThread? = null

    val isRecording: Boolean get() = thread != null

    /** 카메라 캡처 세션에 출력 대상으로 추가해야 하는 Surface (녹화용) */
    val cameraInputSurface: Surface?
        get() = thread?.cameraSurface

    /** 인코더/Muxer/EGL을 초기화하고, 카메라가 그릴 Surface가 준비될 때까지 대기한다 */
    fun start(videoSize: Size, outputFd: FileDescriptor) {
        val t = RecorderThread(videoSize, outputFd)
        t.start()
        t.waitUntilReady()
        val err = t.initError
        if (err != null) throw err
        thread = t
    }

    /** 최신 오버레이 비트맵을 GL 텍스처로 업로드 (다음 프레임부터 합성됨) */
    fun updateOverlay(bitmap: Bitmap) {
        thread?.postUpdateOverlay(bitmap)
    }

    /** 인코더 flush + muxer 종료. 영상 파일을 완성한다 (중복 호출에도 안전) */
    @Synchronized
    fun stop() {
        val t = thread ?: return
        thread = null
        t.shutdown()
    }

    private class RecorderThread(
        private val videoSize: Size,
        private val outputFd: FileDescriptor
    ) : Thread("OverlayRecorderThread") {

        var cameraSurface: Surface? = null
            private set

        /** run() 초기화 단계에서 발생한 예외 (waitUntilReady() 이후 확인) */
        var initError: Throwable? = null
            private set

        private lateinit var handler: Handler

        private val readyLock = Object()
        private var ready = false

        private lateinit var eglCore: EglCore
        private lateinit var windowSurface: WindowSurface
        private lateinit var cameraTexture: SurfaceTexture
        private lateinit var cameraDrawer: FullFrameRect
        private var cameraTextureId = -1
        private val cameraTexMatrix = FloatArray(16)

        private lateinit var overlayDrawer: FullFrameRect
        private var overlayTextureId = -1
        private var hasOverlay = false

        private lateinit var muxer: MuxerWrapper
        private lateinit var videoEncoder: VideoEncoderCore
        private lateinit var audioEncoder: AudioEncoderCore

        private var recordingStartNs = 0L

        fun waitUntilReady() {
            synchronized(readyLock) {
                while (!ready) readyLock.wait()
            }
        }

        fun postUpdateOverlay(bitmap: Bitmap) {
            handler.post { updateOverlayTexture(bitmap) }
        }

        fun shutdown() {
            handler.post {
                releaseResources()
                Looper.myLooper()?.quitSafely()
            }
            try { join(5000) } catch (e: InterruptedException) { Log.e(TAG, "join error", e) }
        }

        override fun run() {
            try {
                recordingStartNs = System.nanoTime()

                // ── 인코더 & Muxer 준비 (영상 + 음성, 두 트랙) ──
                muxer = MuxerWrapper(outputFd, expectedTrackCount = 2)
                videoEncoder = VideoEncoderCore(videoSize.width, videoSize.height, VIDEO_BIT_RATE, muxer)
                audioEncoder = AudioEncoderCore(muxer, recordingStartNs)

                // ── EGL 컨텍스트를 인코더 입력 Surface에 연결 ──
                eglCore = EglCore(flags = EglCore.FLAG_RECORDABLE)
                windowSurface = WindowSurface(eglCore, videoEncoder.inputSurface)
                windowSurface.makeCurrent()

                // ── 카메라 프레임용 외부 텍스처 + SurfaceTexture ──
                cameraDrawer = FullFrameRect(Texture2dProgram(Texture2dProgram.TEXTURE_EXT))
                cameraTextureId = cameraDrawer.createTextureObject()
                cameraTexture = SurfaceTexture(cameraTextureId)
                cameraTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
                cameraSurface = Surface(cameraTexture)

                // ── 오버레이(텍스트/그래픽) 텍스처 프로그램 ──
                overlayDrawer = FullFrameRect(Texture2dProgram(Texture2dProgram.TEXTURE_2D))
                overlayTextureId = overlayDrawer.createTextureObject()

                audioEncoder.start()

                Looper.prepare()
                handler = Handler(Looper.myLooper()!!)

                cameraTexture.setOnFrameAvailableListener({ handler.post { drawFrame() } }, null)
            } catch (e: Throwable) {
                initError = e
                cleanupPartialInit()
            }

            synchronized(readyLock) {
                ready = true
                readyLock.notifyAll()
            }

            if (initError != null) return
            Looper.loop()
        }

        /** 초기화 중 실패 시 이미 생성된 리소스를 best-effort로 정리 */
        private fun cleanupPartialInit() {
            try { if (this::audioEncoder.isInitialized) { audioEncoder.stop(); audioEncoder.release() } } catch (_: Exception) {}
            try { if (this::videoEncoder.isInitialized) videoEncoder.release() } catch (_: Exception) {}
            try { if (this::muxer.isInitialized) muxer.release() } catch (_: Exception) {}
            try { if (this::cameraDrawer.isInitialized) cameraDrawer.release() } catch (_: Exception) {}
            try { if (this::overlayDrawer.isInitialized) overlayDrawer.release() } catch (_: Exception) {}
            try { if (this::cameraTexture.isInitialized) cameraTexture.release() } catch (_: Exception) {}
            try { cameraSurface?.release() } catch (_: Exception) {}
            try { if (this::windowSurface.isInitialized) windowSurface.release() } catch (_: Exception) {}
            try { if (this::eglCore.isInitialized) eglCore.release() } catch (_: Exception) {}
        }

        private fun drawFrame() {
            windowSurface.makeCurrent()

            cameraTexture.updateTexImage()
            cameraTexture.getTransformMatrix(cameraTexMatrix)

            GLES20.glViewport(0, 0, videoSize.width, videoSize.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 1) 카메라 프레임 → 배경
            cameraDrawer.drawFrame(cameraTextureId, cameraTexMatrix)

            // 2) 오버레이(GPS/속도/시간 등 DrawText 결과) → 알파 블렌딩으로 합성
            if (hasOverlay) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                overlayDrawer.drawFrame(overlayTextureId, GlUtil.FLIP_MATRIX_Y)
                GLES20.glDisable(GLES20.GL_BLEND)
            }

            val ptsNs = maxOf(0L, cameraTexture.timestamp - recordingStartNs)
            windowSurface.setPresentationTime(ptsNs)
            windowSurface.swapBuffers()

            videoEncoder.drainEncoder(false)
        }

        private fun updateOverlayTexture(bitmap: Bitmap) {
            windowSurface.makeCurrent()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GlUtil.checkGlError("texImage2D overlay")
            hasOverlay = true
        }

        private fun releaseResources() {
            try {
                videoEncoder.drainEncoder(true)
            } catch (e: Exception) {
                Log.e(TAG, "final drain error", e)
            }
            audioEncoder.stop()
            audioEncoder.release()
            videoEncoder.release()
            muxer.release()

            cameraDrawer.release()
            overlayDrawer.release()
            cameraTexture.release()
            cameraSurface?.release()
            windowSurface.release()
            eglCore.release()
        }
    }
}
