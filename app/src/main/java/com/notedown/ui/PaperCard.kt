package com.notedown.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private object FoldedPaperShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val fold = with(density) { 18.dp.toPx() }.coerceAtMost(size.width / 2)
        val radius = with(density) { 6.dp.toPx() }
        return Outline.Generic(Path().apply {
            moveTo(radius, 0f)
            lineTo(size.width - fold, 0f)
            lineTo(size.width, fold)
            lineTo(size.width, size.height - radius)
            quadraticTo(size.width, size.height, size.width - radius, size.height)
            lineTo(radius, size.height)
            quadraticTo(0f, size.height, 0f, size.height - radius)
            lineTo(0f, radius)
            quadraticTo(0f, 0f, radius, 0f)
            close()
        })
    }
}

@Composable
fun PaperCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val foldColor = MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = modifier.drawWithContent {
            drawContent()
            val fold = 18.dp.toPx().coerceAtMost(size.width / 2)
            drawPath(Path().apply {
                moveTo(size.width - fold, 0f)
                lineTo(size.width, fold)
                lineTo(size.width - fold + 6.dp.toPx(), fold)
                quadraticTo(size.width - fold, fold, size.width - fold, fold - 6.dp.toPx())
                close()
            }, foldColor)
        },
        shape = FoldedPaperShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        content = content
    )
}
