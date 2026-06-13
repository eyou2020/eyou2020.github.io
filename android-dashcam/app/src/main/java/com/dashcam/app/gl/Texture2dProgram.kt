package com.dashcam.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * 텍스처를 화면(또는 인코더 입력 Surface)에 그리는 셰이더 프로그램.
 *  - TEXTURE_EXT : 카메라 SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *  - TEXTURE_2D  : Canvas로 그린 오버레이 비트맵 (GL_TEXTURE_2D, alpha 블렌딩)
 */
class Texture2dProgram(private val textureTarget: Int) {

    companion object {
        const val TEXTURE_2D = GLES20.GL_TEXTURE_2D
        const val TEXTURE_EXT = GLES11Ext.GL_TEXTURE_EXTERNAL_OES

        private const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """

        private const val FRAGMENT_SHADER_EXT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """
    }

    private val programHandle: Int
    private val uTexMatrixLoc: Int
    private val uAlphaLoc: Int
    private val aPositionLoc: Int
    private val aTextureCoordLoc: Int
    private val uTextureLoc: Int

    init {
        val fragmentShader = if (textureTarget == TEXTURE_EXT) FRAGMENT_SHADER_EXT else FRAGMENT_SHADER_2D
        programHandle = GlUtil.createProgram(VERTEX_SHADER, fragmentShader)
        uTexMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
        uAlphaLoc = GLES20.glGetUniformLocation(programHandle, "uAlpha")
        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programHandle, "sTexture")
    }

    fun createTextureObject(): Int = GlUtil.createTextureObject(textureTarget)

    fun release() {
        GLES20.glDeleteProgram(programHandle)
    }

    fun draw(
        vertexBuffer: FloatBuffer,
        vertexStride: Int,
        texCoordBuffer: FloatBuffer,
        texCoordStride: Int,
        texMatrix: FloatArray,
        textureId: Int,
        alpha: Float
    ) {
        GlUtil.checkGlError("draw start")
        GLES20.glUseProgram(programHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(
            aPositionLoc, Drawable2d.COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, vertexStride, vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(
            aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, texCoordStride, texCoordBuffer
        )

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uAlphaLoc, alpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, Drawable2d.VERTEX_COUNT)
        GlUtil.checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(textureTarget, 0)
    }
}
