package com.notedown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notedown.api.RouterModel

@Composable
internal fun ModelPickerDialog(
    label: String,
    selected: String,
    models: List<RouterModel>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var sortColumn by remember { mutableStateOf<ModelSortColumn?>(null) }
    var descending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val filtered = remember(models, query, sortColumn, descending) {
        sortModels(models.filter { it.name.contains(query, true) || it.id.contains(query, true) }, sortColumn, descending)
    }
    LaunchedEffect(sortColumn, descending, query) { listState.scrollToItem(0) }
    fun sort(column: ModelSortColumn) {
        descending = if (sortColumn == column) !descending else false
        sortColumn = column
    }
    var expandedId by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(query, onQueryChange, label = { Text("Search models") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("USD per 1M tokens · catalog rates", style = MaterialTheme.typography.labelMedium)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        SortHeader("Model", Modifier.weight(2f), sortColumn == ModelSortColumn.MODEL, descending,
                            Alignment.Start) { sort(ModelSortColumn.MODEL) }
                        SortHeader("Input", Modifier.weight(1f), sortColumn == ModelSortColumn.INPUT, descending,
                            Alignment.End) { sort(ModelSortColumn.INPUT) }
                        SortHeader("Output", Modifier.weight(1f), sortColumn == ModelSortColumn.OUTPUT, descending,
                            Alignment.End) { sort(ModelSortColumn.OUTPUT) }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f), state = listState) {
                        if (filtered.isEmpty()) item {
                            Text(if (models.isEmpty()) "Refresh models in Settings. An internet connection is required."
                                else "No matching models.", Modifier.padding(12.dp))
                        }
                        items(filtered, key = { it.id }) { model ->
                            val isSelected = model.id == selected
                            val details = remember(model.pricing) { model.pricing?.let(::pricingDetails).orEmpty() }
                            Column {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(model.id) })
                                        .padding(horizontal = 8.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(2f).padding(end = 8.dp)) {
                                        if (isSelected) Text("✓ Selected", style = MaterialTheme.typography.labelSmall)
                                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                                        Text(model.id, style = MaterialTheme.typography.bodySmall)
                                    }
                                    PriceCell(tokenPricePerMillion(model.pricing?.get("prompt")))
                                    PriceCell(tokenPricePerMillion(model.pricing?.get("completion")))
                                }
                                if (details.isNotEmpty()) {
                                    TextButton(onClick = { expandedId = if (expandedId == model.id) null else model.id }) {
                                        Text(if (expandedId == model.id) "Hide pricing details" else "Pricing details")
                                    }
                                    if (expandedId == model.id) Column(Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Other rates use the provider’s original units unless labeled.",
                                            style = MaterialTheme.typography.labelSmall)
                                        details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Text("Tap a heading to sort; tap a row to select.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SortHeader(
    label: String,
    modifier: Modifier,
    active: Boolean,
    descending: Boolean,
    alignment: Alignment.Horizontal,
    onClick: () -> Unit
) {
    Column(modifier.heightIn(min = 48.dp)
        .semantics { stateDescription = if (!active) "Not sorted" else if (descending) "Sorted descending" else "Sorted ascending" }
        .clickable(role = Role.Button, onClickLabel = "Sort by $label", onClick = onClick),
        horizontalAlignment = alignment) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (active) Text(if (descending) "↓" else "↑", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RowScope.PriceCell(text: String) {
    Text(text, Modifier.weight(1f).padding(start = 4.dp),
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
}
