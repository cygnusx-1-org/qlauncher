/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.ui.theme.QTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Material 3 style stepped slider Projectivy uses: a 16 dp track split by a 4 dp bar thumb
 * with 6 dp gaps, stop dots, and a lighter fill from the [origin] to the thumb. Values map onto
 * the track inset by half the track height at each end, as Material's slider does.
 *
 * It is drawn only; the row that owns it handles LEFT and RIGHT.
 *
 * @param stops number of stops (`(max - min) / step + 1`).
 * @param position the value as a fraction 0..1 of the range.
 * @param origin the fill origin as a fraction 0..1.
 * @param bubble the value text shown above the thumb while it is being changed, or null.
 */
@Composable
fun StepSlider(
    position: Float,
    origin: Float,
    stops: Int,
    bubble: String?,
    modifier: Modifier = Modifier,
    active: Color = QTheme.colors.accent,
    inactive: Color = QTheme.colors.accentContainer,
    height: Dp = 32.dp,
    track: Dp = 16.dp,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val insetPx = with(density) { (track / 2).toPx() }
        val thumbX = insetPx + (widthPx - 2 * insetPx) * position.coerceIn(0f, 1f)
        Canvas(Modifier.matchParentSize()) {
            drawStepSlider(position, origin, stops, active, inactive, track.toPx())
        }
        AnimatedVisibility(
            visible = bubble != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .wrapContentSize(unbounded = true)
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(0, 0) {
                        p.place(
                            (thumbX - p.width / 2f).roundToInt(),
                            -p.height - 6.dp.roundToPx(),
                        )
                    }
                },
        ) {
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(bubble.orEmpty(), color = Color(0xFF1B1C1E), style = QTheme.type.rowTitle)
            }
        }
    }
}

private fun DrawScope.drawStepSlider(
    position: Float,
    origin: Float,
    stops: Int,
    active: Color,
    inactive: Color,
    trackPx: Float,
) {
    val w = size.width
    val cy = size.height / 2f
    val inset = trackPx / 2f
    val usable = w - 2 * inset
    fun xOf(f: Float) = inset + usable * f.coerceIn(0f, 1f)

    val thumbX = xOf(position)
    val thumbW = 4.dp.toPx()
    val gap = 6.dp.toPx()
    val outer = CornerRadius(trackPx / 2f)
    val innerR = CornerRadius(2.dp.toPx())
    val top = cy - trackPx / 2f
    val leftEnd = thumbX - thumbW / 2f - gap
    val rightStart = thumbX + thumbW / 2f + gap
    val originX = when {
        origin <= 0f -> 0f
        origin >= 1f -> w
        else -> xOf(origin)
    }
    val fillFrom = minOf(originX, thumbX)
    val fillTo = maxOf(originX, thumbX)

    fun segment(l: Float, r: Float, outerLeft: Boolean): RoundRect? {
        if (r - l <= 0.5f) return null
        return if (outerLeft) {
            RoundRect(l, top, r, top + trackPx, outer, innerR, innerR, outer)
        } else {
            RoundRect(l, top, r, top + trackPx, innerR, outer, outer, innerR)
        }
    }

    for (rect in listOfNotNull(segment(0f, leftEnd, true), segment(rightStart, w, false))) {
        val path = Path().apply { addRoundRect(rect) }
        drawPath(path, inactive)
        val l = maxOf(rect.left, fillFrom)
        val r = minOf(rect.right, fillTo)
        if (r > l) {
            clipPath(path) {
                drawRect(active, Offset(l, top), Size(r - l, trackPx))
            }
        }
    }

    // Stop dots: every stop when they fit, otherwise only the end.
    val dot = 2.dp.toPx()
    val showAll = stops in 2..30
    val dotIndexes = if (showAll) (0 until stops) else listOf(stops - 1)
    for (i in dotIndexes) {
        val f = if (stops <= 1) 1f else i.toFloat() / (stops - 1)
        val x = xOf(f)
        if (abs(x - thumbX) < thumbW / 2f + gap + dot) continue
        val onFill = x in fillFrom..fillTo
        drawCircle(if (onFill) inactive else active, dot, Offset(x, cy))
    }

    // Thumb bar.
    drawRoundRect(
        active,
        Offset(thumbX - thumbW / 2f, 0f),
        Size(thumbW, size.height),
        CornerRadius(thumbW / 2f),
    )
}
