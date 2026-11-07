package com.notedown.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

fun insertAtSelection(existing: String, start: Int, end: Int, dictated: String): String? {
    if (start !in 0..existing.length || end !in 0..existing.length || end < start) return null
    return existing.substring(0, start) + dictated + existing.substring(end)
}

class TextInsertionManager {
    /** Resolve the destination at completion, using its latest content and selection. */
    fun insertIntoFocused(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null || text.isBlank() || !node.refresh() || !node.isEditable ||
            !node.isEnabled || node.isPassword || !node.isFocused) return false
        // Selection toolbars can mark the focused editor as occluded. The caller
        // already resolves it from the focused application window; keep that focus
        // check rather than rejecting a valid cursor because of temporary occlusion.
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        // Some editors omit selection metadata for a completely empty field.
        val start = if (existing.isEmpty()) 0 else node.textSelectionStart
        val end = if (existing.isEmpty()) 0 else node.textSelectionEnd
        val updated = insertAtSelection(existing, start, end, text) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + text.length)
        })
        return true
    }
}
