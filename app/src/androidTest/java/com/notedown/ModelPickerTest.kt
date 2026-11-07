package com.notedown

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.api.RouterModel
import com.notedown.ui.ModelPicker
import com.notedown.ui.ModelPickerDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPickerTest {
    @Test fun allColumnsFitNarrowDialogWithLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val model = RouterModel("fixture/long-model-name", "Example model with a long display name",
            Json.parseToJsonElement("""{"prompt":"0.00001666667","completion":"0.0000002"}""").jsonObject)
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
            return null
        }
        fun visibleBounds(text: String): Rect {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                val node = find(automation.rootInActiveWindow, text)
                if (node?.isVisibleToUser == true) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) return bounds
                }
                SystemClock.sleep(100)
            }
            throw AssertionError("Text is not visible without scrolling: $text")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val narrowDensity = activity.resources.displayMetrics.widthPixels / 320f
                activity.setContent {
                    // Exercise a 320 dp viewport and enlarged fonts without changing device settings.
                    CompositionLocalProvider(LocalDensity provides Density(narrowDensity, fontScale = 1.3f)) {
                        MaterialTheme {
                            ModelPickerDialog("Fixture models", "", listOf(model), "", {}, {}, {})
                        }
                    }
                }
            }
            val modelHeader = visibleBounds("Model")
            val inputHeader = visibleBounds("Input")
            val outputHeader = visibleBounds("Output")
            val screen = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
            listOf(modelHeader, inputHeader, outputHeader, visibleBounds(model.name),
                visibleBounds("$16.67"), visibleBounds("$0.20")).forEach {
                assertTrue("Cell must fit the dialog: $it in $screen", screen.contains(it))
            }
            assertTrue(modelHeader.right <= inputHeader.left)
            assertTrue(inputHeader.right <= outputHeader.left)
            assertEquals(inputHeader.top, outputHeader.top)
        }
    }

    @Test fun tableSearchSelectionAndPricingDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val models = listOf(
            RouterModel("fixture/alpha", "Example Alpha", Json.parseToJsonElement(
                """{"prompt":"0.00001666667","completion":"0","web_search":"0.01"}"""
            ).jsonObject),
            RouterModel("fixture/beta", "Example Beta", Json.parseToJsonElement(
                """{"prompt":"0.000000003"}"""
            ).jsonObject)
        )
        fun findIn(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(text) == true) return node
            for (index in 0 until node.childCount) findIn(node.getChild(index), text)?.let { return it }
            return null
        }
        fun find(text: String): AccessibilityNodeInfo? = findIn(automation.rootInActiveWindow, text)
        fun await(text: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                find(text)?.let { return it }
                SystemClock.sleep(100)
            }
            throw AssertionError("Missing UI text: $text")
        }
        fun click(text: String) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                var node: AccessibilityNodeInfo? = find(text)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                SystemClock.sleep(100)
            }
            fail("No clickable UI text: $text")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var selected by remember { mutableStateOf("fixture/alpha") }
                    MaterialTheme { ModelPicker("Fixture models", selected, models) { selected = it } }
                }
            }
            click("Fixture models")
            await("USD per 1M tokens")
            await("$16.67")
            await("$0.0030")
            await("Unknown")
            await("✓ Selected")
            fun awaitOrder(first: String, second: String) {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (SystemClock.uptimeMillis() < deadline) {
                    val firstBounds = Rect().also { find(first)?.getBoundsInScreen(it) }
                    val secondBounds = Rect().also { find(second)?.getBoundsInScreen(it) }
                    if (!firstBounds.isEmpty && !secondBounds.isEmpty && firstBounds.top < secondBounds.top) return
                    SystemClock.sleep(100)
                }
                fail("Expected $first above $second")
            }
            click("Input")
            await("↑")
            awaitOrder("Example Beta", "Example Alpha")
            click("Input")
            await("↓")
            awaitOrder("Example Alpha", "Example Beta")
            // Unknown output pricing stays below a known zero price in either direction.
            click("Output")
            await("↑")
            awaitOrder("Example Alpha", "Example Beta")
            click("Output")
            await("↓")
            awaitOrder("Example Alpha", "Example Beta")
            click("Model")
            await("↑")
            awaitOrder("Example Alpha", "Example Beta")
            click("Model")
            await("↓")
            awaitOrder("Example Beta", "Example Alpha")
            click("Pricing details")
            await("web search: 0.01")
            // Search by ID as well as name, then select the remaining row.
            fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isEditable) return node
                for (index in 0 until node.childCount) editable(node.getChild(index))?.let { return it }
                return null
            }
            val search = checkNotNull(editable(automation.rootInActiveWindow))
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture/beta")
            }))
            await("Example Beta")
            val deadline = SystemClock.uptimeMillis() + 5000
            while (find("Example Alpha") != null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertNull(find("Example Alpha"))
            click("Example Beta")
            click("Fixture models")
            await("✓ Selected")
            await("Example Beta")
            click("Close")
        }
    }
}
