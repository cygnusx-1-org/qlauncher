/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cygnusx1.qlauncher.ui.theme.QTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The settings pages' left pane: a full-screen copy of [content] rotated about the vertical axis
 * through its left edge, so its right edge recedes and lands on the pane's right edge.
 * Tinted with the accent, with drifting specks over it.
 *
 * @param paneWidthFraction where the right edge must land, as a fraction of the screen width.
 */
@Composable
fun TiltedPreview(modifier: Modifier = Modifier, paneWidthFraction: Float = 1120f / 1920f, content: @Composable () -> Unit) {
    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { conf.screenWidthDp.dp.toPx() }
    val heightPx = with(density) { conf.screenHeightDp.dp.toPx() }
    val dpi = density.density * 160f
    // Compose passes cameraDistance to the RenderNode as is, in inches; Android projects one
    // inch as 72 px. Projectivy's 2.5 x width (in pixels, through View) is width / dpi inches.
    val cameraInches = 2.5f * widthPx / dpi
    val cameraPx = cameraInches * 72f
    val angle = remember(widthPx, cameraPx, paneWidthFraction) { solveAngle(widthPx, cameraPx, paneWidthFraction) }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                // A full-screen-sized copy, anchored at the pane's left edge.
                .layout { measurable, constraints ->
                    val p = measurable.measure(Constraints.fixed(widthPx.toInt(), heightPx.toInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, (constraints.maxHeight - p.height) / 2) }
                }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = angle
                    cameraDistance = cameraInches
                },
        ) {
            content()
            Box(Modifier.fillMaxSize().background(QTheme.colors.accent.copy(alpha = 0.18f)))
        }
        ParticleLayer(Modifier.fillMaxSize())
    }
}

/** Finds the rotation (0..30°) whose projected right edge is at [fraction] of the width. */
internal fun solveAngle(widthPx: Float, cameraPx: Float, fraction: Float): Float {
    fun edge(deg: Float): Float {
        val t = deg * PI.toFloat() / 180f
        return widthPx * cos(t) * cameraPx / (cameraPx + widthPx * sin(t))
    }
    var lo = 0f
    var hi = 30f
    val target = widthPx * fraction
    repeat(40) {
        val mid = (lo + hi) / 2
        if (edge(mid) > target) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

private class Speck(var x: Float, var y: Float, val scale: Float, val speed: Float, val left: Boolean, var travelled: Float)

/**
 * 24 drifting specks: they rise at up to 120 dp/s scaled by a
 * random factor and their size, drift sideways at a quarter of that, tumble once per screen
 * height, and are tinted from the accent towards a pale cream with a pulsing alpha. The speck is
 * QLauncher's own shape.
 */
@Composable
fun ParticleLayer(modifier: Modifier = Modifier) {
    val accent = QTheme.colors.accent
    val cream = Color(0xFFE9E4B4)
    var tick by remember { mutableLongStateOf(0L) }
    val specks = remember { mutableListOf<Speck>() }
    val rnd = remember { Random(42) }
    val density = LocalDensity.current.density
    var started by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        delay(1000)
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                tick = now
                if (started == 0L) started = now
                specks.forEach { s ->
                    val v = 120f * density * s.speed * s.scale
                    s.y -= v * dt
                    s.x += (if (s.left) -1 else 1) * v / 4f * dt
                    s.travelled += v * dt
                }
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        if (specks.isEmpty() && size.width > 0) {
            repeat(24) { i ->
                specks += Speck(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height, 0.45f + rnd.nextFloat() * 0.55f, 0.3f + rnd.nextFloat() * 0.7f, i % 2 == 0, rnd.nextFloat() * size.height)
            }
        }
        if (started == 0L) return@Canvas
        specks.forEach { s ->
            if (s.y < -40f || s.x < -40f || s.x > size.width + 40f) {
                s.y = size.height + 20f
                s.x = rnd.nextFloat() * size.width
                s.travelled = 0f
            }
            val phase = (s.travelled / size.height) % 1f
            val flip = cos(phase * 2 * PI).toFloat()
            val color = lerp(accent, cream, abs(flip)).copy(alpha = 0.25f + 0.35f * abs(sin(phase * PI).toFloat()))
            val r = 14f * density * s.scale
            rotate(phase * 360f, Offset(s.x, s.y)) {
                scale(flip.coerceIn(-1f, 1f).let { if (abs(it) < 0.15f) 0.15f else it }, 1f, Offset(s.x, s.y)) {
                    val path = Path().apply {
                        moveTo(s.x, s.y - r)
                        quadraticTo(s.x + r * 0.9f, s.y, s.x, s.y + r * 0.6f)
                        quadraticTo(s.x - r * 0.9f, s.y, s.x, s.y - r)
                        close()
                    }
                    drawPath(path, color)
                }
            }
        }
    }
}
