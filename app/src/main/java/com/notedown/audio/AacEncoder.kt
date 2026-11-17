package com.notedown.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/** Compresses a canonical trimmed WAV into a private 64 kbps AAC-LC/M4A upload copy. */
class AacEncoder @Inject constructor() {
    suspend fun encode(input: File, output: File) = withContext(Dispatchers.IO) {
        require(input.canonicalPath != output.canonicalPath)
        var complete = false
        try {
            RandomAccessFile(input, "r").use { source ->
                val header = ByteArray(44)
                source.readFully(header)
                val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                    fields.getInt(16) == 16 && fields.getShort(20).toInt() == 1 &&
                    fields.getShort(22).toInt() == 1 && fields.getInt(24) == SAMPLE_RATE &&
                    fields.getShort(32).toInt() == 2 && fields.getShort(34).toInt() == 16 &&
                    String(header, 36, 4, Charsets.US_ASCII) == "data") { "Unsupported WAV for compression." }
                var remaining = fields.getInt(40)
                require(remaining > 0 && remaining % 2 == 0 && source.length() == remaining.toLong() + 44 &&
                    source.length() < MAX_UPLOAD_BYTES) { "Invalid WAV for compression." }
                ensureActive()
                val codec = MediaCodec.createEncoderByType(MIME)
                var muxer: MediaMuxer? = null
                var codecStarted = false
                var muxerStarted = false
                try {
                    val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = writer
                    codec.configure(MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_BYTES)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    codecStarted = true
                    val info = MediaCodec.BufferInfo()
                    val bytes = ByteArray(BUFFER_BYTES)
                    var frames = 0L
                    var flushBytes = FLUSH_FRAMES * FRAME_BYTES
                    var inputDone = false
                    var outputDone = false
                    var payloadBytes = 0L
                    var track = -1
                    var lastProgress = SystemClock.elapsedRealtime()
                    fun drain(index: Int): Boolean {
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted)
                            track = writer.addTrack(codec.outputFormat)
                            writer.start()
                            muxerStarted = true
                            return true
                        }
                        if (index < 0) return false
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted)
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                writer.writeSampleData(track, buffer, info)
                                payloadBytes += info.size
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        return true
                    }
                    while (!outputDone) {
                        ensureActive()
                        check(SystemClock.elapsedRealtime() - lastProgress < STALL_TIMEOUT_MS) { "Audio compression stalled." }
                        var progressed = false
                        // Drain before feeding more PCM; blocking for input with pending output is slow.
                        while (!outputDone && drain(codec.dequeueOutputBuffer(info, 0))) {
                            ensureActive()
                            progressed = true
                        }
                        while (!inputDone && !outputDone) {
                            ensureActive()
                            val index = codec.dequeueInputBuffer(0)
                            if (index < 0) break
                            val buffer = codec.getInputBuffer(index)!!
                            val capacity = minOf(bytes.size, buffer.remaining()) / FRAME_BYTES * FRAME_BYTES
                            check(capacity > 0) { "Unsupported audio encoder input buffer." }
                            if (remaining == 0 && flushBytes == 0) {
                                codec.queueInputBuffer(index, 0, 0, frames * 1000000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else if (remaining == 0) {
                                // Some AAC-LC encoders acknowledge an empty EOS without flushing
                                // their delayed audio. Feed a short zero tail so the last word emerges.
                                val count = minOf(capacity, flushBytes)
                                bytes.fill(0, 0, count)
                                buffer.put(bytes, 0, count)
                                codec.queueInputBuffer(index, 0, count, frames * 1000000 / SAMPLE_RATE, 0)
                                frames += count / 2
                                flushBytes -= count
                            } else {
                                val count = minOf(capacity, remaining)
                                source.readFully(bytes, 0, count)
                                remaining -= count
                                // Preserve the last word even when its PCM ends partway through an AAC frame.
                                val padded = (count + FRAME_BYTES - 1) / FRAME_BYTES * FRAME_BYTES
                                bytes.fill(0, count, padded)
                                buffer.put(bytes, 0, padded)
                                codec.queueInputBuffer(index, 0, padded, frames * 1000000 / SAMPLE_RATE, 0)
                                frames += padded / 2
                            }
                            progressed = true
                        }
                        if (!progressed && !outputDone) progressed = drain(codec.dequeueOutputBuffer(info, 10000))
                        if (progressed) lastProgress = SystemClock.elapsedRealtime()
                    }
                    check(muxerStarted && inputDone && remaining == 0 && flushBytes == 0 && payloadBytes > 0) { "Audio encoder returned incomplete output." }
                    writer.stop()
                    muxerStarted = false
                } finally {
                    // Synchronous cleanup must not replace an in-flight error or coroutine cancellation.
                    if (muxerStarted) runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                    if (codecStarted) runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }
            ensureActive()
            require(output.length() in 1L until MAX_UPLOAD_BYTES) { "Compressed audio is empty or exceeds the upload limit." }
            complete = true
        } finally { if (!complete) output.delete() }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 64000
        private const val MIME = "audio/mp4a-latm"
        private const val FRAME_BYTES = 1024 * 2
        private const val FLUSH_FRAMES = 3
        private const val BUFFER_BYTES = 16384
        private const val STALL_TIMEOUT_MS = 30000L
        private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024
    }
}
