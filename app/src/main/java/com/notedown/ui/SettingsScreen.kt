package com.notedown.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notedown.api.RouterModel
import com.notedown.data.*
import java.math.BigDecimal
import java.util.UUID

@Composable fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.modelRepository.models.collectAsStateWithLifecycle()
    val keyPresent by vm.hasKey.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var deleteAll by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<CleanupProfile?>(null) }
    val listState = rememberLazyListState()
    var dragY by remember { mutableStateOf<Float?>(null) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(dragY != null) {
        while (dragY != null) {
            withFrameNanos { }
            val y = dragY ?: break
            val distance = when {
                y < viewport.top + edge -> y - viewport.top - edge
                y > viewport.bottom - edge -> y - viewport.bottom + edge
                else -> 0f
            }
            if (distance != 0f) listState.scrollBy((distance / edge).coerceIn(-1f, 1f) * edge / 6f)
        }
    }
    LazyColumn(modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() }, state = listState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Make it yours.", style = MaterialTheme.typography.headlineSmall) }
        item { SettingsSection("OpenRouter") {
            Text(if (keyPresent) "API key saved with Android Keystore encryption." else "Bring your own key. No account with Notedown is needed.")
            OutlinedTextField(key, { key = it }, label = { Text(if (keyPresent) "Replace API key" else "API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row { Button(onClick = { vm.key(key); key = "" }, enabled = !busy) { Text(if (key.isBlank() && keyPresent) "Validate saved key" else "Validate & save") }
                if (keyPresent) TextButton(onClick = vm::removeKey) { Text("Remove") } }
        } }
        item { SettingsSection("Transcription") {
            Text("Model availability, format support and pricing depend on the provider.", style = MaterialTheme.typography.bodySmall)
            ModelPicker("Default model", settings.model, models["transcription"].orEmpty()) { vm.text("model", it) }
            var language by remember(settings.language) { mutableStateOf(settings.language) }
            OutlinedTextField(language, { language = it.take(2).lowercase(); vm.text("language", language) }, label = { Text("Language (ISO code, blank = auto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SettingSwitch("WAV compatibility mode", "Use WAV for recordings and untrimmed uploads. Turn off Skip silence for WAV-only providers.", settings.wav) { vm.flag("wav", it) }
            SettingSwitch("Skip silence before transcription", "Faster on slow connections and slightly cheaper. Trims pauses and compresses uploads; keeps original recordings.", settings.skipSilence) { vm.flag("skipSilence", it) }
            TextButton(onClick = vm::refresh, enabled = !busy) { Text(if (busy) "Contacting OpenRouter…" else "Refresh available models") }
        } }
        item { SettingsSection("Cleanup profiles") {
            Text("Tap a profile below the floating recording controls to finish and clean up. The check mark transcribes without cleanup. Other saved profiles are available under More.")
            Text("Drag a profile's handle up or down to reorder it.", style = MaterialTheme.typography.bodySmall)
            CleanupProfileList(settings.cleanupProfiles, vm::moveProfile, { editingProfile = it }, vm::profileShortcut) { dragY = it }
            OutlinedButton(onClick = { editingProfile = CleanupProfile(UUID.randomUUID().toString(), "") }) { Text("Add profile") }
        } }
        item { SettingsSection("Floating Button") {
            SettingSwitch("Enable floating button above keyboard", "Requires Notedown's accessibility service.", settings.overlay) { vm.flag("overlay", it) }
            Text("Drag the floating microphone to move it. It snaps to the nearest side and remembers its position.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::resetOverlayPosition) { Text("Reset position above keyboard") }
            Text("Finished dictation is inserted at the cursor in whichever text field is focused when processing completes. If no supported field is focused, use Copy or history.", style = MaterialTheme.typography.bodySmall)
            Text("Android may briefly display the recording panel while enabling microphone access. Capture starts automatically, without another tap.", style = MaterialTheme.typography.bodySmall)
            Text("Recordings stop automatically after 10 minutes. WAV uploads are limited to 25 MB. A failed request keeps the recording for retry.", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsSection("Usage on this device") {
            val actual = usage.mapNotNull { it.costUsd?.toBigDecimalOrNull() }.fold(BigDecimal.ZERO, BigDecimal::add)
            Text("${usage.size} successful API requests", style = MaterialTheme.typography.titleMedium)
            Text("Reported cost: $${actual.toPlainString()} USD")
            Text("${usage.count { it.costUsd == null }} requests have no reported cost. Estimates are not added. Totals include deleted recordings and exclude usage from other devices.", style = MaterialTheme.typography.bodySmall)
        } }
        item { SettingsSection("Local history") {
            Text("Recordings and transcripts are stored in app-private storage. Uninstalling Notedown removes them. Audio leaves this device only for transcription; cleanup sends the raw text.")
            TextButton(onClick = { deleteAll = true }) { Text("Delete all recordings & transcripts", color = MaterialTheme.colorScheme.error) }
        } }
    }
    if (deleteAll) AlertDialog(onDismissRequest = { deleteAll = false }, title = { Text("Delete all history?") }, text = { Text("All recordings and transcript versions will be permanently removed. The usage ledger is retained.") }, confirmButton = { TextButton(onClick = { deleteAll = false; vm.deleteAll() }) { Text("Delete all") } }, dismissButton = { TextButton(onClick = { deleteAll = false }) { Text("Cancel") } })
    editingProfile?.let { profile ->
        CleanupProfileDialog(profile, models["text"].orEmpty(), settings.cleanupProfiles.any { it.id == profile.id },
            { editingProfile = null }, { vm.saveProfile(it); editingProfile = null },
            { vm.deleteProfile(profile.id); editingProfile = null })
    }
}

@Composable private fun CleanupProfileDialog(profile: CleanupProfile, models: List<RouterModel>, existing: Boolean,
    dismiss: () -> Unit, save: (CleanupProfile) -> Unit, delete: () -> Unit) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing) "Edit profile" else "Add profile") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text("Name") }, singleLine = true)
            ModelPicker("Cleanup model", draft.model, models) { draft = draft.copy(model = it) }
            OutlinedTextField(draft.prompt, { draft = draft.copy(prompt = it.take(3000)) }, label = { Text("Cleanup instructions") }, minLines = 4)
            SettingSwitch("Show shortcut", "Below the floating recording controls", draft.shortcut) { draft = draft.copy(shortcut = it) }
            if (existing) TextButton(onClick = { deleting = true }) { Text("Delete profile", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = { save(draft.copy(name = draft.name.trim(), prompt = draft.prompt.trim())) },
            enabled = draft.name.isNotBlank() && draft.prompt.isNotBlank() && draft.model.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete profile?") },
        text = { Text("Queued transcriptions keep their saved settings.") },
        confirmButton = { TextButton(onClick = delete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
@Composable fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    PaperCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
@Composable fun SettingSwitch(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, change)
    }
}
@Composable fun ModelPicker(label: String, selected: String, models: List<RouterModel>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(models.firstOrNull { it.id == selected }?.name ?: selected.ifBlank { "Choose a model" }) }
    }
    if (selected.isNotBlank() && models.isNotEmpty() && models.none { it.id == selected }) Text("Selected model is unavailable in the current catalog. Choose another before dictating.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (open) ModelPickerDialog(label, selected, models, query, { query = it }, { open = false }) {
        change(it)
        open = false
    }
}
