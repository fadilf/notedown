package com.notedown.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.notedown.data.CleanupProfile

@Composable internal fun CleanupProfileList(
    profiles: List<CleanupProfile>,
    move: (String, Int) -> Unit,
    edit: (CleanupProfile) -> Unit,
    shortcut: (String, Boolean) -> Unit,
    dragPosition: (Float?) -> Unit = {}
) {
    var ordered by remember { mutableStateOf(profiles) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    var originalIndex by remember { mutableIntStateOf(0) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val handleWidth = with(LocalDensity.current) { 48.dp.toPx() }
    val direction = LocalLayoutDirection.current
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestMove by rememberUpdatedState(move)
    val latestPosition by rememberUpdatedState(dragPosition)
    LaunchedEffect(profiles) {
        if (draggedId == null) ordered = profiles
        bounds.keys.retainAll(profiles.map { it.id }.toSet())
    }
    // Re-evaluate during parent auto-scroll as well as pointer movement.
    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            withFrameNanos { }
            val id = draggedId ?: break
            val from = ordered.indexOfFirst { it.id == id }
            val to = ordered.count { it.id != id && (bounds[it.id]?.center?.y ?: Float.MAX_VALUE) < center }
            if (from >= 0 && from != to) ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    DisposableEffect(Unit) { onDispose { latestPosition(null) } }
    val start by rememberUpdatedState<(String, Float) -> Unit>({ id, pointerY ->
        bounds[id]?.let {
            draggedId = id
            originalIndex = ordered.indexOfFirst { profile -> profile.id == id }
            center = it.center.y
            grabOffset = center - pointerY
            latestPosition(center)
        }
    })
    val movePointer by rememberUpdatedState<(Float) -> Unit>({ pointerY -> center = pointerY + grabOffset; latestPosition(center) })
    val end by rememberUpdatedState<() -> Unit>({
        draggedId?.let { id ->
            val index = ordered.indexOfFirst { it.id == id }
            if (index != originalIndex) latestMove(id, index - originalIndex)
        }
        draggedId = null
        latestPosition(null)
    })
    val cancel by rememberUpdatedState<() -> Unit>({ draggedId = null; ordered = profiles; latestPosition(null) })
    Column(Modifier.onGloballyPositioned { listCoordinates = it }.pointerInput(handleWidth, direction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val position = listCoordinates?.localToRoot(down.position) ?: return@awaitEachGesture
            val id = bounds.entries.firstOrNull {
                it.value.contains(position) && if (direction == LayoutDirection.Ltr) position.x < it.value.left + handleWidth
                    else position.x > it.value.right - handleWidth
            }?.key ?: return@awaitEachGesture
            val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
            start(id, position.y)
            try {
                listCoordinates?.let { movePointer(it.localToRoot(slop.position).y) }
                val completed = drag(slop.id) { change ->
                    change.consume()
                    listCoordinates?.let { movePointer(it.localToRoot(change.position).y) }
                }
                if (completed) end() else cancel()
            } finally {
                if (draggedId != null) cancel()
            }
        }
    }) {
        ordered.forEachIndexed { index, profile -> key(profile.id) {
            val dragging = draggedId == profile.id
            Box(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).onGloballyPositioned {
                bounds[profile.id] = Rect(it.positionInRoot(), it.size.toSize())
            }) {
                Surface(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = if (draggedId == profile.id) center - (bounds[profile.id]?.center?.y ?: center) else 0f
                }, color = MaterialTheme.colorScheme.surface, shadowElevation = if (dragging) 6.dp else 0.dp) {
                    Column {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).semantics {
                                contentDescription = "Reorder ${profile.name}"
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction("Move up") { move(profile.id, -1); true })
                                    if (index < ordered.lastIndex) add(CustomAccessibilityAction("Move down") { move(profile.id, 1); true })
                                }
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.DragHandle, contentDescription = null)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                Text("Show below recording controls", style = MaterialTheme.typography.bodySmall)
                                if (profile.model.isBlank()) Text("Choose a cleanup model before using this profile.", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { edit(profile) }) { Icon(Icons.Outlined.Edit, "Edit ${profile.name}") }
                            Switch(profile.shortcut, { shortcut(profile.id, it) }, Modifier.semantics { contentDescription = "Show ${profile.name} below recording controls" })
                        }
                    }
                }
            }
        } }
    }
}
