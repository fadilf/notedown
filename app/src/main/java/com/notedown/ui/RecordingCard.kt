package com.notedown.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notedown.api.RouterModel
import com.notedown.data.*
import java.text.DateFormat
import java.util.Date

fun copyTranscript(context: android.content.Context, text: String) {
    val clip = ClipData.newPlainText("Notedown transcript", text)
    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
@Composable fun RecordingCard(item: RecordingWithTranscripts, defaultModel: String, models: List<RouterModel>, play: () -> Unit, retranscribe: (String) -> Unit, delete: () -> Unit) {
    var selected by rememberSaveable(item.recording.id) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    var selectedModel by remember(defaultModel) { mutableStateOf(defaultModel) }
    var showRaw by remember { mutableStateOf(false) }
    val sorted = item.transcripts.sortedByDescending { it.createdAt }
    val transcript = sorted.firstOrNull { it.id == selected } ?: sorted.firstOrNull()
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.recording.createdAt)), style = MaterialTheme.typography.labelLarge)
                Text("${item.recording.durationMs / 1000}s · ${item.recording.audioFormat.uppercase()}", style = MaterialTheme.typography.labelMedium)
            }
            Row { FilledTonalIconButton(onClick = play) { Icon(Icons.Outlined.PlayArrow, "Play or pause recording") }
                Column(Modifier.padding(start = 12.dp, top = 4.dp)) {
                    Text(transcript?.modelId ?: "Ready to transcribe", style = MaterialTheme.typography.labelLarge)
                    Text(transcript?.status ?: "Saved locally", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (sorted.size > 1) {
                var menu by remember { mutableStateOf(false) }
                Box { TextButton(onClick = { menu = true }) { Text("Version ${sorted.indexOf(transcript) + 1} of ${sorted.size} ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { sorted.forEachIndexed { index, version ->
                        DropdownMenuItem(text = { Text("${index + 1} · ${version.modelId} · ${version.status}") }, onClick = { selected = version.id; menu = false; showRaw = false })
                    } }
                }
            }
            val text = if (showRaw) transcript?.rawText.orEmpty() else transcript?.text.orEmpty()
            if (text.isNotBlank()) {
                SelectionContainer { Text(text, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) }
                if (text.length > 200 || text.count { it == '\n' } > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read more") }
                if (transcript?.cleanedText != null) TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Show cleaned text" else "Show original transcript") }
            } else Text(if (transcript?.status in listOf("queued", "processing")) "Queued or transcribing…" else "Audio saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.recording.status == "recovered") Text("Recovered after an interruption. Unfinished audio may not be playable.", style = MaterialTheme.typography.bodySmall)
            transcript?.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { copyTranscript(context, text) }, enabled = text.isNotBlank()) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
                TextButton(onClick = { choosingModel = true }, enabled = transcript?.status !in listOf("queued", "processing")) { Text("Retranscribe") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete recording and versions") }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete recording?") }, text = { Text("This removes the audio and all its transcript versions. Usage totals are retained.") }, confirmButton = { TextButton(onClick = { deleting = false; delete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    if (choosingModel) AlertDialog(onDismissRequest = { choosingModel = false }, title = { Text("Create a new version") }, text = { Column {
        Text("The audio will be sent to the selected provider. Existing transcripts are kept.")
        ModelPicker("Transcription model", selectedModel, models) { selectedModel = it }
    } }, confirmButton = { TextButton(onClick = { choosingModel = false; retranscribe(selectedModel) }, enabled = selectedModel.isNotBlank()) { Text("Transcribe") } }, dismissButton = { TextButton(onClick = { choosingModel = false }) { Text("Cancel") } })
}
