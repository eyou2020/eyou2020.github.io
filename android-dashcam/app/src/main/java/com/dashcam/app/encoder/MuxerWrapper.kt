package com.dashcam.app.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * 비디오/오디오 인코더 스레드가 동시에 사용하는 MediaMuxer 래퍼.
 * 두 트랙(영상+음성)이 모두 addTrack 되어야 muxer.start()가 호출된다.
 */
class MuxerWrapper(fd: FileDescriptor, private val expectedTrackCount: Int) {

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var addedTrackCount = 0
    private var started = false

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val index = muxer.addTrack(format)
        addedTrackCount++
        if (addedTrackCount == expectedTrackCount) {
            muxer.start()
            started = true
        }
        return index
    }

    @Synchronized
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        muxer.writeSampleData(trackIndex, buffer, info)
    }

    @Synchronized
    fun release() {
        if (started) {
            try { muxer.stop() } catch (e: Exception) { /* ignore */ }
        }
        muxer.release()
    }
}
