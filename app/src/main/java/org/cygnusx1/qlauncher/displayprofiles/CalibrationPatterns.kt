/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme
import kotlin.math.floor
import kotlin.math.min

/** The dynamic range a pattern needs. */
enum class PatternRange { SDR, HDR, DV }

/** One entry of the *Preview* list. HDR and Dolby Vision ones are listed but not drawable yet. */
data class CalibrationPatternInfo(val index: Int, @StringRes val nameRes: Int, val range: PatternRange) {
    /** Only the SDR patterns can be drawn. */
    val isAvailable: Boolean get() = range == PatternRange.SDR
}

/** The 24 patterns of the profile editor, in Projectivy's order. The 12 SDR ones are drawn. */
object CalibrationPatterns {
    const val BLACK_CLIPPING = 0
    const val APL_CLIPPING = 1
    const val WHITE_CLIPPING = 2
    const val GRAYSCALE_RAMP = 3
    const val GRAYSCALE_STEPS = 4
    const val DYNAMIC_BRIGHTNESS = 5
    const val COLOR_STEPS = 6
    const val COLOR_CLIPPING = 7
    const val FLASHING_COLOR_BARS = 8
    const val SHARPNESS_OVERSCAN = 9
    const val UHD = 10
    const val JUDDER = 11

    val all: List<CalibrationPatternInfo> = listOf(
        R.string.backup_pattern_black_clipping to PatternRange.SDR,
        R.string.backup_pattern_apl_clipping to PatternRange.SDR,
        R.string.backup_pattern_white_clipping to PatternRange.SDR,
        R.string.backup_pattern_grayscale_ramp to PatternRange.SDR,
        R.string.backup_pattern_grayscale_steps to PatternRange.SDR,
        R.string.backup_pattern_dynamic_brightness to PatternRange.SDR,
        R.string.backup_pattern_color_steps to PatternRange.SDR,
        R.string.backup_pattern_color_clipping to PatternRange.SDR,
        R.string.backup_pattern_flashing_color_bars to PatternRange.SDR,
        R.string.backup_pattern_sharpness_overscan to PatternRange.SDR,
        R.string.backup_pattern_uhd to PatternRange.SDR,
        R.string.backup_pattern_judder to PatternRange.SDR,
        R.string.backup_pattern_hdr_aspect_ratio_sharpness to PatternRange.HDR,
        R.string.backup_pattern_hdr_black_level to PatternRange.HDR,
        R.string.backup_pattern_hdr_color_clipping_240_1000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_color_clipping_3600_10000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_color_clipping_700_10000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_color_clipping_900_4000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_grayscale_ramp_10000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_grayscale_ramp_1000 to PatternRange.HDR,
        R.string.backup_pattern_hdr_grayscale_ramp_4000 to PatternRange.HDR,
        R.string.backup_pattern_dv_black_pluge to PatternRange.DV,
        R.string.backup_pattern_dv_bt2111 to PatternRange.DV,
        R.string.backup_pattern_dv_color_ramps to PatternRange.DV,
    ).mapIndexed { i, (res, range) -> CalibrationPatternInfo(i, res, range) }

    val sdr: List<CalibrationPatternInfo> get() = all.filter { it.range == PatternRange.SDR }
}

/**
 * Draws calibration pattern [index] to fill [modifier]'s bounds. The patterns are QLauncher's
 * own drawings, made to be judged on a 1920 × 1080 panel; an HDR or Dolby Vision entry shows its
 * name and that it is not available.
 */
