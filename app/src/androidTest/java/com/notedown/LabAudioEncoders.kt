package com.notedown

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.notedown.audio.AacEncoder
import java.io.File
import java.io.RandomAccessFile

internal object LabMp3Encoder {
    init { System.loadLibrary("codec_lab_lame") }
    external fun encode(input: String, output: String, bitrate: Int)
}

/** Experimental MP3/AAC32 helpers; AAC64 exercises the production upload encoder. */
internal object LabAudioEncoders {
    suspend fun encode(wav: File, output: File, variant: String) {
        when {
            variant.startsWith("mp3") -> LabMp3Encoder.encode(wav.absolutePath, output.absolutePath, variant.substringAfter('_').toInt())
            variant == "aac_64" -> AacEncoder().encode(wav, output)
            variant.startsWith("aac") -> aac(wav, output, variant.substringAfter('_').toInt() * 1000)
            else -> error("Unsupported experimental encoder")
        }
    }

    private fun aac(input: File, output: File, bitrate: Int) {
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 16000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(16384)
            var inputDone = false
            var outputDone = false
            var frames = 0L
            var track = -1
            val deadline = SystemClock.elapsedRealtime() + 30000
            fun drain(index: Int): Boolean {
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                    return true
                }
                if (index < 0) return false
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    check(started)
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(track, buffer, info)
                }
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                return true
            }
            RandomAccessFile(input, "r").use { source ->
                source.seek(44)
                while (!outputDone) {
                    check(SystemClock.elapsedRealtime() < deadline) { "AAC encoder stalled" }
                    var progressed = false
                    while (!outputDone && drain(codec.dequeueOutputBuffer(info, 0))) progressed = true
                    while (!inputDone && !outputDone) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            var count = source.read(bytes, 0, minOf(bytes.size, buffer.remaining()))
                            if (count < 0) {
                                codec.queueInputBuffer(index, 0, 0, frames * 1000000 / 16000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                check(count % 2 == 0)
                                // The platform encoder otherwise drops a partial final 1024-sample frame.
                                val padded = (count + 2047) / 2048 * 2048
                                check(padded <= bytes.size && padded <= buffer.remaining())
                                bytes.fill(0, count, padded)
                                count = padded
                                buffer.put(bytes, 0, count)
                                codec.queueInputBuffer(index, 0, count, frames * 1000000 / 16000, 0)
                                frames += count / 2
                            }
                            progressed = true
                        } else break
                    }
                    if (!progressed && !outputDone) drain(codec.dequeueOutputBuffer(info, 10000))
                }
            }
        } finally {
            if (started) muxer.stop()
            muxer.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }
}
