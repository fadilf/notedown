package com.notedown.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.notedown.audio.RecordingService
import com.notedown.R
import com.notedown.domain.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint class CaptureActivity : ComponentActivity() {
    @Inject lateinit var sessions: RecordingSessionManager
    private var requested = false
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() else closeCapture() }
    override fun onCreate(savedInstanceState: Bundle?) {
        val overlayCapture = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        if (overlayCapture) setTheme(R.style.Theme_Notedown_Capture_Overlay)
        super.onCreate(savedInstanceState)
        if (overlayCapture) {
            // Stay visible/resumed for microphone startup without taking the editor's
            // input focus (which would hide its IME until this activity finishes).
            // Set this before the decor is attached, not after a focus change.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        }
        if (!overlayCapture) window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setContent { NotedownTheme {
            val state by sessions.state.collectAsState()
            if (overlayCapture) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = colorResource(R.color.brand_charcoal),
                    contentColor = colorResource(R.color.brand_cream)) {
                    if (state is DictationState.Error) {
                        Column(Modifier.padding(16.dp)) {
                            Text((state as DictationState.Error).message)
                            TextButton(onClick = { closeCapture() }) { Text("Close") }
                        }
                    } else {
                        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = colorResource(R.color.brand_cream), strokeWidth = 2.dp)
                            Text("Starting…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Notedown", style = MaterialTheme.typography.titleLarge)
                Text(when(state) { is DictationState.Recording -> "Recording your voice…"; is DictationState.Error -> (state as DictationState.Error).message; else -> "Starting microphone…" })
                Row { TextButton(onClick = { command("cancel"); closeCapture() }) { Text("Cancel") }
                    if (state is DictationState.Recording) Button(onClick = { command("stop"); closeCapture() }) { Text("Stop & transcribe") }
                }
            } }
        } }
        if (overlayCapture) positionStartupChip()
        lifecycleScope.launch { sessions.state.collect { state ->
            if (state is DictationState.Recording && intent.getBooleanExtra(EXTRA_OVERLAY, false)) closeCapture()
        } }
    }
    private fun positionStartupChip() {
        val density = resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val width = (184 * density).toInt().coerceAtMost((screenWidth - 2 * margin).coerceAtLeast(1))
        val anchorLeft = intent.getIntExtra(EXTRA_ANCHOR_LEFT, margin)
        val anchorRight = intent.getIntExtra(EXTRA_ANCHOR_RIGHT, screenWidth - margin)
        val x = if ((anchorLeft + anchorRight) / 2 < screenWidth / 2) anchorLeft else anchorRight - width
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP or Gravity.LEFT)
        window.attributes = window.attributes.apply {
            // Anchor coordinates are absolute screen coordinates, like the overlay's.
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            this.x = x.coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
            y = intent.getIntExtra(EXTRA_ANCHOR_TOP, margin).coerceAtLeast(margin)
        }
    }
    override fun onResume() {
        super.onResume()
        if (!requested) {
            requested = true
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    private fun closeCapture() {
        if (intent.getBooleanExtra(EXTRA_OVERLAY, false) && isTaskRoot) finishAndRemoveTask()
        else finish()
    }
    private fun begin() {
        // Called only while this visible, user-launched activity is resumed.
        try { startForegroundService(Intent(this, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_INSERT_INTO_FOCUSED_FIELD, intent.getBooleanExtra(EXTRA_OVERLAY, false))) }
        catch (_: RuntimeException) { android.widget.Toast.makeText(this, "Microphone could not start. Reopen Notedown and check permissions.", android.widget.Toast.LENGTH_LONG).show(); closeCapture() }
    }
    companion object {
        private const val EXTRA_OVERLAY = "overlay"
        private const val EXTRA_ANCHOR_LEFT = "anchor_left"
        private const val EXTRA_ANCHOR_RIGHT = "anchor_right"
        private const val EXTRA_ANCHOR_TOP = "anchor_top"

        fun overlayIntent(context: Context, anchor: Rect): Intent = Intent(context, CaptureActivity::class.java)
            .putExtra(EXTRA_OVERLAY, true)
            .putExtra(EXTRA_ANCHOR_LEFT, anchor.left)
            .putExtra(EXTRA_ANCHOR_RIGHT, anchor.right)
            .putExtra(EXTRA_ANCHOR_TOP, anchor.top)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
    private fun command(action: String) { startService(Intent(this, RecordingService::class.java).setAction(action)) }
}
