package com.notedown.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

fun insertAtSelection(existing: String, start: Int, end: Int, dictated: String): String? {
    if (start !in 0..existing.length || end !in 0..existing.length || end < start) return null
    return existing.substring(0, start) + dictated + existing.substring(end)
}
data class FieldTarget(val token: String, val node: AccessibilityNodeInfo, val windowId: Int, val packageName: String,
    val viewId: String?, val original: String, val start: Int, val end: Int)
class TextInsertionManager {
    private var target: FieldTarget? = null
    fun capture(node: AccessibilityNodeInfo?): String? {
        clear()
        if (node == null || !node.isEditable || node.isPassword) return null
        val token = UUID.randomUUID().toString()
        @Suppress("DEPRECATION")
        target = FieldTarget(token, AccessibilityNodeInfo.obtain(node), node.windowId, node.packageName.toString(), node.viewIdResourceName,
            node.text?.toString().orEmpty(), node.textSelectionStart, node.textSelectionEnd)
        return token
    }
    fun insert(token: String?, text: String, activePackage: String?): Boolean {
        val saved = target ?: return false
        if (saved.token != token || text.isBlank() || activePackage != saved.packageName) return false
        val node = saved.node
        if (!node.refresh() || !node.isEditable || node.isPassword || !node.isVisibleToUser || !node.isFocused ||
            node.windowId != saved.windowId || node.packageName.toString() != saved.packageName || node.viewIdResourceName != saved.viewId ||
            node.text?.toString().orEmpty() != saved.original || node.textSelectionStart != saved.start || node.textSelectionEnd != saved.end) return false
        val updated = insertAtSelection(saved.original, saved.start, saved.end, text) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, saved.start + text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, saved.start + text.length)
        })
        clear(); return true
    }
    fun invalidateIfDifferent(node: AccessibilityNodeInfo?) {
        val saved = target ?: return
        if (node != null && node.isEditable && node.packageName.toString() != "com.notedown" && node != saved.node) clear()
    }
    fun clear() { target = null }
}
