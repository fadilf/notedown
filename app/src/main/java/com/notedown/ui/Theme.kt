package com.notedown.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable fun NotedownTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current) }
    else if (dark) darkColorScheme(primary = Color(0xFFB2D1BB), background = Color(0xFF141A16))
    else lightColorScheme(primary = Color(0xFF355C47), background = Color(0xFFF8FAF5), surface = Color(0xFFF8FAF5))
    MaterialTheme(colorScheme = colors, content = content)
}