@Composable
fun CalibrationPattern(index: Int, modifier: Modifier = Modifier) {
    val info = CalibrationPatterns.all.getOrNull(index)
    if (info == null || !info.isAvailable) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (info != null) {
                Text(
                    text = stringResource(R.string.backup_pattern_unavailable, stringResource(info.nameRes)),
                    style = QTheme.type.rowTitle,
                    color = QPalette.TextDescription,
                )
            }
        }
        return
    }
    val animated = index == CalibrationPatterns.DYNAMIC_BRIGHTNESS || index == CalibrationPatterns.FLASHING_COLOR_BARS ||
        index == CalibrationPatterns.JUDDER
    val timeMillis by produceState(0L, animated) {
        if (!animated) return@produceState
        val start = androidx.compose.runtime.withFrameMillis { it }
        while (true) {
            androidx.compose.runtime.withFrameMillis { value = it - start }
        }
    }
    Canvas(modifier.background(Color.Black)) {
        when (index) {
            CalibrationPatterns.BLACK_CLIPPING -> blackClipping()
            CalibrationPatterns.APL_CLIPPING -> aplClipping()
            CalibrationPatterns.WHITE_CLIPPING -> whiteClipping()
            CalibrationPatterns.GRAYSCALE_RAMP -> grayscaleRamp()
            CalibrationPatterns.GRAYSCALE_STEPS -> grayscaleSteps()
            CalibrationPatterns.DYNAMIC_BRIGHTNESS -> dynamicBrightness(timeMillis)
            CalibrationPatterns.COLOR_STEPS -> colorSteps()
            CalibrationPatterns.COLOR_CLIPPING -> colorClipping()
            CalibrationPatterns.FLASHING_COLOR_BARS -> flashingColorBars(timeMillis)
            CalibrationPatterns.SHARPNESS_OVERSCAN -> sharpnessOverscan()
            CalibrationPatterns.UHD -> uhdPattern()
            CalibrationPatterns.JUDDER -> judder(timeMillis)
        }
    }
}

private fun gray(level: Int): Color = Color(level, level, level)

/** Vertical bars of [levels] across [width] starting at [left], [top] to [bottom]. */
private fun DrawScope.bars(levels: List<Color>, left: Float, top: Float, width: Float, height: Float, gapFraction: Float = 0f) {
    val w = width / levels.size
    levels.forEachIndexed { i, c ->
        val x0 = floor(left + i * w)
        val x1 = floor(left + (i + 1) * w - w * gapFraction)
        drawRect(c, Offset(x0, top), Size(x1 - x0, height))
    }
}

/** Bars at code values 1 to 20 on reference black; a display that crushes blacks hides the first ones. */
private fun DrawScope.blackClipping() {
    drawRect(gray(0))
    val levels = (1..20).map(::gray)
    bars(levels, size.width * 0.1f, size.height * 0.25f, size.width * 0.8f, size.height * 0.5f, gapFraction = 0.5f)
    // A reference stripe of 2 % gray below, so the eye has an anchor.
    drawRect(gray(5), Offset(size.width * 0.1f, size.height * 0.8f), Size(size.width * 0.8f, size.height * 0.04f))
}

/** Near-black and near-white bars inside a mid-gray surround, for clipping at a higher average level. */
private fun DrawScope.aplClipping() {
    drawRect(gray(128))
    val boxW = size.width * 0.38f
    val boxH = size.height * 0.5f
    val top = size.height * 0.25f
    drawRect(gray(0), Offset(size.width * 0.08f, top), Size(boxW, boxH))
    bars((1..16).map(::gray), size.width * 0.08f + boxW * 0.05f, top + boxH * 0.2f, boxW * 0.9f, boxH * 0.6f, 0.5f)
    drawRect(gray(255), Offset(size.width * 0.54f, top), Size(boxW, boxH))
    bars((239..254).map(::gray), size.width * 0.54f + boxW * 0.05f, top + boxH * 0.2f, boxW * 0.9f, boxH * 0.6f, 0.5f)
}

/** Bars at code values 235 to 254 on full white; a display that clips whites hides the last ones. */
private fun DrawScope.whiteClipping() {
    drawRect(gray(255))
    bars((235..254).map(::gray), size.width * 0.1f, size.height * 0.25f, size.width * 0.8f, size.height * 0.5f, 0.5f)
}

