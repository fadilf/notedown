package com.notedown

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import com.notedown.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
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
    @Test fun draggedMicrophoneRemembersPositionAndReturnsToOriginalEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = SettingsRepository(context)
        val originalPosition = runBlocking { repository.settings.first() }
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
            shell("am start -W -n $editorComponent -f 0x10008000")
            await("Notedown microphone over external editor") { overlayButton("Start voice dictation") != null }
            // Real pointer movement must move/dock the window without starting capture.
            SystemClock.sleep(1000) // Let the IME finish animating before injecting a pointer gesture.
            val initial = Rect().also { checkNotNull(overlayButton("Start voice dictation")).getBoundsInScreen(it) }
            val screenWidth = context.resources.displayMetrics.widthPixels
            val targetX = if (initial.centerX() > screenWidth / 2) 50f else screenWidth - 50f
            val targetY = (initial.centerY() - 160).coerceAtLeast(120).toFloat()
            val downTime = SystemClock.uptimeMillis()
            fun pointer(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            pointer(MotionEvent.ACTION_DOWN, initial.exactCenterX(), initial.exactCenterY())
            for (step in 1..12) {
                SystemClock.sleep(20)
                val fraction = step / 12f
                pointer(MotionEvent.ACTION_MOVE, initial.centerX() + (targetX - initial.centerX()) * fraction,
                    initial.centerY() + (targetY - initial.centerY()) * fraction)
            }
            pointer(MotionEvent.ACTION_UP, targetX, targetY)
            await("dragged microphone remains idle on opposite edge from $initial (screen width $screenWidth)") {
                overlayButton("Start voice dictation")?.let {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    (bounds.centerX() < screenWidth / 2) != (initial.centerX() < screenWidth / 2)
                } == true
            }
            val savedPosition = runBlocking {
                withTimeout(10000) {
                    repository.settings.first { it.overlayVerticalFraction != null && it.left == (targetX < screenWidth / 2) }
                }
            }
            assertNotNull(savedPosition.overlayVerticalFraction)
            assertNull("Dragging must not start recording", overlayButton("Stop"))
            SystemClock.sleep(500) // Wait for the edge snap to reach WindowManager/accessibility.
            val movedBounds = Rect().also { checkNotNull(overlayButton("Start voice dictation")).getBoundsInScreen(it) }
            // Hide and recreate the overlay by closing/reopening the IME.
            shell("input keyevent KEYCODE_BACK")
            await("microphone hidden with keyboard") { overlayButton("Start voice dictation") == null }
            automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            var restoredBounds: Rect? = null
            try {
                await("microphone restored at remembered position") {
                    overlayButton("Start voice dictation")?.let {
                        val bounds = Rect().also(it::getBoundsInScreen)
                        restoredBounds = bounds
                        kotlin.math.abs(bounds.centerX() - movedBounds.centerX()) <= 2 &&
                            kotlin.math.abs(bounds.centerY() - movedBounds.centerY()) <= 2
                    } == true
                }
            } catch (e: AssertionError) {
                throw AssertionError("Expected $movedBounds, restored $restoredBounds; saved left=${savedPosition.left}, fraction=${savedPosition.overlayVerticalFraction}", e)
            }
            val button = checkNotNull(overlayButton("Start voice dictation"))
            assertTrue("Microphone click should be accepted", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("recording controls after one microphone tap") { overlayButton("Stop") != null }
            await("original editor restored instead of accessibility settings") {
                automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.root?.packageName?.toString() == instrumentation.context.packageName }
            }
            assertTrue("Recording should still be running", overlayButton("Stop") != null)
            val controls = Rect().also { bounds ->
                automation.windows.first { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.getBoundsInScreen(bounds)
            }
            assertTrue("Expanded controls should stay on-screen: $controls", controls.left >= 0 && controls.top >= 0 &&
                controls.right <= screenWidth && controls.bottom <= context.resources.displayMetrics.heightPixels)
        } finally {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction("cancel")) }
            if (originalServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled ${if (originallyEnabled == "1") "1" else "0"}")
            runBlocking { repository.overlayPosition(originalPosition.left, originalPosition.overlayVerticalFraction) }
            scenario.close()
        }
    }
}
