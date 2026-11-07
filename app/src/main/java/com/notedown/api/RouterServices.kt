package com.notedown.api

import com.notedown.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import javax.inject.Inject

fun safeApiError(error: Throwable): String = when (error) {
    is HttpException -> when (error.code()) {
        401, 403 -> "API key rejected. Replace or validate it in Settings."
        402 -> "OpenRouter credits are exhausted. Add credits and retry."
        404 -> "Model unavailable. Refresh models and select another."
        413 -> "Audio exceeds provider size limits. Use a shorter recording."
        429 -> "Rate limited. Wait before retrying."
        400, 415, 422 -> "Provider rejected the audio or options. Try WAV compatibility mode, another language, or another model."
        in 500..599 -> "OpenRouter or its provider is temporarily unavailable. Retry later."
        else -> "OpenRouter request failed (HTTP ${error.code()})."
    }
    is java.net.SocketTimeoutException -> "Request timed out. Audio is saved; retry from history."
    is IOException -> "Network unavailable. Audio is saved; retry when connected."
    else -> "Request failed. Audio is saved; check your model and settings."
}
fun reportedCost(usage: JsonObject?): String? = usage?.get("cost")?.jsonPrimitive?.contentOrNull?.let { runCatching { it.toBigDecimal().toPlainString() }.getOrNull() }
class TranscriptionService @Inject constructor(private val api: OpenRouterApi, private val keys: ApiKeyStorage) {
    suspend fun transcribe(recording: RecordingEntity, settings: Settings): TranscriptionResponse = withContext(Dispatchers.IO) {
        require(settings.model.isNotBlank()) { "Select a transcription model in Settings." }
        val file = File(recording.filePath)
        require(file.isFile && file.length() > 44) { "Recording is empty or missing." }
        require(file.length() < 25L * 1024 * 1024) { "Recording exceeds the 25 MB upload limit." }
        val converted = if (settings.wav && recording.audioFormat != "wav") File.createTempFile("notedown-converted-", ".wav", file.parentFile) else null
        try {
        if (converted != null) com.notedown.audio.WavConverter().convert(file, converted)
        val upload = converted ?: file
        val mime = if (upload.extension == "wav") "audio/wav" else "audio/mp4"
        api.transcribe("Bearer ${keys.read() ?: error("Add an API key in Settings.")}", settings.model.toRequestBody("text/plain".toMediaType()),
            MultipartBody.Part.createFormData("file", upload.name, upload.asRequestBody(mime.toMediaType())),
            settings.language.takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaType()))
        } finally { converted?.delete() }
    }
}
class CleanupService @Inject constructor(private val api: OpenRouterApi, private val keys: ApiKeyStorage) {
    suspend fun clean(text: String, settings: Settings): ChatResponse {
        require(settings.cleanupModel.isNotBlank()) { "Select a cleanup model." }
        return api.cleanup("Bearer ${keys.read() ?: error("API key missing")}", ChatRequest(settings.cleanupModel,
            listOf(Message("system", settings.prompt), Message("user", text))))
    }
}
