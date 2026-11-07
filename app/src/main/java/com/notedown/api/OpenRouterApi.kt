package com.notedown.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

@Serializable data class ModelList(val data: List<RouterModel>)
@Serializable data class RouterModel(val id: String, val name: String = id, val pricing: JsonObject? = null, val architecture: JsonObject? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String> = emptyList())
@Serializable data class TranscriptionResponse(val text: String, val usage: JsonObject? = null)
@Serializable data class Message(val role: String, val content: String)
@Serializable data class ChatRequest(val model: String, val messages: List<Message>, val stream: Boolean = false)
@Serializable data class Choice(val message: Message)
@Serializable data class ChatResponse(val choices: List<Choice>, val usage: JsonObject? = null)
interface OpenRouterApi {
    @GET("key") suspend fun validate(@Header("Authorization") key: String): JsonObject
    @GET("models") suspend fun models(@Query("output_modalities") modality: String): ModelList
    @Multipart @POST("audio/transcriptions") suspend fun transcribe(@Header("Authorization") key: String,
        @Part("model") model: RequestBody, @Part file: MultipartBody.Part, @Part("language") language: RequestBody?): TranscriptionResponse
    @POST("chat/completions") suspend fun cleanup(@Header("Authorization") key: String, @Body request: ChatRequest): ChatResponse
}
