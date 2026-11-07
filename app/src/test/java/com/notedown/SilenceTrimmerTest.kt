package com.notedown

import com.notedown.audio.SilenceTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SilenceTrimmerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun wav(frames: Int, lastFrameBytes: Int = SilenceTrimmer.FRAME_BYTES): File {
        val size = (frames - 1) * SilenceTrimmer.FRAME_BYTES + lastFrameBytes
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(size)
        repeat(frames) { index -> repeat((if (index == frames - 1) lastFrameBytes else SilenceTrimmer.FRAME_BYTES) / 2) { data.putShort(index.toShort()) } }
        return folder.newFile().apply { writeBytes(data.array()) }
    }

    @Test fun removesInteriorAndEdgeSilenceWithPaddingAndValidHeader() = runTest {
        val input = wav(200)
        val original = input.readBytes()
        val output = folder.newFile()
        var index = 0
        assertTrue(SilenceTrimmer().trim(input, output) { index++ in setOf(30, 150) })
        // Speech at 30 and 150 retains frames 15..45 and 135..165, in order.
        val result = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(62 * 1024 + 44L, output.length())
        assertEquals(output.length().toInt() - 8, result.getInt(4))
        assertEquals(output.length().toInt() - 44, result.getInt(40))
        assertEquals(15, result.getShort(44).toInt())
        assertEquals(135, result.getShort(44 + 31 * 1024).toInt())
        assertArrayEquals(original, input.readBytes())
    }

    @Test fun shortPausesMergeAndDoNotDuplicateAudio() {
        val speech = BooleanArray(100).apply { this[30] = true; this[50] = true }
        assertEquals(listOf(15..65), SilenceTrimmer.speechRanges(speech))
    }

    @Test fun noSpeechOrContinuousSpeechLeavesOriginalUsable() = runTest {
        val input = wav(100)
        val output = folder.newFile()
        assertFalse(SilenceTrimmer().trim(input, output) { false })
        assertFalse(SilenceTrimmer().trim(input, output) { true })
        assertEquals(0L, output.length())
    }

    @Test fun finalPartialFrameIsPaddedOnlyForDetection() = runTest {
        val input = wav(100, 20)
        val output = folder.newFile()
        var index = 0
        assertTrue(SilenceTrimmer().trim(input, output) { frame ->
            if (index == 99) {
                assertEquals(1024, frame.size)
                assertTrue(frame.drop(20).all { it == 0.toByte() })
            }
            index++ == 99
        })
        assertEquals(44L + 15 * 1024 + 20, output.length())
    }

    @Test fun cancellationAndDetectorFailuresPropagateWithoutChangingInput() = runTest {
        val input = wav(100)
        val original = input.readBytes()
        try {
            SilenceTrimmer().trim(input, folder.newFile()) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        try {
            SilenceTrimmer().trim(input, folder.newFile()) { error("Detector unavailable") }
            fail("Detector failures must propagate")
        } catch (_: IllegalStateException) { }
        assertArrayEquals(original, input.readBytes())
    }

    @Test fun rejectsNonPcmWavBeforeDetection() = runTest {
        val input = wav(2)
        input.writeBytes(input.readBytes().apply { this[20] = 3 })
        try {
            SilenceTrimmer().trim(input, folder.newFile()) { fail("Invalid format reached detector"); true }
            fail("Invalid format must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
