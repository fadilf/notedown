package com.notedown.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.notedown.audio.RecordingService
import com.notedown.domain.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint class CaptureActivity : ComponentActivity() {
    @Inject lateinit var sessions: RecordingSessionManager
    private var requested = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() else finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setContent { NotedownTheme {
            val state by sessions.state.collectAsState()
            Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Notedown", style = MaterialTheme.typography.titleLarge)
                Text(when(state) { is DictationState.Recording -> "Recording your voice…"; is DictationState.Error -> (state as DictationState.Error).message; else -> "Starting microphone…" })
                Row { TextButton(onClick = { command("cancel"); finish() }) { Text("Cancel") }
                    if (state is DictationState.Recording) Button(onClick = { command("stop"); finish() }) { Text("Stop & transcribe") }
                }
            } }
        } }
        lifecycleScope.launch { sessions.state.collect { state ->
            if (state is DictationState.Recording && intent.getBooleanExtra("overlay", false)) finish()
        } }
    }
    override fun onResume() {
        super.onResume()
        if (!requested) {
            requested = true
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun begin() {
        // Called only while this visible, user-launched activity is resumed.
        try { startForegroundService(Intent(this, RecordingService::class.java)) }
        catch (_: RuntimeException) { android.widget.Toast.makeText(this, "Microphone could not start. Reopen Notedown and check permissions.", android.widget.Toast.LENGTH_LONG).show(); finish() }
    }
    private fun command(action: String) { startService(Intent(this, RecordingService::class.java).setAction(action)) }
}
