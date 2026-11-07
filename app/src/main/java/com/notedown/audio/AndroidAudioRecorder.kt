package com.notedown.audio

import android.content.Context
import android.media.*
import android.os.SystemClock
import com.notedown.data.RecordingEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AndroidAudioRecorder(private val context: Context) : AudioRecorder {
    private val mutex = Mutex()
    private var recorder: MediaRecorder? = null
    private var pcm: AudioRecord? = null
    private var writer: Job? = null
    private var session: RecordingSession? = null
    private var file: File? = null
    private var monotonicStart = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> /* Stop retains any recoverable PCM. */ })
    override suspend fun startRecording(wav: Boolean): RecordingSession = mutex.withLock { withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission denied")
        check(session == null) { "Already recording" }
        val next = RecordingSession(UUID.randomUUID().toString(), System.currentTimeMillis())
        val output = File(context.filesDir, "recordings/${next.id}.${if (wav) "wav" else "m4a"}").apply { parentFile!!.mkdirs() }
        file = output
        try {
            if (wav) {
                val buffer = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
                val input = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
                pcm = input
                check(input.state == AudioRecord.STATE_INITIALIZED)
                input.startRecording()
                writer = scope.launch {
                    RandomAccessFile(output, "rw").use { stream ->
                        stream.write(ByteArray(44)); val bytes = ByteArray(buffer)
                        while (isActive) {
                            val count = input.read(bytes, 0, bytes.size)
                            if (count > 0) stream.write(bytes, 0, count) else error("Microphone interrupted")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val media = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                recorder = media
                media.setAudioSource(MediaRecorder.AudioSource.MIC); media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); media.setAudioSamplingRate(44100); media.setAudioEncodingBitRate(96000)
                media.setOutputFile(output.absolutePath); media.prepare(); media.start()
            }
            monotonicStart = SystemClock.elapsedRealtime(); session = next; next
        } catch (e: Exception) { release(); output.delete(); file = null; throw e }
    } }
    override suspend fun stopRecording(): RecordingEntity = mutex.withLock { withContext(Dispatchers.IO) {
        val current = checkNotNull(session)
        val output = checkNotNull(file)
        val duration = SystemClock.elapsedRealtime() - monotonicStart
        try {
            if (pcm != null) {
                writer?.cancel(); pcm?.stop(); writer?.join()
                RandomAccessFile(output, "rw").use { stream ->
                    val size = (stream.length() - 44).toInt().coerceAtLeast(0)
                    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                    header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
                    stream.seek(0); stream.write(header.array())
                }
            } else recorder?.stop()
            require(duration >= 250 && output.length() > 44) { "Recording too short" }
            RecordingEntity(current.id, current.startedAt, duration, output.absolutePath, output.extension)
        } catch (e: Exception) { output.delete(); throw e } finally { release(); session = null; file = null }
    } }
    override suspend fun cancelRecording() = mutex.withLock { withContext(Dispatchers.IO) {
        writer?.cancel(); runCatching { pcm?.stop() }; writer?.join(); runCatching { recorder?.stop() }
        release(); file?.delete(); file = null; session = null
    } }
    private fun release() { recorder?.release(); recorder = null; pcm?.release(); pcm = null; writer = null }
}
