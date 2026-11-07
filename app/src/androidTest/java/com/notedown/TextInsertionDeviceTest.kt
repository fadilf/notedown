package com.notedown

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notedown.accessibility.TextInsertionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextInsertionDeviceTest {
    @Test fun insertsAtLatestSelectionAndUsesNewlyFocusedFieldWithoutReplacingOtherText() {
        withEditor { automation ->
            val insertion = TextInsertionManager()
            val primary = field(automation, "editor-primary")
            assertTrue(primary.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection(5, 9)))
            await { focused(automation)?.let { it.textSelectionStart == 5 && it.textSelectionEnd == 9 } == true }
            val destination = focused(automation)
            assertTrue("Insertion refused: ${destination?.let { "focused=${it.isFocused} enabled=${it.isEnabled} visible=${it.isVisibleToUser} editable=${it.isEditable} password=${it.isPassword} selection=${it.textSelectionStart}..${it.textSelectionEnd} actions=${it.actionList}" }}",
                insertion.insertIntoFocused(destination, "NEW"))
            await { field(automation, "editor-primary").text.toString() == "Keep NEW text: " }
            assertEquals(8, field(automation, "editor-primary").textSelectionStart)

            // The user moves to a different field while the API is processing.
            val secondary = field(automation, "editor-secondary")
            assertTrue(secondary.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            assertTrue(secondary.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection(6, 6)))
            await { focused(automation)?.contentDescription?.toString() == "editor-secondary" }
            assertTrue(insertion.insertIntoFocused(focused(automation), "dictated "))
            await { field(automation, "editor-secondary").text.toString() == "Other dictated text: " }
            assertEquals("Keep NEW text: ", field(automation, "editor-primary").text.toString())
            assertEquals(15, field(automation, "editor-secondary").textSelectionStart)

            assertTrue(field(automation, "editor-secondary").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }))
            await { field(automation, "editor-secondary").isShowingHintText }
            assertTrue(insertion.insertIntoFocused(focused(automation), "Hello."))
            await { field(automation, "editor-secondary").text.toString() == "Hello." }
            assertEquals(6, field(automation, "editor-secondary").textSelectionStart)
        }
    }
    @Test fun rejectsPasswordsUnfocusedFieldsAndMissingTargets() {
        withEditor { automation ->
            val insertion = TextInsertionManager()
            assertFalse(insertion.insertIntoFocused(null, "dictated"))
            assertFalse(insertion.insertIntoFocused(field(automation, "editor-secondary"), "dictated"))
            val password = field(automation, "editor-password")
            assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            await { focused(automation)?.isPassword == true }
            assertFalse(insertion.insertIntoFocused(focused(automation), "dictated"))
            assertEquals("Other text: ", field(automation, "editor-secondary").text.toString())
        }
    }
    private fun withEditor(test: (UiAutomation) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            val component = "${instrumentation.context.packageName}/com.notedown.fixture.EditorActivity"
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n $component -f 0x10008000")).use { it.readBytes() }
            await { focused(automation)?.contentDescription?.toString() == "editor-primary" }
            automation.waitForIdle(500, 5000)
            test(automation)
        } finally { scenario.close() }
    }
    private fun selection(start: Int, end: Int) = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    }
    private fun focused(automation: UiAutomation): AccessibilityNodeInfo? = automation.windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
        ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    private fun field(automation: UiAutomation, description: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return automation.windows.firstNotNullOfOrNull { find(it.root) } ?: error("Editor not found: $description")
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail("Editor did not reach the expected focus, text or selection")
    }
}