/** A smooth black-to-white ramp over a white-to-black one; banding shows as visible steps. */
private fun DrawScope.grayscaleRamp() {
    val half = size.height / 2f
    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), Offset.Zero, Size(size.width, half))
    drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Black)), Offset(0f, half), Size(size.width, size.height - half))
}

/** Eleven steps from 0 % to 100 %, each distinct from its neighbours on a well set display. */
private fun DrawScope.grayscaleSteps() {
    val levels = (0..10).map { gray((it * 255 + 5) / 10) }
    bars(levels, 0f, 0f, size.width, size.height * 0.7f)
    bars(levels.reversed(), 0f, size.height * 0.7f, size.width, size.height * 0.3f)
}

/**
 * A fixed 50 % patch on a background that swaps between black and white every two seconds; the
 * patch should look the same throughout when dynamic brightness is off.
 */
private fun DrawScope.dynamicBrightness(timeMillis: Long) {
    val bright = (timeMillis / 2000) % 2 == 1L
    drawRect(if (bright) Color.White else Color.Black)
    val side = min(size.width, size.height) * 0.3f
    drawRect(gray(128), Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))
}

private val PRIMARIES = listOf(
    Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF),
    Color(0xFF00FFFF), Color(0xFFFF00FF), Color(0xFFFFFF00),
)

private fun scale(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f)

/** Eleven steps from black to full for red, green, blue, cyan, magenta and yellow. */
private fun DrawScope.colorSteps() {
    val rowH = size.height / PRIMARIES.size
    PRIMARIES.forEachIndexed { r, c ->
        bars((0..10).map { scale(c, it / 10f) }, 0f, floor(r * rowH), size.width, floor((r + 1) * rowH) - floor(r * rowH))
    }
}

/** For each colour, bars at 90 % to 99 % on a 100 % field; clipped channels hide the top bars. */
private fun DrawScope.colorClipping() {
    val rowH = size.height / PRIMARIES.size
    PRIMARIES.forEachIndexed { r, c ->
        val top = floor(r * rowH)
        val h = floor((r + 1) * rowH) - top
        drawRect(c, Offset(0f, top), Size(size.width, h))
        bars((230..254 step 3).map { scale(c, it / 255f) }, size.width * 0.1f, top + h * 0.2f, size.width * 0.8f, h * 0.6f, 0.5f)
    }
}

private val BARS_75 = listOf(
    Color(0xFFBFBFBF), Color(0xFFBFBF00), Color(0xFF00BFBF), Color(0xFF00BF00),
    Color(0xFFBF00BF), Color(0xFFBF0000), Color(0xFF0000BF),
)

/** 75 % colour bars that flash on and off twice a second, for colour and tint through a filter. */
private fun DrawScope.flashingColorBars(timeMillis: Long) {
    val on = (timeMillis / 500) % 2 == 0L
    if (!on) {
        drawRect(Color.Black)
        return
    }
    bars(BARS_75, 0f, 0f, size.width, size.height * 0.67f)
    bars(BARS_75.reversed().map { scale(it, 0.5f) }, 0f, size.height * 0.67f, size.width, size.height * 0.08f)
    bars(listOf(Color(0xFF00214C), Color.White, Color(0xFF32006A), gray(16)), 0f, size.height * 0.75f, size.width, size.height * 0.25f)
}

/**
 * Edge markers at 0 %, 1 %, 2.5 % and 5 % overscan, one-pixel line groups for sharpness and a
 * centre cross; on a 1:1 panel every edge line is visible and no line rings.
 */
