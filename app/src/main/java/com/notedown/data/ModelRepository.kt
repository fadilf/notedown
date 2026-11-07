package com.notedown.data

import android.content.Context
import com.notedown.api.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class ModelRepository @Inject constructor(@ApplicationContext context: Context, private val api: OpenRouterApi) {
    private val cache = java.io.File(context.filesDir, "models.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(readCache())
    val models = mutable.asStateFlow()
    private fun readCache(): Map<String, List<RouterModel>> = runCatching {
        json.decodeFromString<Map<String, List<RouterModel>>>(cache.readText())
    }.getOrDefault(emptyMap())
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && mutable.value.isNotEmpty() && System.currentTimeMillis() - cache.lastModified() < 86400000) return@withContext
        val transcription = api.models("transcription").data
        val text = api.models("text").data.filter { it.architecture?.get("input_modalities")?.toString()?.contains("text") != false }
        val result = mapOf("transcription" to transcription, "text" to text)
        cache.writeText(json.encodeToString(result)); mutable.value = result
    }
}
