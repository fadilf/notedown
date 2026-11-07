package com.notedown.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notedown.R
import com.notedown.audio.AudioPlaybackManager
import com.notedown.data.*
import com.notedown.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NotedownApp(vm: AppViewModel) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val state by vm.sessions.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val player = remember { AudioPlaybackManager(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    Scaffold(topBar = { TopAppBar(title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_notedown), contentDescription = null, modifier = Modifier.size(36.dp))
            Text("notedown", style = MaterialTheme.typography.headlineMedium)
        }
    },
        actions = { if (destination == 0) IconButton(onClick = { destination = 1 }) { Icon(Icons.Outlined.Settings, "Settings") } }) },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { NavigationBar {
            NavigationBarItem(selected = destination == 0, onClick = { destination = 0 }, icon = { Icon(Icons.Outlined.History, null) }, label = { Text("History") })
            NavigationBarItem(selected = destination == 1, onClick = { destination = 1 }, icon = { Icon(Icons.Outlined.Tune, null) }, label = { Text("Settings") })
        } },
        floatingActionButton = { if (destination == 0 && hasKey && settings.model.isNotBlank()) ExtendedFloatingActionButton(
            onClick = { context.startActivity(Intent(context, CaptureActivity::class.java)) }, icon = { Icon(Icons.Outlined.Mic, null) }, text = { Text("Dictate") }) }
    ) { padding ->
        if (destination == 1) SettingsScreen(vm, Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SetupCard(hasKey, settings.model.isNotBlank(), { destination = 1 }) }
            if (state !is DictationState.Idle) item { Card { Column(Modifier.padding(16.dp)) {
                Text(when (val s = state) {
                    is DictationState.Recording -> "Recording… Use the floating controls or notification to stop."
                    is DictationState.Processing -> "Waiting for network or transcribing. You can leave the app."
                    is DictationState.Result -> s.message
                    is DictationState.Error -> s.message
                    else -> "Preparing your recording…"
                })
                if (state is DictationState.Result || state is DictationState.Error) TextButton(onClick = { vm.sessions.dismiss() }) { Text("Dismiss") }
            } } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RECORDINGS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("${history.size} saved", style = MaterialTheme.typography.labelLarge)
            } }
            if (history.isEmpty()) item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.GraphicEq, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Make room for a thought", style = MaterialTheme.typography.titleLarge)
                Text("Tap Dictate, or open a keyboard in another app and tap the floating microphone. Your recordings and every transcript version will appear here.")
            } } }
            items(history, key = { it.recording.id }) { item -> RecordingCard(item, settings.model, vm.modelRepository.models.collectAsStateWithLifecycle().value["transcription"].orEmpty(),
                player, { model -> vm.transcribe(item.recording.id, model) }, { if (player.current == item.recording.filePath) player.stop(); vm.delete(item.recording.id) }) }
        }
    }
}

@Composable fun SetupCard(hasKey: Boolean, hasModel: Boolean, configure: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val hasMic = remember(refresh) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val accessibility = remember(refresh) {
        val component = android.content.ComponentName(context, com.notedown.accessibility.NotedownAccessibilityService::class.java).flattenToString()
        AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any { it.equals(component, true) } == true
    }
    if (hasKey && hasModel && hasMic && accessibility) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up dictation", style = MaterialTheme.typography.titleMedium)
            if (!hasKey || !hasModel) TextButton(onClick = configure) { Text("1. Add API key & choose a model") }
            if (!hasMic) { Text("Microphone access lets you record only when you tap Dictate."); TextButton(onClick = { mic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("2. Allow microphone") } }
            if (!accessibility) { Text("Accessibility detects the keyboard and inserts text into your original field. Password fields are excluded."); TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("3. Enable Notedown accessibility") } }
            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                TextButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow recording notifications (optional)") }
            Text("Audio is sent directly to OpenRouter and its model provider when you stop. Check their data policies before dictating sensitive information.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
