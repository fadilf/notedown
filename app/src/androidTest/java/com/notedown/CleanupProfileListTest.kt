package com.notedown

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.data.CleanupProfile
import com.notedown.ui.CleanupProfileList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupProfileListTest {
    @Test fun dragReordersBothDirectionsAndCancelRestoresOrderWithoutEditingOrToggling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val initial = listOf("Alpha", "Beta", "Gamma").map { CleanupProfile(it, it, model = "fixture/model") }
        val saved = AtomicReference(initial)
        val edited = AtomicReference<String?>(null)
        fun find(node: AccessibilityNodeInfo?, description: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == description) return node
            for (index in 0 until node.childCount) find(node.getChild(index), description)?.let { return it }
            return null
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            val positions = initial.associate { profile ->
                profile.id to Rect().also { find(automation.rootInActiveWindow, "Reorder ${profile.name}")?.getBoundsInScreen(it) }
            }
            fail("Profile list did not reach the expected state: ${saved.get().map { it.id }}; positions=$positions")
        }
        fun node(description: String): AccessibilityNodeInfo {
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            await { find(automation.rootInActiveWindow, description)?.isVisibleToUser == true }
            return checkNotNull(find(automation.rootInActiveWindow, description))
        }
        fun bounds(description: String) = Rect().also { node(description).getBoundsInScreen(it) }
        fun click(description: String) {
            var target: AccessibilityNodeInfo? = node(description)
            while (target != null && !target.isClickable) target = target.parent
            assertTrue("No clickable action for $description", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun gesture(from: String, to: String, cancel: Boolean = false) {
            val start = bounds("Reorder $from")
            val target = bounds("Reorder $to")
            val time = SystemClock.uptimeMillis()
            fun event(action: Int, y: Float) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, start.exactCenterX(), y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, start.exactCenterY())
            val destination = target.exactCenterY() + if (target.centerY() > start.centerY()) 30f else -30f
            for (step in 1..20) {
                event(MotionEvent.ACTION_MOVE, start.centerY() + (destination - start.centerY()) * step / 20f)
                SystemClock.sleep(25)
            }
            event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, destination)
            instrumentation.waitForIdleSync()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var profiles by remember { mutableStateOf(initial) }
                MaterialTheme {
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    CleanupProfileList(profiles, { id, offset ->
                        profiles = profiles.toMutableList().apply {
                            val from = indexOfFirst { it.id == id }
                            add(from + offset, removeAt(from))
                        }
                        saved.set(profiles)
                    }, { edited.set(it.id) }, { id, enabled ->
                        profiles = profiles.map { if (it.id == id) it.copy(shortcut = enabled) else it }
                        saved.set(profiles)
                    })
                    }
                }
            } }
            val pencil = bounds("Edit Alpha")
            val toggle = bounds("Show Alpha below recording controls")
            assertTrue("Pencil is immediately left of the enable switch", pencil.right <= toggle.left)
            gesture("Alpha", "Gamma")
            await { saved.get().map { it.id } == listOf("Beta", "Gamma", "Alpha") }
            await { bounds("Reorder Alpha").top > bounds("Reorder Gamma").top }
            gesture("Alpha", "Beta")
            await { saved.get().map { it.id } == listOf("Alpha", "Beta", "Gamma") }
            gesture("Alpha", "Gamma", cancel = true)
            assertEquals(initial, saved.get())
            await { bounds("Reorder Alpha").top < bounds("Reorder Beta").top }
            assertNull("Dragging must not open the editor", edited.get())
            assertTrue("Dragging must not toggle a profile", saved.get().all { it.shortcut })
            click("Edit Beta")
            await { edited.get() == "Beta" }
            click("Show Beta below recording controls")
            await { !saved.get().single { it.id == "Beta" }.shortcut }
        }
    }
}
