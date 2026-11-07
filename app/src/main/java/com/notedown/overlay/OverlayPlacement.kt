package com.notedown.overlay

/** Window-local safe bounds, after reserving system bars, keyboard and an edge margin. */
internal data class OverlayPlacement(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun x(dockLeft: Boolean, width: Int): Int = if (dockLeft) left else maxX(width)
    fun maxX(width: Int): Int = (right - width).coerceAtLeast(left)
    fun maxY(height: Int): Int = (bottom - height).coerceAtLeast(top)
    fun y(fraction: Float?, height: Int): Int = top + ((maxY(height) - top) * (fraction ?: 1f).coerceIn(0f, 1f)).toInt()
    fun fraction(y: Int, height: Int): Float = if (maxY(height) == top) 0f
        else ((y - top).toFloat() / (maxY(height) - top)).coerceIn(0f, 1f)
    fun dockLeft(x: Int, width: Int): Boolean = x + width / 2 <= (left + right) / 2
}
