package com.notedown.audio

import com.notedown.data.RecordingEntity

data class RecordingSession(val id: String, val startedAt: Long)
interface AudioRecorder {
    suspend fun startRecording(wav: Boolean = false): RecordingSession
    suspend fun stopRecording(): RecordingEntity
    suspend fun cancelRecording()
}
