package com.dashcam.app.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** 화면 전체를 채우는 사각형(triangle strip) 정점/텍스처 좌표 */
class Drawable2d {

    companion object {
        const val COORDS_PER_VERTEX = 2
        const val VERTEX_COUNT = 4

        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, 1.0f
        )

        private val TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
    }

    val vertexArray: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(VERTEX_COORDS)
            position(0)
        }

    val texCoordArray: FloatBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(TEX_COORDS)
            position(0)
        }

    val vertexStride = COORDS_PER_VERTEX * 4
    val texCoordStride = 2 * 4
}
