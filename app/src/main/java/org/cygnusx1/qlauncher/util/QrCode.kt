/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min

/**
 * A QR code for [text] in the dotted style of the settings pages: round dots for the data and
 * rounded squares for the three finder patterns, no quiet zone, filling the smaller side of the
 * available space. Draws nothing when the text is too long for version 15.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    val matrix = remember(text) { runCatching { QrEncoder.encode(text) }.getOrNull() }
    Canvas(modifier) {
        if (matrix != null) drawQr(matrix, color)
    }
}

private fun DrawScope.drawQr(m: QrMatrix, color: Color) {
    val n = m.size
    val cell = min(size.width, size.height) / n
    val left = (size.width - cell * n) / 2f
    val top = (size.height - cell * n) / 2f
    val radius = cell * 0.42f

    for (row in 0 until n) {
        for (col in 0 until n) {
            if (m.isFinder(row, col) || !m[row, col]) continue
            drawCircle(color, radius, Offset(left + (col + 0.5f) * cell, top + (row + 0.5f) * cell))
        }
    }

    for ((r0, c0) in listOf(0 to 0, 0 to n - 7, n - 7 to 0)) {
        val x = left + c0 * cell
        val y = top + r0 * cell
        // Outer ring: 7 modules square, one module thick, drawn on its centre line.
        drawRoundRect(
            color = color,
            topLeft = Offset(x + cell / 2f, y + cell / 2f),
            size = Size(cell * 6f, cell * 6f),
            cornerRadius = CornerRadius(cell * 1.4f),
            style = Stroke(width = cell * 0.92f),
        )
        // Centre: 3 × 3 modules.
        drawRoundRect(
            color = color,
            topLeft = Offset(x + cell * 2f, y + cell * 2f),
            size = Size(cell * 3f, cell * 3f),
            cornerRadius = CornerRadius(cell * 0.8f),
        )
    }
}
