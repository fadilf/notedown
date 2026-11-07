package com.notedown.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notedown.api.RouterModel
import com.notedown.data.*
import java.math.BigDecimal

@Composable fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val models by vm.modelRepository.models.collectAsStateWithLifecycle()
    val keyPresent by vm.hasKey.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var deleteAll by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
            SettingSwitch("WAV compatibility mode", "Use uncompressed WAV for providers that reject M4A. Also converts saved M4A when retranscribing.", settings.wav) { vm.flag("wav", it) }
            TextButton(onClick = vm::refresh, enabled = !busy) { Text(if (busy) "Contacting OpenRouter…" else "Refresh available models") }
        } }
        item { SettingsSection("AI cleanup") {
            SettingSwitch("Clean up dictated text", "Punctuation and readability, using a separate model.", settings.cleanup) { vm.flag("cleanup", it) }
            if (settings.cleanup) {
                ModelPicker("Cleanup model", settings.cleanupModel, models["text"].orEmpty()) { vm.text("cleanupModel", it) }
                var prompt by remember(settings.prompt) { mutableStateOf(settings.prompt) }
                OutlinedTextField(prompt, { prompt = it.take(3000); vm.text("prompt", prompt) }, label = { Text("Cleanup system prompt") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.text("prompt", DEFAULT_PROMPT) }) { Text("Restore default prompt") }
            }
        } }
        item { SettingsSection("Floating microphone") {
            SettingSwitch("Show near the keyboard", "Requires Notedown's accessibility service.", settings.overlay) { vm.flag("overlay", it) }
            SettingSwitch("Position on the left", "Places controls above the keyboard's left edge.", settings.left) { vm.flag("left", it) }
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
}
@Composable fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary); content(); HorizontalDivider(Modifier.padding(top = 8.dp)) }
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
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = { Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search models") }, singleLine = true)
        if (models.isEmpty()) Text("Refresh models in Settings. An internet connection is required.")
        LazyColumn(Modifier.heightIn(max = 380.dp)) { items(models.filter { it.name.contains(query, true) || it.id.contains(query, true) }.size) { index ->
            val model = models.filter { it.name.contains(query, true) || it.id.contains(query, true) }[index]
            TextButton(onClick = { change(model.id); open = false }) { Column(Modifier.fillMaxWidth()) {
                Text(model.name, style = MaterialTheme.typography.titleSmall)
                Text(model.id, style = MaterialTheme.typography.bodySmall)
                model.pricing?.let { Text("USD pricing: $it", style = MaterialTheme.typography.labelSmall) }
                model.architecture?.get("output_modalities")?.let { Text("Output: $it", style = MaterialTheme.typography.labelSmall) }
            } }
        } }
    } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
