package com.notedown

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.notedown.audio.SilenceFilter
import com.notedown.audio.SilenceTrimmer
import com.notedown.audio.WavConverter
import com.notedown.di.AppModule
import com.notedown.api.reportedCost
import com.notedown.api.OpenRouterApi
import com.notedown.api.TranscriptionResponse
import com.notedown.api.TranscriptionService
import com.notedown.data.ApiKeyStorage
import com.notedown.data.RecordingEntity
import com.notedown.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/** Runtime-only private recordings; never run live requests in ordinary test suites. */
@RunWith(AndroidJUnit4::class)
class SilenceOptimizationLabTest {
    private val variants = listOf("baseline", "buffered", "pooled_float", "reuse_session", "frame_1024", "frame_1536", "fused", "pipeline", "production_pipeline")
    private val rows = mutableListOf<JsonElement>()
    private lateinit var root: File
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun save() = File(root, "results.json").writeText(JsonArray(rows).toString())
    private fun hash(file: File) = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun library(size: Int = 512) = VadSilero(ApplicationProvider.getApplicationContext(), SampleRate.SAMPLE_RATE_16K,
        when (size) { 1024 -> FrameSize.FRAME_SIZE_1024; 1536 -> FrameSize.FRAME_SIZE_1536; else -> FrameSize.FRAME_SIZE_512 }, Mode.NORMAL)

    private fun ranges(speech: List<Boolean>, size: Int): List<IntRange> {
        val pad = (480 + size / 16 - 1) / (size / 16)
        val result = mutableListOf<IntRange>()
        speech.forEachIndexed { index, value ->
            if (value) {
                val first = maxOf(0, index - pad)
                val last = minOf(speech.lastIndex, index + pad)
                val previous = result.lastOrNull()
                if (previous != null && first <= previous.last + 1) result[result.lastIndex] = previous.first..last
                else result.add(first..last)
            }
        }
        return result
    }

    private fun writeTrimmed(pcm: ByteArray, speech: List<Boolean>, size: Int, output: File): Boolean {
        val ranges = ranges(speech, size)
        if (ranges.isEmpty() || ranges.singleOrNull() == 0..speech.lastIndex) return false
        val bytes = size * 2
        val length = ranges.sumOf { minOf(pcm.size, (it.last + 1) * bytes) - it.first * bytes }
        output.outputStream().buffered(65536).use { stream ->
            stream.write(WavConverter.header(length))
            for (range in ranges) {
                val start = range.first * bytes
                stream.write(pcm, start, minOf(pcm.size, (range.last + 1) * bytes) - start)
            }
        }
        return true
    }