private fun DrawScope.sharpnessOverscan() {
    drawRect(gray(128))
    val w = size.width
    val h = size.height
    // Outermost pixel border.
    drawRect(Color.White, Offset.Zero, Size(w, h), style = Stroke(1f))
    for ((frac, color) in listOf(0.01f to Color.Red, 0.025f to Color.Green, 0.05f to Color.Blue)) {
        val dx = floor(w * frac)
        val dy = floor(h * frac)
        drawRect(color, Offset(dx, dy), Size(w - 2 * dx, h - 2 * dy), style = Stroke(2f))
    }
    // Centre cross.
    drawRect(Color.Black, Offset(floor(w / 2f), h * 0.3f), Size(1f, h * 0.4f))
    drawRect(Color.Black, Offset(w * 0.3f, floor(h / 2f)), Size(w * 0.4f, 1f))
    // One-pixel line groups: vertical and horizontal, black on white and white on black.
    val patch = floor(min(w, h) * 0.12f)
    val spots = listOf(Offset(w * 0.15f, h * 0.2f), Offset(w * 0.75f, h * 0.2f), Offset(w * 0.15f, h * 0.65f), Offset(w * 0.75f, h * 0.65f))
    spots.forEachIndexed { i, o ->
        val x0 = floor(o.x)
        val y0 = floor(o.y)
        val dark = i % 2 == 0
        drawRect(if (dark) Color.White else Color.Black, Offset(x0, y0), Size(patch, patch))
        val line = if (dark) Color.Black else Color.White
        var k = 0
        while (k < patch) {
            if (i < 2) drawRect(line, Offset(x0 + k, y0), Size(1f, patch)) else drawRect(line, Offset(x0, y0 + k), Size(patch, 1f))
            k += 2
        }
    }
}

/** A resolution chart: a grid, a large circle, diagonals and one-pixel checkerboards in the corners. */
private fun DrawScope.uhdPattern() {
    drawRect(gray(64))
    val w = size.width
    val h = size.height
    val cell = floor(h / 9f)
    var x = (w % cell) / 2f
    while (x <= w) {
        drawRect(gray(192), Offset(floor(x), 0f), Size(1f, h))
        x += cell
    }
    var y = (h % cell) / 2f
    while (y <= h) {
        drawRect(gray(192), Offset(0f, floor(y)), Size(w, 1f))
        y += cell
    }
    drawCircle(Color.White, radius = h * 0.45f, center = Offset(w / 2f, h / 2f), style = Stroke(2f))
    drawLine(Color.White, Offset(0f, 0f), Offset(w, h), strokeWidth = 1f)
    drawLine(Color.White, Offset(w, 0f), Offset(0f, h), strokeWidth = 1f)
    val patch = floor(cell * 1.5f).toInt()
    for ((cx, cy) in listOf(cell to cell, w - cell - patch to cell, cell to h - cell - patch, w - cell - patch to h - cell - patch)) {
        val x0 = floor(cx)
        val y0 = floor(cy)
        drawRect(Color.Black, Offset(x0, y0), Size(patch.toFloat(), patch.toFloat()))
        for (r in 0 until patch) {
            var c = r % 2
            while (c < patch) {
                drawRect(Color.White, Offset(x0 + c, y0 + r), Size(1f, 1f))
                c += 2
            }
        }
    }
    // Primary and secondary colour swatches along the bottom of the circle.
    bars(PRIMARIES, w * 0.3f, h * 0.62f, w * 0.4f, cell * 0.8f)
}

/**
 * A white bar crossing the screen at a constant 480 px per second, over a fixed grid; judder
 * shows as uneven steps or doubled edges.
 */
private fun DrawScope.judder(timeMillis: Long) {
    drawRect(gray(32))
    val w = size.width
    val h = size.height
    var gx = 0f
    while (gx < w) {
        drawRect(gray(80), Offset(floor(gx), 0f), Size(1f, h))
        gx += w / 16f
    }
    val barW = floor(w / 48f)
    val speed = w / 4000f // the full width in four seconds
    val travel = w + barW
    val x = floor((timeMillis * speed) % travel - barW)
    drawRect(Color.White, Offset(x, h * 0.15f), Size(barW, h * 0.7f))
}
