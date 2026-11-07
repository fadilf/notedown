package com.notedown

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notedown.audio.AndroidAudioRecorder
import com.notedown.audio.WavConverter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @Test fun visibleActivityAutomaticallyStartsMicrophoneForegroundService() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = ActivityScenario.launch(com.notedown.ui.CaptureActivity::class.java)
        try {
            delay(1600)
            @Suppress("DEPRECATION")
            val running = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
            assertTrue(running.any { it.service.className == "com.notedown.audio.RecordingService" && it.foreground })
            context.startService(android.content.Intent(context, com.notedown.audio.RecordingService::class.java).setAction("cancel"))
            delay(500)
        } finally { activity.close() }
    }
    @Test fun realMicrophoneRecordsM4aConvertsToWavAndCancelDiscards() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            delay(600)
            val recorder = AndroidAudioRecorder(context)
            recorder.startRecording(); delay(1100)
            val audio = recorder.stopRecording()
            assertTrue(File(audio.filePath).length() > 44); assertTrue(audio.durationMs >= 1000)
            val wav = File(context.cacheDir, "test-conversion.wav")
            WavConverter().convert(File(audio.filePath), wav)
            assertEquals("RIFF", wav.inputStream().use { String(it.readNBytes(4)) })
            assertTrue(wav.length() > 44)
            File(audio.filePath).delete(); wav.delete()
            val cancelled = recorder.startRecording(true); delay(400); recorder.cancelRecording()
            assertFalse(File(context.filesDir, "recordings/${cancelled.id}.wav").exists())
        } finally { activity.close() }
    }
}
