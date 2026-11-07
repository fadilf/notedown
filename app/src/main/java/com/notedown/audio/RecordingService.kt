package com.notedown.audio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.notedown.R
import com.notedown.MainActivity
import com.notedown.domain.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint class RecordingService : Service() {
    @Inject lateinit var sessions: RecordingSessionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var limit: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> scope.launch { limit?.cancel(); sessions.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            "cancel" -> scope.launch { limit?.cancel(); sessions.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            else -> {
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel("recording", "Microphone recording", NotificationManager.IMPORTANCE_LOW))
                    fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
                    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    val notification = Notification.Builder(this, "recording").setSmallIcon(R.drawable.ic_mic).setContentTitle("Notedown is recording")
                        .setContentText("Stop to save and transcribe, or cancel to discard.").setContentIntent(open).setOngoing(true)
                        .addAction(Notification.Action.Builder(null, "Stop", action("stop")).build())
                        .addAction(Notification.Action.Builder(null, "Cancel", action("cancel")).build()).build()
                    if (Build.VERSION.SDK_INT >= 30) startForeground(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(10, notification)
                    scope.launch {
                        sessions.start()
                        if (sessions.state.value !is DictationState.Recording) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                        else { limit?.cancel(); limit = scope.launch { delay(600000); sessions.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
                    }
                } catch (_: RuntimeException) { sessions.startFailed(); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        limit?.cancel()
        // Recorder cleanup survives this service scope; saved requests belong to WorkManager.
        if (sessions.state.value is DictationState.Recording) CoroutineScope(Dispatchers.Main.immediate).launch { sessions.cancel() }
        scope.cancel(); super.onDestroy()
    }
}
