package com.dashcam.app.encoder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * AAC 오디오 인코더. AudioRecord로 마이크 PCM을 읽어 MediaCodec으로 인코딩 후 muxer에 기록한다.
 * [recordingStartNs]는 영상 프레임 타임스탬프와 동일한 기준시각(System.nanoTime())으로,
 * 오디오/영상의 presentation time을 같은 기준으로 맞추기 위함이다.
 */
class AudioEncoderCore(private val muxer: MuxerWrapper, private val recordingStartNs: Long) {

    companion object {
        private const val TAG = "AudioEncoderCore"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 128_000
    }

    private val encoder: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var running = false

    init {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = minBufferSize * 4

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        encoder.start()
        running = true
        audioRecord?.startRecording()

        recordingThread = thread(name = "AudioEncoderThread") {
            val buf = ByteArray(bufferSize)
            while (running) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) feedEncoder(buf, read, false)
                drainEncoder(false)
            }
            feedEncoder(ByteArray(0), 0, true)
            drainEncoder(true)
        }
    }

    private fun feedEncoder(data: ByteArray, length: Int, endOfStream: Boolean) {
        val index = encoder.dequeueInputBuffer(10_000)
        if (index >= 0) {
            val inputBuffer = encoder.getInputBuffer(index)
            inputBuffer?.clear()
            inputBuffer?.put(data, 0, length)
            val ptsUs = (System.nanoTime() - recordingStartNs) / 1000
            val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(index, 0, length, maxOf(0L, ptsUs), flags)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
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
                        ?: throw RuntimeException("audio encoder output buffer $index was null")

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

    fun stop() {
        running = false
        try { recordingThread?.join(2000) } catch (e: InterruptedException) { Log.e(TAG, "join error", e) }
        recordingThread = null
        try { audioRecord?.stop() } catch (e: Exception) { Log.e(TAG, "AudioRecord stop error", e) }
        audioRecord?.release()
        audioRecord = null
    }

    fun release() {
        encoder.stop()
        encoder.release()
    }
}
