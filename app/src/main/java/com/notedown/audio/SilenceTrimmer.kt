package com.notedown.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Filters Notedown's canonical 16 kHz mono PCM WAVs without modifying the retained recording. */
class SilenceTrimmer {
    /** Returns false when no speech was found or nothing can be removed; callers keep the input. */
    suspend fun trim(input: File, output: File, isSpeech: (ByteArray) -> Boolean): Boolean = withContext(Dispatchers.IO) {
        require(input.canonicalPath != output.canonicalPath)
        RandomAccessFile(input, "r").use { source ->
            val header = ByteArray(44)
            source.readFully(header)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                fields.getInt(16) == 16 && fields.getShort(20).toInt() == 1 &&
                fields.getShort(22).toInt() == 1 && fields.getInt(24) == 16000 &&
                fields.getShort(32).toInt() == 2 && fields.getShort(34).toInt() == 16 &&
                String(header, 36, 4, Charsets.US_ASCII) == "data") { "Unsupported WAV for silence removal." }
            val size = fields.getInt(40)
            require(size > 0 && size % 2 == 0 && source.length() == size.toLong() + 44) { "Invalid WAV for silence removal." }
            val frameCount = (size + FRAME_BYTES - 1) / FRAME_BYTES
            val speech = BooleanArray(frameCount)
            val frame = ByteArray(FRAME_BYTES)
            for (index in speech.indices) {
                ensureActive()
                frame.fill(0)
                source.readFully(frame, 0, minOf(FRAME_BYTES, size - index * FRAME_BYTES))
                speech[index] = isSpeech(frame)
            }
            val ranges = speechRanges(speech)
            if (ranges.isEmpty() || ranges.singleOrNull() == 0 until frameCount) return@withContext false
            RandomAccessFile(output, "rw").use { target ->
                target.setLength(0)
                target.write(header)
                for (range in ranges) {
                    source.seek(44L + range.first.toLong() * FRAME_BYTES)
                    for (index in range) {
                        ensureActive()
                        val count = minOf(FRAME_BYTES, size - index * FRAME_BYTES)
                        source.readFully(frame, 0, count)
                        target.write(frame, 0, count)
                    }
                }
                val retained = (target.length() - 44).toInt()
                fields.putInt(4, retained + 36).putInt(40, retained)
                target.seek(0)
                target.write(header)
            }
            true
        }
    }

    companion object {
        const val FRAME_BYTES = 512 * 2 // Silero's 32 ms frames, PCM16.

        /** Keep 480 ms on each side of every positive frame; merge overlapping padding. */
        internal fun speechRanges(speech: BooleanArray): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            for (index in speech.indices) {
                if (!speech[index]) continue
                val start = (index - 15).coerceAtLeast(0)
                val end = (index + 15).coerceAtMost(speech.lastIndex)
                val previous = ranges.lastOrNull()
                if (previous != null && start <= previous.last + 1) {
                    ranges[ranges.lastIndex] = previous.first..end
                } else ranges.add(start..end)
            }
            return ranges
        }
    }
}
