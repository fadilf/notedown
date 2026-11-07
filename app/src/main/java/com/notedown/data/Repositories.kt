package com.notedown.data

import android.content.Context
import androidx.work.*
import com.notedown.domain.TranscriptionWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class RecordingRepository @Inject constructor(private val dao: HistoryDao) {
    val history = dao.observe()
    val usage = dao.usage()
    fun transcript(id: String) = dao.observeTranscript(id)
    suspend fun save(recording: RecordingEntity) = dao.insert(recording)
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val recording = dao.recording(id) ?: return@withContext
        // Cascade transcript versions, but preserve the usage ledger for accurate lifetime costs.
        dao.delete(id); File(recording.filePath).delete(); Unit
    }
    suspend fun deleteAll() { dao.recordings().forEach { delete(it.id) } }
}
@Singleton class TranscriptionRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val dao: HistoryDao, private val settings: SettingsRepository) {
    suspend fun enqueue(recordingId: String, modelOverride: String? = null): String {
        val config = settings.settings.first()
        val model = modelOverride ?: config.model
        require(model.isNotBlank()) { "Select a transcription model in Settings." }
        val id = UUID.randomUUID().toString()
        dao.insert(TranscriptionEntity(id, recordingId, System.currentTimeMillis(), model))
        val data = workDataOf("id" to id, "language" to config.language, "cleanup" to config.cleanup,
            "wav" to config.wav, "cleanupModel" to config.cleanupModel, "prompt" to config.prompt.take(3000))
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>().setId(UUID.fromString(id)).setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("recording:$recordingId").build()
        // Wait for WorkManager's durable enqueue, not merely its in-memory request construction.
        try {
            withContext(Dispatchers.IO) { WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request).result.get() }
        } catch (e: Exception) {
            dao.transcript(id)?.let { dao.update(it.copy(status = "failed", errorMessage = "Could not schedule transcription. Retry from history.")) }
            throw e
        }
        return id
    }
    fun cancel(recordingId: String) { WorkManager.getInstance(context).cancelAllWorkByTag("recording:$recordingId") }
}
