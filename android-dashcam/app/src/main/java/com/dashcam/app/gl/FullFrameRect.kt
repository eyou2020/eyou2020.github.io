package com.dashcam.app.gl

/** 화면 전체를 채우는 사각형에 텍스처를 그리는 도우미 */
class FullFrameRect(val program: Texture2dProgram) {

    private val rectangle = Drawable2d()

    fun createTextureObject(): Int = program.createTextureObject()

    fun drawFrame(textureId: Int, texMatrix: FloatArray, alpha: Float = 1f) {
        program.draw(
            rectangle.vertexArray, rectangle.vertexStride,
            rectangle.texCoordArray, rectangle.texCoordStride,
            texMatrix, textureId, alpha
        )
    }

    fun release() {
        program.release()
    }
}
