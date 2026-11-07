package com.notedown.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordings")
data class RecordingEntity(@PrimaryKey val id: String, val createdAt: Long, val durationMs: Long, val filePath: String, val audioFormat: String, val status: String = "saved")
@Entity(tableName = "transcriptions", foreignKeys = [ForeignKey(entity = RecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE)], indices = [Index("recordingId")])
data class TranscriptionEntity(@PrimaryKey val id: String, val recordingId: String, val createdAt: Long, val modelId: String,
    val rawText: String? = null, val cleanedText: String? = null, val cleanupModelId: String? = null,
    val status: String = "queued", val errorMessage: String? = null, val insertionStatus: String? = null) {
    val text: String get() = cleanedText ?: rawText.orEmpty()
}
@Entity(tableName = "usage")
data class UsageEntity(@PrimaryKey val id: String, val transcriptionId: String, val modelId: String, val stage: String,
    val createdAt: Long, val durationMs: Long, val costUsd: String?, val metadataJson: String?)
data class RecordingWithTranscripts(@Embedded val recording: RecordingEntity, @Relation(parentColumn = "id", entityColumn = "recordingId") val transcripts: List<TranscriptionEntity>)
@Dao interface HistoryDao {
    @Transaction @Query("SELECT * FROM recordings ORDER BY createdAt DESC") fun observe(): Flow<List<RecordingWithTranscripts>>
    @Query("SELECT * FROM usage ORDER BY createdAt DESC") fun usage(): Flow<List<UsageEntity>>
    @Insert suspend fun insert(recording: RecordingEntity)
    @Insert suspend fun insert(transcription: TranscriptionEntity)
    @Insert suspend fun insert(usage: UsageEntity)
    @Update suspend fun update(transcription: TranscriptionEntity)
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun recording(id: String): RecordingEntity?
    @Query("SELECT * FROM recordings") suspend fun recordings(): List<RecordingEntity>
    @Query("SELECT * FROM transcriptions WHERE id = :id") fun observeTranscript(id: String): Flow<TranscriptionEntity?>
    @Query("SELECT * FROM transcriptions WHERE id = :id") suspend fun transcript(id: String): TranscriptionEntity?
    @Query("DELETE FROM recordings WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM transcriptions WHERE status IN ('queued', 'processing')") suspend fun pending(): List<TranscriptionEntity>
    @Transaction suspend fun saveResponse(version: TranscriptionEntity, usage: UsageEntity) { insert(usage); update(version) }
    @Query("UPDATE transcriptions SET status = 'failed', errorMessage = 'Interrupted before completion. Retry creates a new version.' WHERE status = 'processing'") suspend fun recoverInterrupted()
}
@Database(entities = [RecordingEntity::class, TranscriptionEntity::class, UsageEntity::class], version = 1, exportSchema = true)
abstract class NotedownDatabase : RoomDatabase() { abstract fun history(): HistoryDao }
