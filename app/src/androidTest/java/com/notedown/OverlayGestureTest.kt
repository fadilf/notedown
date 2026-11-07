package com.notedown

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notedown.overlay.DraggableOverlayLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayGestureTest {
    @Test fun tapsClickButDragsAndCancelledDragsNeverClick() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicks = 0
        val drops = mutableListOf<Boolean>()
        var moves = 0
        lateinit var layout: DraggableOverlayLayout
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            layout = DraggableOverlayLayout(activity).apply {
                addView(Button(context).apply { setOnClickListener { clicks++ } }, LinearLayout.LayoutParams(80, 80))
                onDrag = { _, _ -> moves++ }
                onDrop = { drops.add(it) }
            }
            activity.setContentView(layout)
        }
        instrumentation.waitForIdleSync()
        fun gesture(moveX: Float, moveY: Float, end: Int) {
            instrumentation.runOnMainSync {
                val time = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { layout.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, 40f, 40f)
                assertTrue(layout.touching)
                event(MotionEvent.ACTION_MOVE, moveX, moveY)
                event(end, moveX, moveY)
                assertFalse(layout.touching)
            }
            instrumentation.waitForIdleSync()
        }
        try {
            gesture(41f, 41f, MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            assertTrue(drops.isEmpty())
            gesture(180f, 140f, MotionEvent.ACTION_UP)
            assertEquals(1, clicks)
            assertEquals(listOf(true), drops)
            gesture(180f, 140f, MotionEvent.ACTION_CANCEL)
            assertEquals(1, clicks)
            assertEquals(listOf(true, false), drops)
            assertTrue(moves >= 2)
        } finally { scenario.close() }
    }
}
