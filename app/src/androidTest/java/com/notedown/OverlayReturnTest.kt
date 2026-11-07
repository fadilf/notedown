package com.notedown

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notedown.audio.RecordingService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayReturnTest {
    @Test fun microphoneReturnsToOriginalEditorWhenAccessibilitySettingsIsInNotedownTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val originalServices = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty()
        val originallyEnabled = shell("settings get secure accessibility_enabled")
        val service = ComponentName(context, com.notedown.accessibility.NotedownAccessibilityService::class.java).flattenToString()
        val services = (originalServices.split(':').filter { it.isNotBlank() } + service).distinct().joinToString(":")
        fun await(description: String, timeout: Long = 10000, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            fail("Timed out: $description; windows=" + automation.windows.joinToString { "type=${it.type} active=${it.isActive} focused=${it.isFocused} pkg=${it.root?.packageName} input=${it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable}" })
        }
        fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) return node
            for (i in 0 until node.childCount) find(node.getChild(i), description)?.let { return it }
            return null
        }
        fun overlayButton(description: String): AccessibilityNodeInfo? = automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .firstNotNullOfOrNull { find(it.root, description) }
        try {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            // The same path as onboarding: system Settings is above MainActivity in its task.
            scenario.onActivity { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            await("accessibility settings") { automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            val editorComponent = "${instrumentation.context.packageName}/com.notedown.fixture.EditorActivity"
            shell("am start -W -n $editorComponent -f 0x10000000")
            await("Notedown microphone over external editor") { overlayButton("Start voice dictation") != null }
            val button = checkNotNull(overlayButton("Start voice dictation"))
            val bounds = Rect().also(button::getBoundsInScreen)
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).also { automation.injectInputEvent(it, true); it.recycle() }
            MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).also { automation.injectInputEvent(it, true); it.recycle() }
            await("recording controls after one microphone tap") { overlayButton("Stop") != null }
            await("original editor restored instead of accessibility settings") {
                automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.root?.packageName?.toString() == instrumentation.context.packageName }
            }
            assertTrue("Recording should still be running", overlayButton("Stop") != null)
        } finally {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction("cancel")) }
            if (originalServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled ${if (originallyEnabled == "1") "1" else "0"}")
            scenario.close()
        }
    }
}
