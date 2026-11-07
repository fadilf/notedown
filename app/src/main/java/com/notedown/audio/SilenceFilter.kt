package com.notedown.audio

import android.content.Context
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class SilenceFilter @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun trim(input: File, output: File): Boolean = withContext(Dispatchers.IO) {
        VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512, Mode.NORMAL).use { detector ->
            SilenceTrimmer().trim(input, output) { detector.isSpeech(it) }
        }
    }
}
