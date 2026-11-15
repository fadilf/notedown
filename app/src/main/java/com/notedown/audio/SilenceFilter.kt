package com.notedown.audio

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

class SilenceFilter @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun trim(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512, Mode.NORMAL).use { detector ->
            SilenceTrimmer().trim(input, output) { detector.isSpeech(it) }
        }
    }

    /** Decode and classify concurrently, retaining a canonical WAV for the normal fallback path. */
    suspend fun convertAndTrim(input: File, decoded: File, output: File): Boolean = withContext(Dispatchers.IO) {
        require(setOf(input.canonicalPath, decoded.canonicalPath, output.canonicalPath).size == 3)
        VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512, Mode.NORMAL).use { detector ->
            coroutineScope {
                val chunks = Channel<ByteArray>(8)
                val producer = launch(Dispatchers.IO) {
                    try {
                        var size = 0
                        decoded.outputStream().buffered(64 * 1024).use { stream ->
                            stream.write(ByteArray(44))
                            WavConverter().decode(input) { bytes, count ->
                                stream.write(bytes, 0, count)
                                size += count
                                chunks.send(bytes.copyOf(count))
                            }
                        }
                        require(size > 0) { "Recording is empty or missing." }
                        RandomAccessFile(decoded, "rw").use { it.write(WavConverter.header(size)) }
                    } finally { chunks.close() }
                }
                var retained = 0
                val samples = FloatArray(512)
                val changed = output.outputStream().buffered(64 * 1024).use { stream ->
                    stream.write(ByteArray(44))
                    val writer = StreamingSilenceWriter(stream) { frame ->
                        for (index in samples.indices) {
                            samples[index] = ((frame[2 * index].toInt() and 255) or (frame[2 * index + 1].toInt() shl 8)) / 32767.0f
                        }
                        detector.isSpeech(samples)
                    }
                    for (chunk in chunks) { ensureActive(); writer.accept(chunk, chunk.size) }
                    writer.finish().also { retained = writer.retainedBytes }
                }
                producer.join()
                RandomAccessFile(output, "rw").use { it.write(WavConverter.header(retained)) }
                changed
            }
        }
    }
}
