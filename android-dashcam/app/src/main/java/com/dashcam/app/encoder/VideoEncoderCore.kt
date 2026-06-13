package com.dashcam.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 비디오 인코더. GL이 그린 프레임을 [inputSurface]를 통해 받아 인코딩 후 muxer에 기록한다.
 */
class VideoEncoderCore(width: Int, height: Int, bitRate: Int, private val muxer: MuxerWrapper) {

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 1
    }

    val inputSurface: Surface
    private val encoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    /** GL이 swapBuffers 한 직후 호출 — 인코딩된 출력을 muxer로 보낸다 */
    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) encoder.signalEndOfInputStream()

        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                }
                index >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(index)
                        ?: throw RuntimeException("video encoder output buffer $index was null")

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(index, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun release() {
        encoder.stop()
        encoder.release()
    }
}
