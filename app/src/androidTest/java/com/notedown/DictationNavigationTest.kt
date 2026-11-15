package com.notedown

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.ui.DictationNavigationBar
import com.notedown.ui.NotedownTheme
import com.notedown.ui.dictationContentPadding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DictationNavigationTest {
    @Test fun contentRemainsVisibleBesideRaisedMicrophoneUntilDivider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val barBounds = AtomicReference<Rect>()
        val contentBounds = AtomicReference<Rect>()
        val density = AtomicReference(1f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    density.set(LocalDensity.current.density)
                    NotedownTheme {
                        Scaffold(bottomBar = {
                            Box(Modifier.onGloballyPositioned {
                                val origin = it.localToScreen(Offset.Zero)
                                barBounds.set(Rect(origin.x.toInt(), origin.y.toInt(), origin.x.toInt() + it.size.width, origin.y.toInt() + it.size.height))
                            }) {
                                DictationNavigationBar(0, {}, {})
                            }
                        }) { padding ->
                            // An opaque fixture makes a premature viewport cutoff or an
                            // opaque bar background visible without using private history.
                            Box(Modifier.fillMaxSize().padding(dictationContentPadding(padding))) {
                                Box(Modifier.fillMaxSize().background(Color.White).onGloballyPositioned {
                                    val origin = it.localToScreen(Offset.Zero)
                                    contentBounds.set(Rect(origin.x.toInt(), origin.y.toInt(), origin.x.toInt() + it.size.width, origin.y.toInt() + it.size.height))
                                })
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            while ((barBounds.get() == null || contentBounds.get() == null) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertNotNull(barBounds.get())
            assertNotNull(contentBounds.get())
            val bar = barBounds.get()
            val dividerY = bar.top + (28 * density.get()).toInt()
            assertTrue("Viewport must reach the divider, not stop at the top of the microphone",
                kotlin.math.abs(contentBounds.get().bottom - dividerY) <= 1)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                val inset = (12 * density.get()).toInt()
                val above = dividerY - (8 * density.get()).toInt()
                val below = dividerY + (8 * density.get()).toInt()
                listOf(bar.left + inset, bar.right - inset).forEach { x ->
                    assertEquals("Cards must show through above the divider", Color.White.toArgb(), screenshot.getPixel(x, above))
                    assertEquals("Bar must stay opaque below the divider", Color(0xFFF6F2EA).toArgb(), screenshot.getPixel(x, below))
                }
            } finally {
                screenshot.recycle()
            }
        }
    }

    @Test fun centeredActionAndNavigationRemainIndependentOnNarrowScreenWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val destination = AtomicInteger(0)
        val captures = AtomicInteger(0)
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun await(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                find(automation.rootInActiveWindow, label)?.let { if (it.isVisibleToUser) return it }
                SystemClock.sleep(100)
            }
            throw AssertionError("Missing navigation action: $label")
        }
        fun click(label: String) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                var node = find(automation.rootInActiveWindow, label)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    instrumentation.waitForIdleSync()
                    return
                }
                SystemClock.sleep(100)
            }
            fail("Action must be clickable: $label")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.widthPixels / 320f
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                        NotedownTheme {
                            var selected by remember { mutableIntStateOf(0) }
                            Scaffold(bottomBar = {
                                DictationNavigationBar(selected, { selected = it; destination.set(it) }, { captures.incrementAndGet() })
                            }) { padding -> Box(Modifier.padding(dictationContentPadding(padding))) }
                        }
                    }
                }
            }
            val history = Rect().also { await("History").getBoundsInScreen(it) }
            val mic = Rect().also { await("Start dictation").getBoundsInScreen(it) }
            val settings = Rect().also { await("Settings").getBoundsInScreen(it) }
            val label = Rect().also { await("Dictate").getBoundsInScreen(it) }
            val screen = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
            listOf(history, mic, settings, label).forEach { assertTrue("Action must fit screen: $it", screen.contains(it)) }
            assertTrue(history.right <= mic.left)
            assertTrue(mic.right <= settings.left)
            assertTrue("Dictate must stay horizontally centered", kotlin.math.abs(screen.centerX() - mic.centerX()) <= 2)
            click("Settings")
            assertEquals(1, destination.get())
            assertEquals(0, captures.get())
            click("Start dictation")
            assertEquals(1, captures.get())
            assertEquals(1, destination.get())
            click("History")
            assertEquals(0, destination.get())
            click("Start dictation")
            assertEquals(2, captures.get())
        }
    }
}
