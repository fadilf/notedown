package com.notedown

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notedown.audio.SilenceFilter
import com.notedown.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SilenceFilterDeviceTest {
    @Test fun bundledSileroLoadsAndPreservesSilenceForFallbackAcrossSessions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = File.createTempFile("silence-input-", ".wav", context.cacheDir)
        val output = File.createTempFile("silence-output-", ".wav", context.cacheDir)
        val size = 32000
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(size)
        input.writeBytes(data.array())
        try {
            val filter = SilenceFilter(context)
            repeat(2) { assertFalse(filter.trim(input, output)) }
            assertArrayEquals(data.array(), input.readBytes())
        } finally { input.delete(); output.delete() }
    }

    @Test fun skipSilencePreferencePersistsIndependentlyOfWavMode() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        val original = settings.settings.first()
        try {
            settings.flag("skipSilence", true)
            assertTrue(settings.settings.first().skipSilence)
            assertEquals(original.wav, settings.settings.first().wav)
            settings.flag("skipSilence", false)
            assertFalse(settings.settings.first().skipSilence)
        } finally { settings.flag("skipSilence", original.skipSilence) }
    }
}
