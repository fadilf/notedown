package com.notedown.domain

import com.notedown.audio.*
import com.notedown.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DictationState {
    data object Idle : DictationState
    data object Starting : DictationState
    data class Recording(val session: RecordingSession) : DictationState
    data object Saving : DictationState
    data class Processing(val transcriptId: String) : DictationState
    data class Result(val text: String, val message: String) : DictationState
    data class Error(val message: String, val recordingId: String? = null) : DictationState
}
@Singleton class RecordingSessionManager @Inject constructor(private val recorder: AudioRecorder, private val settings: SettingsRepository,
    private val history: RecordingRepository, private val queue: TranscriptionRepository) {
    private val mutable = MutableStateFlow<DictationState>(DictationState.Idle)
    val state = mutable.asStateFlow()
    var targetToken: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    suspend fun start() {
        if (mutable.value is DictationState.Recording || mutable.value == DictationState.Starting || mutable.value == DictationState.Saving || mutable.value is DictationState.Processing) return
        mutable.value = DictationState.Starting
        try {
            mutable.value = DictationState.Recording(recorder.startRecording(settings.settings.first().wav))
        } catch (e: Exception) { mutable.value = DictationState.Error("Microphone unavailable. Check permission and close other recording apps.") }
    }
    suspend fun stop() {
        if (mutable.value !is DictationState.Recording) return
        mutable.value = DictationState.Saving
        var savedId: String? = null
        try {
            val recording = recorder.stopRecording()
            history.save(recording)
            savedId = recording.id
            val id = queue.enqueue(recording.id)
            mutable.value = DictationState.Processing(id)
            scope.launch {
                val result = history.transcript(id).filterNotNull().first { it.status in listOf("complete", "failed") }
                mutable.value = if (result.status == "failed") DictationState.Error(result.errorMessage ?: "Transcription failed", recording.id)
                    else DictationState.Result(result.text, result.errorMessage ?: "Transcript saved")
            }
        } catch (e: Exception) { mutable.value = DictationState.Error(if (savedId != null) "Recording saved. Select a model and retry from history." else "Could not finish this recording. Try again.", savedId) }
    }
    suspend fun cancel() {
        if (mutable.value !is DictationState.Recording && mutable.value != DictationState.Starting) return
        recorder.cancelRecording(); targetToken = null; mutable.value = DictationState.Idle
    }
    fun startFailed() { mutable.value = DictationState.Error("Microphone access could not start. Reopen Notedown and check permissions.") }
    fun dismiss() { if (mutable.value is DictationState.Result || mutable.value is DictationState.Error) { targetToken = null; mutable.value = DictationState.Idle } }
}
