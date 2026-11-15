package com.notedown

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.api.*
import com.notedown.audio.SilenceFilter
import com.notedown.audio.WavConverter
import com.notedown.data.ApiKeyStorage
import com.notedown.data.RecordingEntity
import com.notedown.data.Settings
import com.notedown.di.AppModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.io.File

/** Opt-in live profiling; input and key are supplied privately at runtime, never bundled. */
@RunWith(AndroidJUnit4::class)
class SilencePerformanceTest {
    @Test fun profile() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit live profiling opt-in", args.getString("profileSilence") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "silence-profile")
        val input = File(directory, args.getString("input") ?: "input.m4a")
        val keyFile = File(directory, "key")
        val keys = ApiKeyStorage(context)
        val oldKey = keys.read()
        val rows = mutableListOf<JsonElement>()
        val api = AppModule.api()
        val model = args.getString("model") ?: "mistralai/voxtral-small-24b-2507"
        val repetitions = args.getString("repetitions")?.toInt() ?: 1
        val chat = args.getString("transport") == "chat"
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            if (repetitions > 0) {
                keys.write(keyFile.readText().trim())
                keyFile.delete()
            }
            // Profile exact production decode and Silero implementations, including model load.
            repeat(3) { iteration ->
                val wav = File(directory, "decoded.wav")
                val trimmed = File(directory, "trimmed.wav")
                val start = SystemClock.elapsedRealtimeNanos()
                WavConverter().convert(input, wav)
                val decoded = SystemClock.elapsedRealtimeNanos()
                val changed = SilenceFilter(context).trim(wav, trimmed)
                val end = SystemClock.elapsedRealtimeNanos()
                rows.add(buildJsonObject {
                    put("kind", "local"); put("iteration", iteration)
                    put("decode_ms", (decoded - start) / 1e6)
                    put("silero_and_trim_ms", (end - decoded) / 1e6)
                    put("changed", changed); put("input_bytes", input.length())
                    put("wav_bytes", wav.length()); put("trimmed_bytes", trimmed.length())
                    put("wav_seconds", (wav.length() - 44) / 32000.0)
                    put("trimmed_seconds", (trimmed.length() - 44) / 32000.0)
                })
                wav.delete(); trimmed.delete()
            }
            repeat(repetitions) { pair ->
                val order = if (pair % 2 == 0) listOf(false, true) else listOf(true, false)
                for (skip in order) {
                    var apiStart = 0L
                    var uploadBytes = 0L
                    val measured = object : OpenRouterApi by api {
                        override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse {
                            uploadBytes = file.body.contentLength()
                            apiStart = SystemClock.elapsedRealtimeNanos()
                            if (chat) {
                                val buffer = Buffer()
                                file.body.writeTo(buffer)
                                val encoded = android.util.Base64.encodeToString(buffer.readByteArray(), android.util.Base64.NO_WRAP)
                                val payload = buildJsonObject {
                                    put("model", args.getString("model")!!)
                                    putJsonArray("messages") {
                                        addJsonObject {
                                            put("role", "user")
                                            putJsonArray("content") {
                                                addJsonObject { put("type", "text"); put("text", "Transcribe this audio verbatim. Return only the transcript, without commentary.") }
                                                addJsonObject {
                                                    put("type", "input_audio")
                                                    putJsonObject("input_audio") {
                                                        put("data", encoded)
                                                        put("format", if (file.body.contentType().toString() == "audio/wav") "wav" else "m4a")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    put("temperature", 0)
                                }
                                val request = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
                                    .header("Authorization", key)
                                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                                return client.newCall(request).execute().use { result ->
                                    check(result.isSuccessful) { "HTTP ${result.code}" }
                                    val json = Json.parseToJsonElement(result.body.string()).jsonObject
                                    val text = json["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                                    TranscriptionResponse(text, json["usage"] as? JsonObject)
                                }
                            }
                            return api.transcribe(key, model, file, language)
                        }
                    }
                    val start = SystemClock.elapsedRealtimeNanos()
                    var status = "ok"
                    var response: TranscriptionResponse? = null
                    try {
                        response = TranscriptionService(measured, keys, SilenceFilter(context)).transcribe(
                            RecordingEntity("profile", 0, 64134, input.absolutePath, "m4a"),
                            Settings(model = model, skipSilence = skip))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        status = if (e is HttpException) "http_${e.code()}" else e.javaClass.simpleName
                    }
                    val end = SystemClock.elapsedRealtimeNanos()
                    rows.add(buildJsonObject {
                        put("kind", "live"); put("pair", pair); put("skip_silence", skip)
                        put("model", model); put("status", status); put("upload_bytes", uploadBytes)
                        put("transport", if (chat) "chat_adapter" else "production")
                        put("total_ms", (end - start) / 1e6)
                        if (apiStart != 0L) {
                            put("preparation_ms", (apiStart - start) / 1e6)
                            put("api_ms", (end - apiStart) / 1e6)
                        }
                        response?.let {
                            put("transcript_characters", it.text.length)
                            put("reported_cost", reportedCost(it.usage)?.let(::JsonPrimitive) ?: JsonNull)
                            put("usage", it.usage ?: JsonNull)
                        }
                    })
                    File(directory, "results.json").writeText(JsonArray(rows).toString())
                    check(status == "ok") { "Live profiling stopped: $status; timings saved privately" }
                }
            }
        } finally {
            File(directory, "results.json").writeText(JsonArray(rows).toString())
            keyFile.delete()
            if (oldKey == null) keys.remove() else keys.write(oldKey)
        }
    }
}
