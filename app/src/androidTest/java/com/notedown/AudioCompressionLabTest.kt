package com.notedown

import android.content.Context
import android.media.MediaCodecList
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.api.OpenRouterApi
import com.notedown.api.reportedCost
import com.notedown.audio.SilenceFilter
import com.notedown.audio.WavConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioCompressionLabTest {
    private val responseJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val variants = listOf("wav", "aac_32", "aac_64", "mp3_32", "mp3_64")
    private val selected = setOf("2023_07_15_21_55_20.mp3", "2026_11_11_07_30_08.mp3", "2026_11_14_18_34_36.mp3", "stress_minus24.mp3")
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun extension(variant: String) = when { variant.startsWith("aac") -> "m4a"; variant.startsWith("mp3") -> "mp3"; else -> "wav" }
    private fun mime(variant: String) = when (extension(variant)) { "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; else -> "audio/wav" }
    private fun root(): File = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "codec-lab")
    private fun suffix(): String = InstrumentationRegistry.getArguments().getString("codecSession")?.let {
        require(Regex("[A-Za-z0-9_-]+").matches(it)); "-$it"
    }.orEmpty()
    private fun words(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()
    private fun edits(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.size + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous[b.size]
    }

    @Test fun prepare() = runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit local codec opt-in required", InstrumentationRegistry.getArguments().getString("codecLab") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = root()
        val outputs = File(root, "outputs").apply { mkdirs() }
        val rows = mutableListOf<JsonElement>()
        File(root, "environment.json").writeText(buildJsonObject {
            put("android", android.os.Build.VERSION.RELEASE)
            put("abi", android.os.Build.SUPPORTED_ABIS.first())
            putJsonArray("aac_encoders") {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { type -> type == "audio/mp4a-latm" } }
                    .forEach { add(it.name) }
            }
            put("mp3", "LAME 3.100, quality 5, CBR, per-call encoder state, native -O3")
            put("aac_final_pcm_frame_padded", true)
        }.toString())
        for (input in File(root, "inputs").listFiles()!!.filter { it.extension == "mp3" }.sortedBy { it.name }) {
            val decoded = File(outputs, "${input.nameWithoutExtension}-decoded.wav")
            val trimmed = File(outputs, "${input.nameWithoutExtension}-trimmed.wav")
            val start = now()
            val changed = SilenceFilter(context).convertAndTrim(input, decoded, trimmed)
            val preparationMs = (now() - start) / 1e6
            val wav = if (changed) trimmed else decoded
            val seconds = (wav.length() - 44) / 32000.0
            repeat(3) { iteration ->
                val order = variants.drop(iteration) + variants.take(iteration)
                for (variant in order) {
                    val output = if (variant == "wav") wav else File(outputs, "${input.nameWithoutExtension}-$variant.${extension(variant)}")
                    val encodingStart = now()
                    if (variant != "wav") LabAudioEncoders.encode(wav, output, variant)
                    val encodingMs = (now() - encodingStart) / 1e6
                    // Decode every lossy output at least once to reject malformed/truncated encoders locally.
                    var decodedSeconds = seconds
                    if (variant != "wav" && iteration == 0) {
                        val decodedFile = File(outputs, "decode-check.wav")
                        WavConverter().convert(output, decodedFile)
                        decodedSeconds = (decodedFile.length() - 44) / 32000.0
                        check(kotlin.math.abs(decodedSeconds - seconds) < 0.3) { "Encoded duration differs unexpectedly" }
                        if (variant.startsWith("aac")) check(decodedSeconds + 0.0000625 >= seconds) { "AAC dropped the final partial frame" }
                        decodedFile.delete()
                    }
                    rows.add(buildJsonObject {
                        put("file", input.name); put("variant", variant); put("iteration", iteration)
                        put("source_seconds", seconds); put("decoded_seconds", decodedSeconds)
                        put("preparation_ms", preparationMs); put("encoding_ms", encodingMs)
                        put("bytes", output.length()); put("wav_bytes", wav.length()); put("trimmed", changed)
                    })
                    File(root, "local-results${suffix()}.json").writeText(JsonArray(rows).toString())
                }
            }
        }
    }

    private class Timing : EventListener() {
        var uploadStart = 0L
        var uploadEnd = 0L
        var headers = 0L
        override fun requestBodyStart(call: Call) { uploadStart = SystemClock.elapsedRealtimeNanos() }
        override fun requestBodyEnd(call: Call, byteCount: Long) { uploadEnd = SystemClock.elapsedRealtimeNanos() }
        override fun responseHeadersStart(call: Call) { headers = SystemClock.elapsedRealtimeNanos() }
    }

    private class UploadBody(private val file: File, private val mime: String, private val bitsPerSecond: Long) : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val start = SystemClock.elapsedRealtimeNanos()
            var sent = 0L
            file.inputStream().use { source ->
                val bytes = ByteArray(8192)
                while (true) {
                    val count = source.read(bytes)
                    if (count < 0) break
                    sink.write(bytes, 0, count)
                    sent += count
                    if (bitsPerSecond > 0) {
                        val target = sent * 8 * 1000000000 / bitsPerSecond
                        val delay = target - (SystemClock.elapsedRealtimeNanos() - start)
                        if (delay > 0) Thread.sleep(delay / 1000000, (delay % 1000000).toInt())
                    }
                }
            }
        }
    }

    private fun comparisonApi(timing: Timing): OpenRouterApi {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .eventListener(timing).build()
        return Retrofit.Builder().baseUrl("https://openrouter.ai/api/v1/").client(client)
            .addConverterFactory(responseJson.asConverterFactory("application/json".toMediaType()))
            .build().create(OpenRouterApi::class.java)
    }

    @Test fun prepareOriginalRecordings() = runBlocking(Dispatchers.IO) {
        assumeTrue("Explicit local recording comparison opt-in required", InstrumentationRegistry.getArguments().getString("recordingCompare") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = root()
        val manifest = Json.parseToJsonElement(File(root, "recording-inputs.json").readText()).jsonArray
        val outputs = File(root, "recording-outputs").apply { mkdirs() }
        val rows = mutableListOf<JsonElement>()
        for (entry in manifest) {
            val metadata = entry.jsonObject
            val input = File(root, "recording-inputs/${metadata["file"]!!.jsonPrimitive.content}")
            val decoded = File(outputs, "decoded.wav")
            val trimmed = File(outputs, "trimmed.wav")
            val changed = SilenceFilter(context).convertAndTrim(input, decoded, trimmed)
            val sourceSeconds = (decoded.length() - 44) / 32000.0
            val shortenedSeconds = if (changed) (trimmed.length() - 44) / 32000.0 else sourceSeconds
            rows.add(buildJsonObject {
                put("file", input.name); put("original_seconds", metadata["duration_seconds"]!!)
                put("decoded_seconds", sourceSeconds); put("shortened_seconds", shortenedSeconds)
                put("original_bytes", input.length()); put("trimmed", changed)
                put("original_codec", metadata["codec"]!!); put("sample_rate", metadata["sample_rate"]!!)
                put("channels", metadata["channels"]!!)
                for (variant in listOf("aac_64", "mp3_32")) {
                    if (changed) {
                        val encoded = File(outputs, "encoded.${extension(variant)}")
                        val checkFile = File(outputs, "check.wav")
                        LabAudioEncoders.encode(trimmed, encoded, variant)
                        WavConverter().convert(encoded, checkFile)
                        val seconds = (checkFile.length() - 44) / 32000.0
                        check(kotlin.math.abs(seconds - shortenedSeconds) < 0.3)
                        if (variant.startsWith("aac")) check(seconds + 0.0000625 >= shortenedSeconds)
                        put("${variant}_bytes", encoded.length()); put("${variant}_seconds", seconds)
                        encoded.delete(); checkFile.delete()
                    } else {
                        put("${variant}_bytes", input.length()); put("${variant}_seconds", metadata["duration_seconds"]!!)
                    }
                }
            })
            File(root, "recording-plan${suffix()}.json").writeText(JsonArray(rows).toString())
            decoded.delete(); trimmed.delete()
        }
    }

    @Test fun compareOriginalRecordings() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit paid recording comparison opt-in required", args.getString("recordingCompareLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = root()
        val plan = Json.parseToJsonElement(File(root, "recording-plan${suffix()}.json").readText()).jsonArray
        val ledger = File(root, "recording-results${suffix()}.json")
        check(!ledger.exists()) { "Existing paid comparison ledger; inspect it before any further session" }
        ledger.writeText("[]")
        val keyFile = File(root, "key")
        val key = keyFile.readText().trim()
        keyFile.delete()
        val budgetFile = File(root, "budget.json")
        val budget = Json.parseToJsonElement(budgetFile.readText()).jsonObject
        var reserved = budget["reserved_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var reported = budget["reported_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var requests = budget["requests"]!!.jsonPrimitive.int
        var sessionRequests = 0
        fun saveBudget() = budgetFile.writeText(buildJsonObject {
            put("reserved_usd", reserved.toPlainString()); put("reported_usd", reported.toPlainString()); put("requests", requests)
        }.toString())
        val timing = Timing()
        val api = comparisonApi(timing)
        // Warm the shared TLS connection without a paid transcription or saving account information.
        api.validate("Bearer $key")
        val rows = mutableListOf<JsonElement>()
        val orders = listOf(
            listOf("original", "aac_64", "mp3_32"), listOf("aac_64", "mp3_32", "original"),
            listOf("mp3_32", "original", "aac_64"), listOf("original", "mp3_32", "aac_64"),
            listOf("mp3_32", "aac_64", "original"), listOf("aac_64", "original", "mp3_32")
        )
        for (entry in plan) {
            val metadata = entry.jsonObject
            val name = metadata["file"]!!.jsonPrimitive.content
            val input = File(root, "recording-inputs/$name")
            require(input.isFile && input.length() in 45L until 25L * 1024 * 1024)
            val sourceSeconds = metadata["original_seconds"]!!.jsonPrimitive.double
            var reference: List<String> = emptyList()
            val rates = if (name.substringBeforeLast('.') + ".mp3" in selected) listOf(0L, 1000000L) else listOf(0L)
            for (rate in rates) {
                for ((iteration, order) in orders.withIndex()) {
                    for (variant in order) {
                        val reservation = BigDecimal.valueOf(sourceSeconds).multiply(BigDecimal("0.0001")).add(BigDecimal("0.01"))
                        check(reserved + reservation <= BigDecimal("5") && ++sessionRequests <= 400) { "Comparison credit budget exhausted" }
                        reserved += reservation; requests++; saveBudget()
                        val decoded = File(root, "comparison-decoded.wav")
                        val trimmed = File(root, "comparison-trimmed.wav")
                        val encoded = File(root, "comparison-encoded.${extension(variant)}")
                        val start = now()
                        try {
                            var upload = input
                            var changed = false
                            if (variant != "original") changed = SilenceFilter(context).convertAndTrim(input, decoded, trimmed)
                            val prepared = now()
                            if (changed) {
                                LabAudioEncoders.encode(trimmed, encoded, variant)
                                upload = encoded
                            }
                            val encodedAt = now()
                            timing.uploadStart = 0; timing.uploadEnd = 0; timing.headers = 0
                            val apiStart = now()
                            val response = api.transcribe("Bearer $key", "moondream/parakeet-ultra".toRequestBody("text/plain".toMediaType()),
                                MultipartBody.Part.createFormData("file", "audio.${upload.extension}",
                                    UploadBody(upload, if (upload.extension == "mp3") "audio/mpeg" else "audio/mp4", rate)), null)
                            val responded = now()
                            val bytes = upload.length()
                            decoded.delete(); trimmed.delete(); encoded.delete()
                            val end = now()
                            val cost = reportedCost(response.usage)?.toBigDecimal()
                            if (cost != null) { check(cost <= reservation); reported += cost; reserved += cost - reservation }
                            saveBudget()
                            val result = words(response.text)
                            if (rate == 0L && iteration == 0 && variant == "original") reference = result
                            rows.add(buildJsonObject {
                                put("file", name); put("variant", variant); put("iteration", iteration); put("upload_limit_bps", rate)
                                put("source_seconds", sourceSeconds); put("source_bytes", input.length()); put("bytes", bytes)
                                put("trimmed", changed); put("reused_original", upload == input)
                                put("preparation_ms", (prepared - start) / 1e6)
                                put("encoding_ms", if (changed) (encodedAt - prepared) / 1e6 else 0.0)
                                put("api_ms", (responded - apiStart) / 1e6); put("cleanup_ms", (end - responded) / 1e6)
                                put("total_ms", (end - start) / 1e6)
                                put("upload_ms", (timing.uploadEnd - timing.uploadStart) / 1e6)
                                put("post_upload_to_headers_ms", (timing.headers - timing.uploadEnd) / 1e6)
                                put("usage", response.usage ?: JsonNull)
                                put("reference_words", reference.size); put("result_words", result.size)
                                put("word_edits_vs_original", edits(reference, result)); put("normalized_match", reference == result)
                            })
                            ledger.writeText(JsonArray(rows).toString())
                        } finally { decoded.delete(); trimmed.delete(); encoded.delete() }
                    }
                }
            }
        }
    }

    @Test fun live() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit paid codec opt-in required", args.getString("codecLabLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = root()
        val ledger = File(root, "live-results${suffix()}.json")
        check(!ledger.exists()) { "Existing paid codec ledger; do not replay requests" }
        ledger.writeText("[]")
        val keyFile = File(root, "key")
        val key = keyFile.readText().trim()
        keyFile.delete()
        val budgetFile = File(root, "budget.json")
        val budget = Json.parseToJsonElement(budgetFile.readText()).jsonObject
        var reserved = budget["reserved_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var reported = budget["reported_usd"]!!.jsonPrimitive.content.toBigDecimal()
        var requests = budget["requests"]!!.jsonPrimitive.int
        val cap = BigDecimal("5")
        fun saveBudget() = budgetFile.writeText(buildJsonObject {
            put("reserved_usd", reserved.toPlainString()); put("reported_usd", reported.toPlainString()); put("requests", requests)
        }.toString())
        val timing = Timing()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
            .eventListener(timing).build()
        val api = Retrofit.Builder().baseUrl("https://openrouter.ai/api/v1/").client(client)
            .addConverterFactory(responseJson.asConverterFactory("application/json".toMediaType()))
            .build().create(OpenRouterApi::class.java)
        val rows = mutableListOf<JsonElement>()
        val inputs = File(root, "inputs").listFiles()!!.filter { it.extension == "mp3" }.sortedBy { it.name }
        for (input in inputs) {
            var reference: List<String> = emptyList()
            val rates = if (input.name in selected) listOf(0L, 1000000L) else listOf(0L)
            for (rate in rates) {
                val repetitions = if (input.name in selected) 3 else 1
                repeat(repetitions) { iteration ->
                    val order = variants.drop(iteration) + variants.take(iteration)
                    for (variant in order) {
                        val decoded = File(root, "live-decoded.wav")
                        val trimmed = File(root, "live-trimmed.wav")
                        val encoded = File(root, "live-encoded.${extension(variant)}")
                        val start = now()
                        try {
                            val changed = SilenceFilter(context).convertAndTrim(input, decoded, trimmed)
                            val prepared = now()
                            val wav = if (changed) trimmed else decoded
                            val seconds = (wav.length() - 44) / 32000.0
                            if (variant != "wav") LabAudioEncoders.encode(wav, encoded, variant)
                            val encodedAt = now()
                            val upload = if (variant == "wav") wav else encoded
                            val reserve = BigDecimal.valueOf(seconds).multiply(BigDecimal("0.0001")).add(BigDecimal("0.01"))
                            check(reserved + reserve <= cap && ++requests <= 500) { "Credit budget exhausted" }
                            reserved += reserve; saveBudget()
                            timing.uploadStart = 0; timing.uploadEnd = 0; timing.headers = 0
                            val apiStart = now()
                            val response = api.transcribe("Bearer $key", "moondream/parakeet-ultra".toRequestBody("text/plain".toMediaType()),
                                MultipartBody.Part.createFormData("file", "audio.${extension(variant)}", UploadBody(upload, mime(variant), rate)), null)
                            val end = now()
                            val cost = reportedCost(response.usage)?.toBigDecimal()
                            if (cost != null) { check(cost <= reserve) { "Provider price exceeded reservation" }; reported += cost; reserved += cost - reserve }
                            saveBudget()
                            val result = words(response.text)
                            if (rate == 0L && iteration == 0 && variant == "wav") reference = result
                            rows.add(buildJsonObject {
                                put("file", input.name); put("variant", variant); put("iteration", iteration); put("upload_limit_bps", rate)
                                put("source_seconds", seconds); put("bytes", upload.length()); put("wav_bytes", wav.length())
                                put("preparation_ms", (prepared - start) / 1e6); put("encoding_ms", (encodedAt - prepared) / 1e6)
                                put("api_ms", (end - apiStart) / 1e6); put("total_ms", (end - start) / 1e6)
                                put("upload_ms", (timing.uploadEnd - timing.uploadStart) / 1e6)
                                put("post_upload_to_headers_ms", (timing.headers - timing.uploadEnd) / 1e6)
                                put("usage", response.usage ?: JsonNull)
                                put("reference_words", reference.size); put("result_words", result.size)
                                put("word_edits_vs_wav", edits(reference, result)); put("normalized_match", reference == result)
                            })
                            ledger.writeText(JsonArray(rows).toString())
                        } finally { decoded.delete(); trimmed.delete(); encoded.delete() }
                    }
                }
            }
        }
    }
}
