package com.notedown.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.hypot

/** Intercept only once a gesture becomes a drag, cancelling the child's pending click. */
internal class DraggableOverlayLayout(context: Context) : LinearLayout(context) {
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    var onDrop: (Boolean) -> Unit = {}
    var touching = false
        private set
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragging = false
                downX = event.rawX
                downY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> if (hypot(event.rawX - downX, event.rawY - downY) > slop) {
                dragging = true
                onDrag(event.rawX - downX, event.rawY - downY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) dragging = true
                if (dragging) onDrag(event.rawX - downX, event.rawY - downY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (dragging) onDrop(event.actionMasked == MotionEvent.ACTION_UP)
                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
