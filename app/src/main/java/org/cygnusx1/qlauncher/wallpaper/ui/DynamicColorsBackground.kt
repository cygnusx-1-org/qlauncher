/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.cygnusx1.qlauncher.container
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The settings pages' background: black with soft fields of the current palette's colours that
 * drift slowly. Palette changes cross-fade over 1 s.
 */
@Composable
fun DynamicColorsBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val theme = remember(context) { context.container.theme }
    val palette by theme.palette.collectAsState()
    val warm by animateColorAsState(Color(palette.shade(700) ?: 0xFF4A2030.toInt()), tween(1000), label = "bgWarm")
    val deep by animateColorAsState(Color(palette.shade(800) ?: 0xFF2E1840.toInt()), tween(1000), label = "bgDeep")
    val light by animateColorAsState(Color(palette.shade(500) ?: 0xFF6A3A3A.toInt()), tween(1000), label = "bgLight")
    val drift = rememberInfiniteTransition(label = "bgDrift")
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DRIFT_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "bgPhase",
    )
    Canvas(modifier) {
        drawRect(Color.Black)
        val w = size.width
        val h = size.height
        val r = max(w, h)
        val a = (t * 2 * PI).toFloat()
        // A large field just left of centre, one low and to the right, a faint one at the top right.
        blob(warm.copy(alpha = 0.55f), Offset(w * (0.42f + 0.05f * cos(a)), h * (0.52f + 0.06f * sin(a))), r * 0.42f)
        blob(deep.copy(alpha = 0.6f), Offset(w * (0.55f + 0.06f * sin(a * 2)), h * (0.92f + 0.04f * cos(a))), r * 0.38f)
        blob(light.copy(alpha = 0.22f), Offset(w * (0.82f + 0.04f * cos(a + 1f)), h * (0.02f + 0.05f * sin(a + 1f))), r * 0.3f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.blob(color: Color, center: Offset, radius: Float) {
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius), radius = radius, center = center)
}

private const val DRIFT_PERIOD_MS = 40_000
