package com.notedown.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PaperColors = lightColorScheme(
    primary = Color(0xFFAD5700), onPrimary = Color(0xFFFFFEFC),
    primaryContainer = Color(0xFFEEE7D9), onPrimaryContainer = Color(0xFFAD5700),
    inversePrimary = Color(0xFFF2A33A),
    secondary = Color(0xFF60564A), onSecondary = Color(0xFFFFFEFC),
    secondaryContainer = Color(0xFFEEE7D9), onSecondaryContainer = Color(0xFFAD5700),
    tertiary = Color(0xFF60564A), onTertiary = Color(0xFFFFFEFC),
    tertiaryContainer = Color(0xFFEEE7D9), onTertiaryContainer = Color(0xFF2D241B),
    background = Color(0xFFF6F2EA), onBackground = Color(0xFF2D241B),
    surface = Color(0xFFFFFEFC), onSurface = Color(0xFF2D241B),
    surfaceVariant = Color(0xFFEEE7D9), onSurfaceVariant = Color(0xFF70665A),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2D241B), inverseOnSurface = Color(0xFFF6F2EA),
    surfaceBright = Color(0xFFFFFEFC), surfaceDim = Color(0xFFEEE7D9),
    surfaceContainerLowest = Color(0xFFFFFEFC), surfaceContainerLow = Color(0xFFFFFEFC),
    surfaceContainer = Color(0xFFF6F2EA), surfaceContainerHigh = Color(0xFFEEE7D9),
    surfaceContainerHighest = Color(0xFFE9E0D0),
    outline = Color(0xFFA39786), outlineVariant = Color(0xFFE7DFD1),
    error = Color(0xFFAA3525), onError = Color.White,
    errorContainer = Color(0xFFFFE5DC), onErrorContainer = Color(0xFF742313)
)

private val PaperTypography = Typography().let {
    it.copy(
        headlineLarge = it.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = it.headlineMedium.copy(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = it.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = it.titleLarge.copy(fontFamily = FontFamily.Serif),
        labelLarge = it.labelLarge.copy(letterSpacing = 0.sp)
    )
}

@Composable fun NotedownTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PaperColors,
        typography = PaperTypography,
        shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(16.dp)),
        content = content
    )
}
