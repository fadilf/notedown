package com.notedown.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.notedown.R
import com.notedown.data.CleanupProfileIcon

internal val CleanupProfileIcon.label: String get() = when (this) {
    CleanupProfileIcon.CLEANUP -> "Cleanup"
    CleanupProfileIcon.LIST -> "List"
    CleanupProfileIcon.CHECKLIST -> "Checklist"
    CleanupProfileIcon.NOTE -> "Note"
    CleanupProfileIcon.EMAIL -> "Email"
    CleanupProfileIcon.CHAT -> "Chat"
    CleanupProfileIcon.CODE -> "Code"
    CleanupProfileIcon.TRANSLATE -> "Translate"
}

@get:DrawableRes
internal val CleanupProfileIcon.drawableResource: Int get() = when (this) {
    CleanupProfileIcon.CLEANUP -> R.drawable.ic_profile_cleanup
    CleanupProfileIcon.LIST -> R.drawable.ic_profile_list
    CleanupProfileIcon.CHECKLIST -> R.drawable.ic_profile_checklist
    CleanupProfileIcon.NOTE -> R.drawable.ic_profile_note
    CleanupProfileIcon.EMAIL -> R.drawable.ic_profile_email
    CleanupProfileIcon.CHAT -> R.drawable.ic_profile_chat
    CleanupProfileIcon.CODE -> R.drawable.ic_profile_code
    CleanupProfileIcon.TRANSLATE -> R.drawable.ic_profile_translate
}

@Composable internal fun CleanupProfileIconPicker(selected: CleanupProfileIcon, change: (CleanupProfileIcon) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(selected.drawableResource), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Profile icon: ${selected.label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CleanupProfileIcon.entries.forEach { icon ->
                DropdownMenuItem(text = { Text(icon.label) },
                    leadingIcon = { Icon(painterResource(icon.drawableResource), contentDescription = null) },
                    trailingIcon = { if (icon == selected) Text("✓") },
                    onClick = { change(icon); open = false })
            }
        }
    }
}
