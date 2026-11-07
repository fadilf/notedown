package com.notedown.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Never discards orphan audio: even an unfinalized container remains visible for explicit deletion. */
@Singleton class RecoveryRepository @Inject constructor(@ApplicationContext private val context: Context, private val dao: HistoryDao) {
    suspend fun reconcile(activeRecordingId: String? = null) = withContext(Dispatchers.IO) {
        val known = dao.recordings().map { it.filePath }.toSet()
        File(context.filesDir, "recordings").listFiles().orEmpty().filter { it.nameWithoutExtension != activeRecordingId && it.isFile && it.absolutePath !in known && it.extension in listOf("m4a", "wav") && !it.name.startsWith("notedown-converted-") }.forEach { file ->
            val duration = runCatching {
                MediaMetadataRetriever().use { it.setDataSource(file.absolutePath); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
            }.getOrDefault(0L)
            dao.insert(RecordingEntity(file.nameWithoutExtension, file.lastModified(), duration, file.absolutePath, file.extension, "recovered"))
        }
        dao.pending().filter { System.currentTimeMillis() - it.createdAt > 30000 }.forEach { version ->
            val work = runCatching { WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(version.id)).get() }.getOrNull()
            if (work == null || work.state.isFinished) dao.update(version.copy(status = "failed", errorMessage = "Request was interrupted. Audio is saved; retry to create a new version."))
        }
    }
}
