package com.notedown.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.notedown.data.*
import com.notedown.domain.*
import com.notedown.overlay.OverlayManager
import com.notedown.audio.RecordingService
import com.notedown.ui.CaptureActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint class NotedownAccessibilityService : AccessibilityService() {
    @Inject lateinit var sessions: RecordingSessionManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var queue: TranscriptionRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyboard = KeyboardVisibilityTracker()
    private val insertion = TextInsertionManager()
    private lateinit var overlay: OverlayManager
    private var config = Settings()
    private var insertedResult: DictationState.Result? = null
    override fun onServiceConnected() {
        overlay = OverlayManager(this, ::begin, { command("stop") }, { command("cancel") }, { id ->
            scope.launch { runCatching { queue.enqueue(id) }; sessions.dismiss() }
        }, { sessions.dismiss(); insertion.clear(); render() })
        scope.launch { settings.settings.collectLatest { config = it; render() } }
        scope.launch { sessions.state.collectLatest { state ->
            if (state is DictationState.Result && insertedResult !== state) {
                insertedResult = state
                val success = insertion.insert(sessions.targetToken, state.text, focusedEditor()?.packageName?.toString())
                insertion.clear()
                if (success) { sessions.dismiss() }
            }
            render()
        } }
        scope.launch { while (isActive) { delay(700); render() } }
    }
    private fun focusedEditor(): AccessibilityNodeInfo? {
        // Touching our overlay can make it the active window. Input focus still belongs
        // to the application window; never resolve the target from the touched overlay.
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.isEditable && !it.isPassword && it.packageName?.toString() != packageName }
    }
    private fun begin() {
        val node = focusedEditor()
        sessions.targetToken = insertion.capture(node)
        if (sessions.targetToken == null) return
        // Do not bring Notedown's task (possibly still showing permission Settings)
        // in front of the editor. This short-lived task is removed after mic startup.
        startActivity(CaptureActivity.overlayIntent(this))
    }
    private fun command(action: String) { startService(Intent(this, RecordingService::class.java).setAction(action)) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) insertion.invalidateIfDifferent(event.source)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (sessions.targetToken != null && pkg != packageName && pkg != null &&
                windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.packageName?.toString() == pkg }) {
                insertion.invalidateIfDifferent(focusedEditor())
            }
        }
        render()
    }
    private fun render() {
        if (!::overlay.isInitialized) return
        val state = sessions.state.value
        val ime = keyboard.detect(windows)
        val node = focusedEditor()
        val canShowIdle = config.overlay && ime.visible && node?.isEditable == true && !node.isPassword && node.packageName.toString() != packageName
        if (state == DictationState.Idle && !canShowIdle) { overlay.hide(); return }
        if (!config.overlay && state == DictationState.Idle) { overlay.hide(); return }
        val elapsed = if (state is DictationState.Recording) (System.currentTimeMillis() - state.session.startedAt) / 1000 else 0
        overlay.show(state, ime.top, config.left, elapsed)
    }
    override fun onInterrupt() { if (::overlay.isInitialized) overlay.hide(); insertion.clear() }
    override fun onDestroy() { scope.cancel(); if (::overlay.isInitialized) overlay.hide(); insertion.clear(); super.onDestroy() }
}