    private suspend fun memoryTrim(wav: File, output: File, size: Int, speech: (ByteArray) -> Boolean): Boolean {
        val pcm = wav.inputStream().use { it.skip(44); it.readBytes() }
        val frame = ByteArray(size * 2)
        val flags = mutableListOf<Boolean>()
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            frame.fill(0)
            pcm.copyInto(frame, 0, offset, minOf(pcm.size, offset + frame.size))
            flags.add(speech(frame)); offset += frame.size
        }
        return writeTrimmed(pcm, flags, size, output)
    }

    private suspend fun fused(input: File, output: File, detector: LabSilero, pipeline: Boolean): Pair<Boolean, File> = coroutineScope {
        detector.reset()
        val all = ByteArrayOutputStream()
        val frame = ByteArray(1024)
        val flags = mutableListOf<Boolean>()
        var filled = 0
        fun consume(bytes: ByteArray, count: Int) {
            all.write(bytes, 0, count)
            var offset = 0
            while (offset < count) {
                val length = minOf(frame.size - filled, count - offset)
                bytes.copyInto(frame, filled, offset, offset + length)
                filled += length; offset += length
                if (filled == frame.size) { flags.add(detector.speech(frame)); filled = 0 }
            }
        }
        if (pipeline) {
            val chunks = Channel<ByteArray>(8)
            val producer = launch(Dispatchers.IO) {
                try { WavConverter().decode(input) { bytes, count -> chunks.send(bytes.copyOf(count)) } }
                finally { chunks.close() }
            }
            for (chunk in chunks) { ensureActive(); consume(chunk, chunk.size) }
            producer.join()
        } else WavConverter().decode(input) { bytes, count -> consume(bytes, count) }
        if (filled != 0) { frame.fill(0, filled); flags.add(detector.speech(frame)) }
        val pcm = all.toByteArray()
        val changed = writeTrimmed(pcm, flags, 512, output)
        if (!changed) output.outputStream().buffered().use { it.write(WavConverter.header(pcm.size)); it.write(pcm) }
        changed to output
    }

    @Test fun benchmark() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit local benchmark opt-in required", args.getString("silenceLab") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "silence-lab")
        val inputs = File(root, "inputs").listFiles()!!.filter { it.extension == "mp3" && it.name.startsWith(args.getString("inputPrefix") ?: "") }.sortedBy { it.name }
        check(inputs.isNotEmpty())
        val outputs = File(root, "outputs").apply { mkdirs() }
        val repetitions = args.getString("repetitions")?.toInt() ?: 3
        val sessionStart = now()
        LabSilero(context).use { shared ->
            rows.add(buildJsonObject { put("kind", "model_init"); put("ms", (now() - sessionStart) / 1e6) })
            for ((fileIndex, input) in inputs.withIndex()) {
                val canonical = File(outputs, "${input.nameWithoutExtension}-full.wav")
                LegacyWavConverter().convert(input, canonical)
                // Differential decoder validation independently of VAD decisions.
                val next = File(outputs, "decode-check.wav")
                WavConverter().convert(input, next)
                check(hash(canonical) == hash(next)) { "Decoder mismatch at file $fileIndex" }
                next.delete()
                val baselineOutput = File(outputs, "${input.nameWithoutExtension}-baseline.wav")
                val baselineChanged = SilenceFilter(context).trim(canonical, baselineOutput)
                if (!baselineChanged) canonical.copyTo(baselineOutput, overwrite = true)
                val referenceHash = hash(baselineOutput)
                repeat(repetitions) { iteration ->
                    // Rotate variant order to distribute warmup, thermal, and filesystem-cache effects.
                    val ordered = variants.drop((fileIndex + iteration) % variants.size) + variants.take((fileIndex + iteration) % variants.size)
                    for (variant in ordered) {
                        val output = File(outputs, "${input.nameWithoutExtension}-$variant.wav")
                        val wav = File(outputs, "temporary.wav")
                        val start = now()
                        var decodeMs = 0.0
                        var changed: Boolean
                        if (variant == "production_pipeline") {
                            changed = SilenceFilter(context).convertAndTrim(input, wav, output)
                            if (!changed) wav.copyTo(output, overwrite = true)
                        } else if (variant == "fused" || variant == "pipeline") {
                            changed = fused(input, output, shared, variant == "pipeline").first
                        } else {
                            if (variant == "baseline") LegacyWavConverter().convert(input, wav)
                            else WavConverter().convert(input, wav)
                            val decoded = now()
                            decodeMs = (decoded - start) / 1e6
                            changed = when (variant) {
                                "baseline" -> SilenceFilter(context).trim(wav, output)
                                "buffered" -> library().use { detector -> memoryTrim(wav, output, 512) { detector.isSpeech(it) } }
                                "pooled_float" -> library().use { detector ->
                                    val samples = FloatArray(512)
                                    memoryTrim(wav, output, 512) { bytes ->
                                        for (index in samples.indices) samples[index] = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)) / 32767.0f
                                        detector.isSpeech(samples)
                                    }
                                }
                                "reuse_session" -> { shared.reset(); memoryTrim(wav, output, 512, shared::speech) }
                                else -> {
                                    val size = if (variant == "frame_1024") 1024 else 1536
                                    library(size).use { detector -> memoryTrim(wav, output, size) { detector.isSpeech(it) } }
                                }
                            }
                            if (!changed) wav.copyTo(output, overwrite = true)
                        }
                        val durationMs = (now() - start) / 1e6
                        val sha = hash(output)
                        rows.add(buildJsonObject {
                            put("kind", "local"); put("file", input.name); put("variant", variant); put("iteration", iteration)
                            put("total_ms", durationMs); put("decode_ms", decodeMs)
                            put("changed", changed); put("input_seconds", (canonical.length() - 44) / 32000.0)
                            put("output_seconds", (output.length() - 44) / 32000.0)
                            put("output_bytes", output.length()); put("sha256", sha); put("baseline_identical", sha == referenceHash)
                        })
                        save()
                        if (variant !in listOf("frame_1024", "frame_1536")) check(sha == referenceHash) { "Speech/audio regression at file $fileIndex variant $variant" }
                        wav.delete()
                    }
                }
            }
        }
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()
    private fun distance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.size + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous[b.size]
    }

    @Test fun pipelineFailureCancellationAndConcurrentSessions() = runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit private-recording opt-in required", InstrumentationRegistry.getArguments().getString("silenceLab") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "silence-lab")
        val input = File(root, "inputs/2026_11_11_07_30_08.mp3")
        val originalHash = hash(input)
        val expected = hash(File(root, "outputs/${input.nameWithoutExtension}-baseline.wav"))
        val api = object : OpenRouterApi by AppModule.api() {
            override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse {
                error("No API request permitted during failure/cancellation checks")
            }
        }
        val service = TranscriptionService(api, ApiKeyStorage(context), SilenceFilter(context))
        val malformed = File(input.parentFile, "malformed.mp3").apply { writeBytes(ByteArray(512)) }
        try {
            try {
                service.transcribe(RecordingEntity("invalid", 0, 1000, malformed.absolutePath, "mp3"), Settings(model = "fake", skipSilence = true))
                error("Malformed audio must fail")
            } catch (e: IllegalStateException) { check(e.message == "Silence removal failed. Retry or turn off Skip silence in Settings.") }
            val work = async {
                service.transcribe(RecordingEntity("cancel", 0, 64000, input.absolutePath, "mp3"), Settings(model = "fake", skipSilence = true))
            }
            delay(50)
            withTimeout(5000) { work.cancelAndJoin() }
            check(work.isCancelled)
            check(input.parentFile!!.listFiles()!!.none { it.name.startsWith("notedown-converted-") })
            (0..1).map { index ->
                async {
                    val decoded = File(root, "concurrent-$index.wav")
                    val output = File(root, "concurrent-trimmed-$index.wav")
                    try {
                        val changed = SilenceFilter(context).convertAndTrim(input, decoded, output)
                        check(hash(if (changed) output else decoded) == expected)
                    } finally { decoded.delete(); output.delete() }
                }
            }.awaitAll()
            check(hash(input) == originalHash)
        } finally { malformed.delete() }
    }

    @Test fun pipelineUploadFallbacksAndCleanup() = runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit private-recording opt-in required", InstrumentationRegistry.getArguments().getString("silenceLab") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "silence-lab")
        val keys = ApiKeyStorage(context)
        val oldKey = keys.read()
        try {
            keys.write("fake-key-no-network")
            for ((name, wav) in listOf("stress_silence" to false, "stress_silence" to true, "2026_11_11_07_30_08" to false)) {
                val input = File(root, "inputs/$name.mp3")
                val expected = when {
                    name != "stress_silence" -> File(root, "outputs/$name-baseline.wav")
                    wav -> File(root, "outputs/$name-full.wav")
                    else -> input
                }.readBytes()
                var requests = 0
                val api = object : OpenRouterApi by AppModule.api() {
                    override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse {
                        requests++
                        val body = Buffer().also { file.body.writeTo(it) }.readByteArray()
                        if (name == "stress_silence") {
                            check(body.contentEquals(expected)) { "Upload differs from expected fallback" }
                        } else {
                            check(file.body.contentType().toString() == "audio/mp4")
                            val captured = File(root, "upload-check.m4a")
                            val decoded = File(root, "upload-check.wav")
                            try {
                                captured.writeBytes(body)
                                WavConverter().convert(captured, decoded)
                                val expectedSeconds = (expected.size - 44) / 32000.0
                                val actualSeconds = (decoded.length() - 44) / 32000.0
                                check(actualSeconds + 0.0000625 >= expectedSeconds && actualSeconds - expectedSeconds <= 0.2560625)
                                check(body.size < expected.size / 2)
                            } finally { captured.delete(); decoded.delete() }
                        }
                        return TranscriptionResponse("")
                    }
                }
                TranscriptionService(api, keys, SilenceFilter(context)).transcribe(
                    RecordingEntity("fallback", 0, 64000, input.absolutePath, "mp3"), Settings(model = "fake", skipSilence = true, wav = wav))
                check(requests == 1)
                check(input.parentFile!!.listFiles()!!.none { it.name.startsWith("notedown-converted-") })
            }
        } finally { if (oldKey == null) keys.remove() else keys.write(oldKey) }
    }

    @Test fun containerFormats() = runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit private-recording opt-in required", InstrumentationRegistry.getArguments().getString("silenceLab") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "silence-lab")
        val inputs = File(root, "inputs").listFiles()!!.filter { it.extension == "m4a" }.sortedBy { it.name }
        check(inputs.isNotEmpty())
        val records = mutableListOf<JsonElement>()
        for (input in inputs) {
            val legacyWav = File(root, "container-legacy.wav")
            val legacyTrimmed = File(root, "container-legacy-trimmed.wav")
            val decoded = File(root, "container-decoded.wav")
            val output = File(root, "container-trimmed.wav")
            try {
                repeat(3) { iteration ->
                    val baselineStart = now()
                    LegacyWavConverter().convert(input, legacyWav)
                    val expectedChanged = SilenceFilter(context).trim(legacyWav, legacyTrimmed)
                    val baselineMs = (now() - baselineStart) / 1e6
                    val optimizedStart = now()
                    val changed = SilenceFilter(context).convertAndTrim(input, decoded, output)
                    val optimizedMs = (now() - optimizedStart) / 1e6
                    check(expectedChanged == changed)
                    check(hash(legacyWav) == hash(decoded))
                    check(hash(if (expectedChanged) legacyTrimmed else legacyWav) == hash(if (changed) output else decoded))
                    records.add(buildJsonObject {
                        put("file", input.name); put("iteration", iteration); put("baseline_ms", baselineMs)
                        put("optimized_ms", optimizedMs); put("byte_identical", true)
                    })
                    File(root, "container-results.json").writeText(JsonArray(records).toString())
                }
            } finally { legacyWav.delete(); legacyTrimmed.delete(); decoded.delete(); output.delete() }
        }
    }

    @Test fun liveEndToEnd() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit paid benchmark opt-in required", args.getString("silenceLabLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.filesDir, "silence-lab")
        val ledger = File(root, "e2e-results.json")
        check(!ledger.exists()) { "Inspect existing paid ledger before another run" }
        val budgetFile = File(root, "budget.json")
        val budget = Json.parseToJsonElement(budgetFile.readText()).jsonObject
        val cap = BigDecimal(args.getString("budgetUsd") ?: "5").also { require(it > BigDecimal.ZERO && it <= BigDecimal("5")) }
        var reserved = budget["reserved_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var reported = budget["reported_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var requests = budget["requests"]!!.jsonPrimitive.int
        fun saveBudget() = budgetFile.writeText(buildJsonObject {
            put("reserved_usd", reserved.toPlainString()); put("reported_usd", reported.toPlainString()); put("requests", requests)
        }.toString())
        val keys = ApiKeyStorage(context)
        val oldKey = keys.read()
        val keyFile = File(root, "key")
        val api = AppModule.api()
        val records = mutableListOf<JsonElement>()
        val model = "moondream/parakeet-ultra"
        val selection = listOf("2023_07_15_21_55_20.mp3", "2026_11_11_07_30_08.mp3", "2026_11_14_18_34_36.mp3", "stress_minus24.mp3")
        try {
            keys.write(keyFile.readText().trim()); keyFile.delete()
            for (name in selection) {
                val input = File(root, "inputs/$name")
                val full = File(root, "outputs/${input.nameWithoutExtension}-full.wav")
                val seconds = (full.length() - 44) / 32000.0
                var reference: List<String> = emptyList()
                repeat(3) { iteration ->
                    val variants = listOf("original", "legacy_trimmed", "optimized_trimmed")
                    for (variant in variants.drop(iteration) + variants.take(iteration)) {
                        val reserve = BigDecimal.valueOf(seconds).multiply(BigDecimal("0.0001")).add(BigDecimal("0.01"))
                        check(reserved + reserve <= cap && ++requests <= 100) { "Paid budget exhausted" }
                        reserved += reserve; saveBudget()
                        var apiStart = 0L
                        var uploadBytes = 0L
                        val measured = object : OpenRouterApi by api {
                            override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse {
                                uploadBytes = file.body.contentLength()
                                apiStart = now()
                                return api.transcribe(key, model, file, language)
                            }
                        }
                        val start = now()
                        val response = if (variant == "legacy_trimmed") {
                            val decoded = File(root, "e2e-decoded.wav")
                            val trimmed = File(root, "e2e-trimmed.wav")
                            try {
                                LegacyWavConverter().convert(input, decoded)
                                val changed = SilenceFilter(context).trim(decoded, trimmed)
                                val upload = if (changed) trimmed else input
                                measured.transcribe("Bearer ${keys.read()!!}", model.toRequestBody("text/plain".toMediaType()),
                                    MultipartBody.Part.createFormData("file", upload.name, upload.asRequestBody((if (changed) "audio/wav" else "audio/mpeg").toMediaType())), null)
                            } finally { decoded.delete(); trimmed.delete() }
                        } else TranscriptionService(measured, keys, SilenceFilter(context)).transcribe(
                            RecordingEntity("profile", 0, (seconds * 1000).toLong(), input.absolutePath, "mp3"),
                            Settings(model = model, skipSilence = variant == "optimized_trimmed"))
                        val end = now()
                        val cost = reportedCost(response.usage)?.toBigDecimal()
                        if (cost != null) { check(cost <= reserve); reported += cost; reserved += cost - reserve }
                        saveBudget()
                        val result = words(response.text)
                        if (variant == "original" && iteration == 0) reference = result
                        records.add(buildJsonObject {
                            put("file", name); put("variant", variant); put("iteration", iteration)
                            put("total_ms", (end - start) / 1e6); put("preparation_ms", (apiStart - start) / 1e6)
                            put("api_ms", (end - apiStart) / 1e6); put("upload_bytes", uploadBytes)
                            put("usage", response.usage ?: JsonNull)
                            put("reference_words", reference.size); put("result_words", result.size)
                            put("word_edits_vs_full", distance(reference, result))
                        })
                        ledger.writeText(JsonArray(records).toString())
                    }
                }
            }
        } finally {
            keyFile.delete()
            if (oldKey == null) keys.remove() else keys.write(oldKey)
        }
    }

    @Test fun liveComparisons() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit paid benchmark opt-in required", args.getString("silenceLabLive") == "true")
        root = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "silence-lab")
        val keyFile = File(root, "key")
        val key = keyFile.readText().trim()
        keyFile.delete()
        val local = Json.parseToJsonElement(File(root, "results.json").readText()).jsonArray
        val outputs = File(root, "outputs")
        val ledgerName = args.getString("ledgerName") ?: "live-results"
        require(Regex("[a-zA-Z0-9_-]+").matches(ledgerName))
        val ledger = File(root, "$ledgerName.json")
        check(!ledger.exists()) { "Existing paid ledger: inspect and archive before explicitly starting another session" }
        val api = AppModule.api()
        val records = mutableListOf<JsonElement>()
        val cap = BigDecimal(args.getString("budgetUsd") ?: "5").also { require(it > BigDecimal.ZERO && it <= BigDecimal("5")) }
        val previousBudget = File(root, "budget.json").takeIf { it.exists() }?.readText()?.let { Json.parseToJsonElement(it).jsonObject }
        var reserved = previousBudget?.get("reserved_usd")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO
        var reported = previousBudget?.get("reported_usd")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO
        var requests = previousBudget?.get("requests")?.jsonPrimitive?.int ?: 0
        val model = "moondream/parakeet-ultra"
        val names = local.filter { it.jsonObject["kind"]?.jsonPrimitive?.content == "local" }.map { it.jsonObject["file"]!!.jsonPrimitive.content }.distinct()
            .filter { it.startsWith(args.getString("inputPrefix") ?: "") }
        for (name in names) {
            var reference: List<String> = emptyList()
            val seen = mutableMapOf<String, Pair<List<String>, JsonObject?>>()
            for (variant in listOf("full", "baseline", "buffered", "pooled_float", "reuse_session", "frame_1024", "frame_1536", "fused", "pipeline")) {
                val file = File(outputs, "${name.substringBeforeLast('.') }-$variant.wav")
                val sha = hash(file)
                val cached = seen[sha]
                val seconds = (file.length() - 44) / 32000.0
                var elapsed = 0.0
                var usage: JsonObject? = cached?.second
                val result: List<String>
                if (cached != null) result = cached.first
                else {
                    // Reserve >5x the published per-second rate plus 1 cent before each call.
                    // A failed/unknown charge keeps its reservation and stops the run; no retries.
                    val reserve = BigDecimal.valueOf(seconds).multiply(BigDecimal("0.0001")).add(BigDecimal("0.01"))
                    check(reserved + reserve <= cap && ++requests <= 100) { "Paid benchmark budget exhausted" }
                    reserved += reserve
                    File(root, "budget.json").writeText(buildJsonObject { put("reserved_usd", reserved.toPlainString()); put("reported_usd", reported.toPlainString()); put("requests", requests) }.toString())
                    val start = now()
                    val response = api.transcribe("Bearer $key", model.toRequestBody("text/plain".toMediaType()),
                        MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("audio/wav".toMediaType())), null)
                    elapsed = (now() - start) / 1e6
                    usage = response.usage
                    val cost = reportedCost(usage)?.toBigDecimal()
                    if (cost != null) { check(cost <= reserve) { "Provider price exceeded reservation; stopping" }; reported += cost; reserved += cost - reserve }
                    result = words(response.text)
                    seen[sha] = result to usage
                }
                if (variant == "full") reference = result
                records.add(buildJsonObject {
                    put("file", name); put("variant", variant); put("request_sent", cached == null)
                    put("audio_seconds", seconds); put("api_ms", elapsed); put("usage", usage ?: JsonNull)
                    put("reference_words", reference.size); put("result_words", result.size)
                    put("word_edits_vs_full", distance(reference, result))
                    put("reference_normalized_match", reference == result)
                })
                ledger.writeText(JsonArray(records).toString())
                File(root, "budget.json").writeText(buildJsonObject { put("reserved_usd", reserved.toPlainString()); put("reported_usd", reported.toPlainString()); put("requests", requests) }.toString())
            }
        }
    }
}
