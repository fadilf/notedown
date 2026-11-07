package com.notedown

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.accessibility.FocusedEditorResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class FocusedEditorResolverTest {
    @Test fun findsVirtualEditorWhenProviderReturnsNonEditableContainer() {
        withProvider("normal") { automation, root ->
            automation.clearCache()
            assertTrue("Direct focus lookup must not resolve the editor",
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable != true)
            val resolver = FocusedEditorResolver("com.notedown")
            automation.clearCache()
            assertEquals("provider-editor", resolver.resolveRoot(root)?.contentDescription?.toString())
            assertNull("Own application must be excluded",
                FocusedEditorResolver(root.packageName.toString()).resolve(automation.windows))
        }
    }

    @Test fun traversalRejectsPasswordDisabledAndAccessibilityOnlyFocus() {
        for (mode in listOf("password", "disabled", "unfocused")) {
            withProvider(mode) { automation, root ->
                automation.clearCache()
                assertNull("Must reject $mode editor", FocusedEditorResolver("com.notedown").resolveRoot(root))
            }
        }
    }

    private fun withProvider(mode: String, test: (UiAutomation, AccessibilityNodeInfo) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val component = "${instrumentation.context.packageName}/com.notedown.fixture.EditorActivity"
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("am start -W -n $component --es provider-mode $mode -f 0x10008000")
        try {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                val root = automation.windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
                }?.root
                fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.contentDescription?.toString() == "provider-root") return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
                    return null
                }
                val provider = root?.let(::find)
                if (provider != null) {
                    test(automation, provider)
                    return
                }
                SystemClock.sleep(50)
            }
            fail("Virtual editor fixture did not appear")
        } finally {
            shell("input keyevent KEYCODE_BACK")
        }
    }
}
