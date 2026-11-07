package com.notedown.domain

import com.notedown.api.*
import com.notedown.data.*
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

// A cleanup failure must never discard a successful speech-to-text response.
suspend fun cleanupOrRaw(raw: String, enabled: Boolean, clean: suspend (String) -> String): Pair<String?, String?> {
    if (!enabled || raw.isBlank()) return null to null
    return try { clean(raw).takeIf { it.isNotBlank() }?.let { it to null } ?: (null to "Cleanup returned empty text; using raw transcript.") }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null to "Cleanup failed; using raw transcript. Retranscribe to try again." }
}
class TranscriptionPipeline @Inject constructor(private val dao: HistoryDao, private val stt: TranscriptionService, private val cleanup: CleanupService) {
    suspend fun run(id: String, config: Settings) {
        var version = dao.transcript(id) ?: return
        val recording = dao.recording(version.recordingId) ?: return
        version = version.copy(status = "processing", errorMessage = null)
        dao.update(version)
        try {
            // A resumed worker reuses an already persisted raw response, avoiding a second STT charge.
            if (version.rawText == null) {
                val start = System.currentTimeMillis()
                val response = stt.transcribe(recording, config.copy(model = version.modelId))
                version = version.copy(rawText = response.text)
                dao.saveResponse(version, UsageEntity(UUID.randomUUID().toString(), id, version.modelId, "transcription", start,
                    System.currentTimeMillis() - start, reportedCost(response.usage), response.usage?.toString()))
            }
            val (cleaned, warning) = cleanupOrRaw(version.rawText.orEmpty(), config.cleanup) { raw ->
                val start = System.currentTimeMillis()
                val response = cleanup.clean(raw, config)
                dao.insert(UsageEntity(UUID.randomUUID().toString(), id, config.cleanupModel, "cleanup", start,
                    System.currentTimeMillis() - start, reportedCost(response.usage), response.usage?.toString()))
                response.choices.firstOrNull()?.message?.content.orEmpty()
            }
            dao.update(version.copy(cleanedText = cleaned, cleanupModelId = config.cleanupModel.takeIf { config.cleanup }, status = "complete",
                errorMessage = warning ?: "No speech detected.".takeIf { version.rawText.isNullOrBlank() }))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            dao.update(version.copy(status = "failed", errorMessage = when (e) {
                is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf { it.length < 150 } ?: "Check recording and model settings."
                else -> safeApiError(e)
            }))
        }
    }
}
