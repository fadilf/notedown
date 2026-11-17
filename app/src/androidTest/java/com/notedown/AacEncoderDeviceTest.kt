package com.notedown

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.api.*
import com.notedown.audio.AacEncoder
import com.notedown.audio.SilenceFilter
import com.notedown.audio.WavConverter
import com.notedown.data.ApiKeyStorage
import com.notedown.data.RecordingEntity
import com.notedown.data.Settings
import kotlinx.coroutines.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AacEncoderDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var folder: File
    @Before fun setup() { folder = File(context.cacheDir, "aac-test-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { folder.deleteRecursively() }

    private fun wav(name: String, samples: Int, endingTone: Boolean = false): File = File(folder, name).apply {
        RandomAccessFile(this, "rw").use { file ->
            file.write(WavConverter.header(samples * 2))
            file.setLength(44L + samples * 2)
            if (endingTone) {
                val count = minOf(320, samples)
                val tail = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { tail.putShort((sin(it * 2 * Math.PI * 440 / 16000) * 12000).toInt().toShort()) }
                file.seek(44L + (samples - count) * 2)
                file.write(tail.array())
            }
        }
    }

    private fun hash(file: File): List<Byte> = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        digest.digest().toList()
    }

    private fun checkFormat(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally { extractor.release() }
    }

    @Test fun compressesPartialFramesWithoutDroppingTheEndingSound() = runBlocking {
        for (samples in listOf(2926, 19753, 32017)) {
            val input = wav("input-$samples.wav", samples, endingTone = true)
            val original = hash(input)
            val output = File(folder, "output-$samples.m4a")
            val decoded = File(folder, "decoded-$samples.wav")
            AacEncoder().encode(input, output)
            checkFormat(output)
            WavConverter().convert(output, decoded)
            val decodedSamples = (decoded.length() - 44) / 2
            assertTrue("Partial final frame was discarded", decodedSamples >= samples)
            assertTrue("Unexpected codec flush padding", decodedSamples - samples <= 4096)
            val pcm = ByteBuffer.wrap(decoded.readBytes()).order(ByteOrder.LITTLE_ENDIAN).apply { position(44) }
            var peak = 0
            while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
            assertTrue("The ending sound must remain audible: input=$samples decoded=$decodedSamples peak=$peak", peak > 1000)
            if (samples > 16000) assertTrue(output.length() < input.length() / 2)
            assertEquals(original, hash(input))
        }
    }

    @Test fun rejectsMalformedWavAndReleasesTheEncoderAfterOutputFailure() = runBlocking {
        val malformed = File(folder, "bad.wav").apply { writeBytes(ByteArray(1024)) }
        val output = File(folder, "bad.m4a")
        try { AacEncoder().encode(malformed, output); fail("Malformed input must fail") }
        catch (_: IllegalArgumentException) { }
        assertFalse(output.exists())
        val input = wav("valid.wav", 19753, endingTone = true)
        val original = hash(input)
        try { AacEncoder().encode(input, input); fail("Must reject overwriting the retained input") }
        catch (_: IllegalArgumentException) { }
        try { AacEncoder().encode(input, File(folder, "missing/output.m4a")); fail("Missing destination must fail") }
        catch (_: IOException) { }
        AacEncoder().encode(input, output)
        checkFormat(output)
        assertEquals(original, hash(input))
    }

    @Test fun cancellationDiscardsPartialM4aAndLeavesTheOriginalUsable() = runBlocking {
        val input = wav("ten-minutes.wav", 600 * 16000)
        val original = hash(input)
        val output = File(folder, "cancelled.m4a")
        val work = async(Dispatchers.IO) { AacEncoder().encode(input, output) }
        withTimeout(5000) { while (!output.exists() && !work.isCompleted) delay(1) }
        assertFalse("Cancellation must exercise an active encoding session", work.isCompleted)
        withTimeout(5000) { work.cancelAndJoin() }
        assertTrue(work.isCancelled)
        assertFalse(output.exists())
        assertEquals(original, hash(input))
        AacEncoder().encode(wav("next.wav", 16000), output)
        checkFormat(output)
    }

    private fun fakeApi(upload: suspend (MultipartBody.Part) -> Unit) = object : OpenRouterApi {
        override suspend fun validate(key: String) = error("No network in device tests")
        override suspend fun models(modality: String) = error("No network in device tests")
        override suspend fun cleanup(key: String, request: ChatRequest) = error("No cleanup in upload tests")
        override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse {
            upload(file)
            return TranscriptionResponse("synthetic result")
        }
    }

    @Test fun disabledTrimmingAndNoSpeechKeepTheExistingUploadPaths() = runBlocking {
        val input = wav("silence.wav", 19753)
        val m4a = File(folder, "silence.m4a")
        AacEncoder().encode(input, m4a)
        val decoded = File(folder, "expected.wav")
        WavConverter().convert(m4a, decoded)
        val keys = ApiKeyStorage(context)
        val previous = keys.read()
        try {
            keys.write("fake-local-upload-test")
            for (source in listOf(input, m4a)) for (skip in listOf(false, true)) for (wav in listOf(false, true)) {
                val expected = if (wav && source == m4a) decoded else source
                var calls = 0
                val api = fakeApi { part ->
                    calls++
                    val bytes = Buffer().also { part.body.writeTo(it) }.readByteArray()
                    assertArrayEquals(expected.readBytes(), bytes)
                    assertEquals(if (expected.extension == "wav") "audio/wav" else "audio/mp4", part.body.contentType().toString())
                }
                TranscriptionService(api, keys, SilenceFilter(context)).transcribe(
                    RecordingEntity("fake", 0, 1234, source.absolutePath, source.extension), Settings(model = "fake", wav = wav, skipSilence = skip))
                assertEquals(1, calls)
                assertFalse(folder.listFiles()!!.any { it.name.startsWith("notedown-converted-") })
            }
        } finally { if (previous == null) keys.remove() else keys.write(previous) }
    }

    @Test fun trimmedSpeechUploadsFinalizedAacAndCleansTemporaryFiles() = runBlocking {
        assumeTrue("Requires runtime-only private speech fixture", InstrumentationRegistry.getArguments().getString("trimmedUploadFixture") == "true")
        val source = File(context.filesDir, "silence-upload/input.m4a")
        assertTrue("Stage the private speech fixture before opting in", source.isFile)
        val original = hash(source)
        val input = File(folder, "source.m4a").also { source.copyTo(it) }
        val canonical = File(folder, "source.wav")
        val expected = File(folder, "expected-trimmed.wav")
        WavConverter().convert(input, canonical)
        assertTrue(SilenceFilter(context).trim(canonical, expected))
        val expectedSeconds = (expected.length() - 44) / 32000.0
        val keys = ApiKeyStorage(context)
        val previous = keys.read()
        try {
            keys.write("fake-local-upload-test")
            for (recording in listOf(input, canonical)) for (wav in listOf(false, true)) {
                val before = hash(recording)
                var calls = 0
                val api = fakeApi { part ->
                    calls++
                    assertEquals("audio/mp4", part.body.contentType().toString())
                    assertTrue(part.headers!!["Content-Disposition"]!!.endsWith(".m4a\""))
                    val captured = File(folder, "captured.m4a")
                    val decoded = File(folder, "captured.wav")
                    captured.writeBytes(Buffer().also { part.body.writeTo(it) }.readByteArray())
                    checkFormat(captured)
                    WavConverter().convert(captured, decoded)
                    val seconds = (decoded.length() - 44) / 32000.0
                    assertTrue(seconds + 0.0000625 >= expectedSeconds)
                    assertTrue(seconds - expectedSeconds <= 0.2560625)
                    val effectiveBitrate = captured.length() * 8 / seconds
                    assertTrue("Expected a 64 kbps upload", effectiveBitrate in 55000.0..80000.0)
                    assertTrue(captured.length() < expected.length() / 2)
                }
                TranscriptionService(api, keys, SilenceFilter(context)).transcribe(
                    RecordingEntity("fake", 0, 64000, recording.absolutePath, recording.extension), Settings(model = "fake", wav = wav, skipSilence = true))
                assertEquals(1, calls)
                assertEquals(before, hash(recording))
                assertFalse(folder.listFiles()!!.any { it.name.startsWith("notedown-converted-") })
            }
            var cancelledCalls = 0
            val cancelledApi = fakeApi { cancelledCalls++; error("Cancelled preparation must not reach the API") }
            val work = async(Dispatchers.IO) {
                TranscriptionService(cancelledApi, keys, SilenceFilter(context)).transcribe(
                    RecordingEntity("cancel", 0, 64000, input.absolutePath, "m4a"), Settings(model = "fake", skipSilence = true))
            }
            withTimeout(5000) {
                while (!work.isCompleted && folder.listFiles()!!.none { it.name.startsWith("notedown-converted-compressed-") }) delay(1)
            }
            assertFalse("Cancellation must exercise compressed upload preparation", work.isCompleted)
            withTimeout(5000) { work.cancelAndJoin() }
            assertTrue(work.isCancelled)
            assertEquals(0, cancelledCalls)
            assertFalse(folder.listFiles()!!.any { it.name.startsWith("notedown-converted-") })
            var failures = 0
            val api = fakeApi { failures++; throw IOException("Synthetic request failure") }
            try {
                TranscriptionService(api, keys, SilenceFilter(context)).transcribe(
                    RecordingEntity("fake", 0, 64000, input.absolutePath, "m4a"), Settings(model = "fake", skipSilence = true))
                fail("Request failure must propagate")
            } catch (_: IOException) { }
            assertEquals(1, failures)
            assertFalse(folder.listFiles()!!.any { it.name.startsWith("notedown-converted-") })
            assertEquals(original, hash(source))
        } finally { if (previous == null) keys.remove() else keys.write(previous) }
    }
}
