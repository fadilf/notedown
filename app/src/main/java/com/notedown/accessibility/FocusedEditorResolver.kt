package com.notedown.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Resolve afresh for both capture and delivery; never retain a previous editor. */
class FocusedEditorResolver(private val ownPackage: String) {
    fun resolve(windows: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? = windows.asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
        .mapNotNull { it.root }
        .mapNotNull(::resolveRoot)
        .firstOrNull()

    internal fun resolveRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && eligible(focus)) return focus

        // Custom/virtual accessibility providers may not implement findFocus, or
        // may return a container rather than its focused editor. Only accept an
        // explicitly input-focused node, never accessibility focus or editability alone.
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < MAX_NODES) {
            val node = pending.removeFirst()
            if (eligible(node)) return node
            for (index in 0 until minOf(node.childCount, MAX_NODES - visited - pending.size)) {
                node.getChild(index)?.let(pending::addLast)
            }
        }
        return null
    }

    private fun eligible(node: AccessibilityNodeInfo): Boolean = node.isFocused && node.isEditable &&
        node.isEnabled && !node.isPassword && node.packageName != null && node.packageName.toString() != ownPackage

    private companion object { const val MAX_NODES = 2000 }
}
