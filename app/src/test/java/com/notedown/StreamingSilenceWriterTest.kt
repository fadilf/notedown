package com.notedown

import com.notedown.audio.SilenceTrimmer
import com.notedown.audio.StreamingSilenceWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class StreamingSilenceWriterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun matchesOfflineTrimmerAcrossBoundariesFragmentationAndPartialFrames() = runTest {
        val random = Random(713)
        repeat(80) { example ->
            val frames = random.nextInt(1, 250)
            val lastBytes = random.nextInt(1, 513) * 2
            val pcm = random.nextBytes((frames - 1) * 1024 + lastBytes)
            val decisions = BooleanArray(frames) { index ->
                when (example % 5) {
                    0 -> false
                    1 -> true
                    2 -> index == frames - 1
                    3 -> index == 0 || index == minOf(31, frames - 1)
                    else -> random.nextInt(12) == 0
                }
            }
            val input = folder.newFile()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()).putInt(pcm.size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                put("data".toByteArray()).putInt(pcm.size)
            }.array()
            input.outputStream().use { it.write(header); it.write(pcm) }
            val offline = folder.newFile()
            var position = 0
            val expectedChanged = SilenceTrimmer().trim(input, offline) { decisions[position++] }
            val stream = ByteArrayOutputStream()
            position = 0
            val writer = StreamingSilenceWriter(stream) { frame ->
                if (position == frames - 1) assertTrue(frame.drop(lastBytes).all { it == 0.toByte() })
                decisions[position++]
            }
            var offset = 0
            while (offset < pcm.size) {
                val count = minOf(pcm.size - offset, random.nextInt(1, 1800) * 2)
                writer.accept(pcm.copyOfRange(offset, offset + count), count)
                offset += count
            }
            assertEquals("Case $example", expectedChanged, writer.finish())
            assertEquals(frames, position)
            assertEquals(pcm.size, writer.inputBytes)
            if (expectedChanged) assertArrayEquals(offline.readBytes().copyOfRange(44, offline.length().toInt()), stream.toByteArray())
            else if (decisions.any { it }) assertArrayEquals(pcm, stream.toByteArray())
            else assertEquals(0, writer.retainedBytes)
        }
    }

    @Test fun detectorFailurePropagates() {
        val writer = StreamingSilenceWriter(ByteArrayOutputStream()) { error("Detector unavailable") }
        try { writer.accept(ByteArray(1024), 1024); fail("Failure must propagate") }
        catch (_: IllegalStateException) { }
    }
}
