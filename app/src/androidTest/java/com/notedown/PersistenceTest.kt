package com.notedown

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notedown.api.*
import com.notedown.audio.*
import com.notedown.data.*
import com.notedown.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private lateinit var db: NotedownDatabase
    private lateinit var context: Context
    @Before fun open() { context = ApplicationProvider.getApplicationContext(); db = Room.inMemoryDatabaseBuilder(context, NotedownDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun recording() { db.history().insert(RecordingEntity("r", 1, 1000, "/private/r.m4a", "m4a")) }
    @Test fun versionsCoexistAndCascadeOnRecordingDeletion() = runBlocking {
        recording(); val dao = db.history()
        dao.insert(TranscriptionEntity("v1", "r", 1, "model-a", rawText = "first", status = "complete"))
        dao.insert(TranscriptionEntity("v2", "r", 2, "model-b", rawText = "second", status = "complete"))
        assertEquals(2, dao.observe().first().single().transcripts.size)
        assertEquals("first", dao.transcript("v1")!!.rawText)
        dao.delete("r"); assertNull(dao.transcript("v1")); assertNull(dao.transcript("v2"))
    }
    @Test fun foreignKeyRejectsOrphanTranscript() = runBlocking {
        try { db.history().insert(TranscriptionEntity("bad", "missing", 1, "a")); fail("Expected foreign key failure") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }
    @Test fun settingsPersistAcrossRepositoryInstances() = runBlocking {
        val first = SettingsRepository(context); first.text("model", "chosen/model"); first.flag("cleanup", true)
        val second = SettingsRepository(context).settings.first()
        assertEquals("chosen/model", second.model); assertTrue(second.cleanup)
        first.text("model", ""); first.flag("cleanup", false)
    }
    @Test fun keystoreRoundTripAndRemoval() {
        val keys = ApiKeyStorage(context); keys.write("test-local-credential")
        assertEquals("test-local-credential", ApiKeyStorage(context).read()); keys.remove(); assertNull(keys.read())
    }
    @Test fun recordingLifecycleStartsOnceAndCancelDiscards() = runBlocking {
        val fake = FakeRecorder()
        val settings = SettingsRepository(context)
        val manager = RecordingSessionManager(fake, settings, RecordingRepository(db.history()), TranscriptionRepository(context, db.history(), settings))
        manager.start(); manager.start(); assertEquals(1, fake.starts); assertTrue(manager.state.value is DictationState.Recording)
        manager.cancel(); assertEquals(1, fake.cancels); assertEquals(DictationState.Idle, manager.state.value)
        manager.cancel(); assertEquals(1, fake.cancels)
    }
    @Test fun stopSavesAudioBeforeSubmissionFailureAndIgnoresDuplicateStop() = runBlocking {
        val fake = FakeRecorder()
        val settings = SettingsRepository(context).apply { text("model", "") }
        val manager = RecordingSessionManager(fake, settings, RecordingRepository(db.history()), TranscriptionRepository(context, db.history(), settings))
        manager.start(); manager.stop(); manager.stop()
        assertNotNull(db.history().recording("r"))
        assertTrue(manager.state.value is DictationState.Error)
        assertEquals("r", (manager.state.value as DictationState.Error).recordingId)
    }
    @Test fun cleanupFailurePersistsSuccessfulRawAndUsage() = runBlocking {
        recording(); val dao = db.history(); dao.insert(TranscriptionEntity("v", "r", 1, "stt"))
        val api = FakeApi(); val keys = ApiKeyStorage(context).apply { write("local-test") }
        // Existing audio is checked by the STT service, so resume from a persisted raw result.
        dao.update(dao.transcript("v")!!.copy(rawText = "raw speech"))
        val pipeline = TranscriptionPipeline(dao, TranscriptionService(api, keys), CleanupService(api, keys))
        pipeline.run("v", Settings(cleanup = true, cleanupModel = "cleanup"))
        assertEquals("complete", dao.transcript("v")!!.status)
        assertEquals("raw speech", dao.transcript("v")!!.text)
        assertNotNull(dao.transcript("v")!!.errorMessage); assertEquals(0, api.sttCalls); keys.remove()
    }
    @Test fun missingAudioIsSavedAsRetryableFailure() = runBlocking {
        recording(); val dao = db.history(); dao.insert(TranscriptionEntity("v", "r", 1, "stt"))
        val api = FakeApi(); val keys = ApiKeyStorage(context)
        TranscriptionPipeline(dao, TranscriptionService(api, keys), CleanupService(api, keys)).run("v", Settings(model = "stt"))
        assertEquals("failed", dao.transcript("v")!!.status); assertNotNull(dao.recording("r")); assertEquals(0, api.sttCalls)
    }
    private class FakeRecorder : AudioRecorder {
        var starts = 0; var cancels = 0
        override suspend fun startRecording(wav: Boolean): RecordingSession { starts++; return RecordingSession("r", 1) }
        override suspend fun stopRecording() = RecordingEntity("r", 1, 1000, "test", "m4a")
        override suspend fun cancelRecording() { cancels++ }
    }
    private class FakeApi : OpenRouterApi {
        var sttCalls = 0
        override suspend fun validate(key: String) = JsonObject(emptyMap())
        override suspend fun models(modality: String) = ModelList(emptyList())
        override suspend fun transcribe(key: String, model: RequestBody, file: MultipartBody.Part, language: RequestBody?): TranscriptionResponse { sttCalls++; return TranscriptionResponse("speech") }
        override suspend fun cleanup(key: String, request: ChatRequest): ChatResponse = error("provider down")
    }
}
