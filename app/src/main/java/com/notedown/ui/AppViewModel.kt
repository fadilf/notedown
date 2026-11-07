package com.notedown.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notedown.api.*
import com.notedown.data.*
import com.notedown.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel class AppViewModel @Inject constructor(val settingsRepository: SettingsRepository,
    private val recordings: RecordingRepository, private val transcriptions: TranscriptionRepository,
    private val keys: ApiKeyStorage, private val api: OpenRouterApi, val modelRepository: ModelRepository,
    val sessions: RecordingSessionManager, private val recovery: RecoveryRepository) : ViewModel() {
    val history = recordings.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val usage = recordings.usage.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val hasKey = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    init { viewModelScope.launch { runCatching { recovery.reconcile((sessions.state.value as? DictationState.Recording)?.session?.id) }; hasKey.value = withContext(Dispatchers.IO) { keys.read() != null }; runCatching { modelRepository.refresh() } } }
    fun text(key: String, value: String) { viewModelScope.launch { settingsRepository.text(key, value) } }
    fun flag(key: String, value: Boolean) { viewModelScope.launch { settingsRepository.flag(key, value) } }
    fun key(value: String) { viewModelScope.launch {
        busy.value = true
        try {
            withContext(Dispatchers.IO) {
                val candidate = value.trim().ifBlank { keys.read() ?: error("Enter your OpenRouter API key.") }
                api.validate("Bearer $candidate"); keys.write(candidate)
            }
            hasKey.value = true; message.value = "API key validated and encrypted on this device."
        } catch (e: Exception) { message.value = safeApiError(e) } finally { busy.value = false }
    } }
    fun removeKey() { viewModelScope.launch { withContext(Dispatchers.IO) { keys.remove() }; hasKey.value = false; message.value = "API key removed." } }
    fun refresh() { viewModelScope.launch { busy.value = true; try { modelRepository.refresh(true); message.value = "Models refreshed." } catch(e: Exception) { message.value = safeApiError(e) } finally { busy.value = false } } }
    fun transcribe(id: String, model: String) { viewModelScope.launch {
        try { transcriptions.enqueue(id, model); message.value = "New transcription queued. Your existing versions are kept." }
        catch (_: Exception) { message.value = "Choose a transcription model in Settings first." }
    } }
    fun delete(id: String) { viewModelScope.launch { transcriptions.cancel(id); recordings.delete(id) } }
    fun deleteAll() { viewModelScope.launch { history.value.forEach { transcriptions.cancel(it.recording.id) }; recordings.deleteAll() } }
}
