package com.notedown.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

data class KeyboardState(val visible: Boolean, val top: Int?)
class KeyboardVisibilityTracker {
    fun detect(windows: List<AccessibilityWindowInfo>): KeyboardState {
        // Editable focus alone is insufficient: the IME must have an actual visible window.
        val keyboard = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.map { window ->
            Rect().also(window::getBoundsInScreen)
        }.filter { !it.isEmpty }.minByOrNull { it.top }
        return KeyboardState(keyboard != null, keyboard?.top)
    }
}
