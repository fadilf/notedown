package com.notedown.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode retained M4A into a temporary 16 kHz mono PCM WAV for compatibility uploads. */
class WavConverter {
    suspend fun convert(input: File, output: File) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var sourceFrame = 0L; var outputFrame = 0L
            RandomAccessFile(output, "rw").use { file ->
                file.setLength(0); file.write(ByteArray(44))
                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val next = decoder.outputFormat
                        rate = next.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = next.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(!next.containsKey(MediaFormat.KEY_PCM_ENCODING) || next.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_16BIT) { "Unsupported decoder PCM format" }
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val chunk = ByteBuffer.allocate(info.size + 4).order(ByteOrder.LITTLE_ENDIAN)
                        while (buffer.remaining() >= 2 * channels) {
                            var sum = 0
                            repeat(channels) { sum += buffer.short.toInt() }
                            if (sourceFrame * 16000 >= outputFrame * rate) { chunk.putShort((sum / channels).toShort()); outputFrame++ }
                            sourceFrame++
                        }
                        file.write(chunk.array(), 0, chunk.position())
                        require(file.length() < 25L * 1024 * 1024) { "Converted audio exceeds the 25 MB upload limit." }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                val size = (file.length() - 44).toInt()
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
                file.seek(0); file.write(header.array())
            }
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}
