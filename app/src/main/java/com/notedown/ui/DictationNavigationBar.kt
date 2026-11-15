package com.notedown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val DictationBarOverlap = 28.dp

@Composable
fun dictationContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    // The raised microphone's touch area belongs to the bar, but its surrounding
    // transparent space belongs to the scrolling viewport above the divider.
    return PaddingValues(
        start = scaffoldPadding.calculateStartPadding(direction),
        top = scaffoldPadding.calculateTopPadding(),
        end = scaffoldPadding.calculateEndPadding(direction),
        bottom = (scaffoldPadding.calculateBottomPadding() - DictationBarOverlap).coerceAtLeast(0.dp)
    )
}

@Composable
fun DictationNavigationBar(destination: Int, navigate: (Int) -> Unit, dictate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val navigationColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.primary,
        selectedTextColor = colors.onBackground,
        indicatorColor = Color.Transparent,
        unselectedIconColor = colors.secondary,
        unselectedTextColor = colors.secondary
    )
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val top = DictationBarOverlap.toPx()
            drawRect(colors.background, topLeft = Offset(0f, top), size = Size(size.width, (size.height - top).coerceAtLeast(0f)))
        }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .heightIn(min = 104.dp)
    ) {
        HorizontalDivider(Modifier.padding(top = DictationBarOverlap), color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).heightIn(min = 76.dp)) {
            NavigationBarItem(
                selected = destination == 0, onClick = { navigate(0) }, colors = navigationColors,
                icon = { Icon(Icons.Outlined.History, null) },
                label = { Text("History", fontWeight = if (destination == 0) FontWeight.SemiBold else FontWeight.Normal) }
            )
            Spacer(Modifier.weight(1f))
            NavigationBarItem(
                selected = destination == 1, onClick = { navigate(1) }, colors = navigationColors,
                icon = { Icon(Icons.Outlined.Settings, null) },
                label = { Text("Settings", fontWeight = if (destination == 1) FontWeight.SemiBold else FontWeight.Normal) }
            )
        }
        Column(
            Modifier.align(Alignment.TopCenter).padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                onClick = dictate,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = colors.inverseSurface,
                contentColor = colors.inversePrimary,
                border = BorderStroke(5.dp, colors.background),
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Mic, "Start dictation", Modifier.size(32.dp))
                }
            }
            Text("Dictate", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}
