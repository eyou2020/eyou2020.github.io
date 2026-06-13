package com.dashcam.app.gl

import android.opengl.EGLSurface
import android.view.Surface

/** EglCore + 출력 대상 Surface(여기서는 MediaCodec 인코더의 입력 Surface)를 묶은 EGL window surface */
class WindowSurface(private val eglCore: EglCore, surface: Surface) {

    private var eglSurface: EGLSurface = eglCore.createWindowSurface(surface)

    fun makeCurrent() = eglCore.makeCurrent(eglSurface)

    fun swapBuffers(): Boolean = eglCore.swapBuffers(eglSurface)

    fun setPresentationTime(nsecs: Long) = eglCore.setPresentationTime(eglSurface, nsecs)

    fun release() {
        eglCore.releaseSurface(eglSurface)
    }
}
