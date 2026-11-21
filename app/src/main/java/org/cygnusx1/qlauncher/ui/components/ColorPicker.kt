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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.ui.settings.SettingsSwitch
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The colour picker's model: 30 hues at 12° steps, grey and
 * transparent; a luminance that mixes the swatch towards white or black; an alpha; and the
 * "dynamic" flag carried in the lowest bit.
 */
object ColorPickerModel {
    const val COLUMNS = 8
    const val GREY = 0xFF888888.toInt()
    const val TRANSPARENT_INDEX = 31
    const val GREY_INDEX = 30

    val swatches: List<Int> = (0 until 30).map { i -> hueToRgb(12f * i) } + GREY + 0

    /** Opaque colour of a hue at full saturation and value. */
    fun hueToRgb(hue: Float): Int {
        val h = (hue % 360f) / 60f
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, x, 0f)
            1 -> Triple(x, 1f, 0f)
            2 -> Triple(0f, 1f, x)
            3 -> Triple(0f, x, 1f)
            4 -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        fun c(v: Float) = (v * 255f).roundToInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Mixes [rgb] towards white (v > 0) or black (v < 0) by |v| / 100. */
    fun mix(rgb: Int, luminance: Int): Int {
        val f = abs(luminance) / 100f
        val target = if (luminance >= 0) 255 else 0
        fun ch(c: Int) = (c + (target - c) * f).roundToInt().coerceIn(0, 255)
        val r = ch((rgb shr 16) and 0xFF)
        val g = ch((rgb shr 8) and 0xFF)
        val b = ch(rgb and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The stored value for a choice. */
    fun compose(swatch: Int, luminance: Int, alpha: Int, dynamic: Boolean): Int {
        if (swatch == TRANSPARENT_INDEX) return 0
        val rgb = mix(swatches[swatch], luminance)
        val c = (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
        if (!dynamic) return c and 1.inv()
        // 0xFFFFFFFF is ColorKey.UNSET; dynamic white keeps the bit but drops the next one.
        val d = c or 1
        return if (d == ColorKey.UNSET) d and 2.inv() else d
    }

    /** The swatch, luminance and alpha closest to a stored colour. */
    fun decompose(stored: Int?): Triple<Int, Int, Int> {
        if (stored == null || stored == ColorKey.UNSET) return Triple(GREY_INDEX, 0, 255)
        if (stored == 0) return Triple(TRANSPARENT_INDEX, 0, 255)
        val alpha = stored ushr 24
        var best = Triple(GREY_INDEX, 0, alpha)
        var bestD = Long.MAX_VALUE
        for (s in 0 until 31) {
            for (l in -100..100 step 5) {
                val c = mix(swatches[s], l)
                val d = dist(c, stored)
                if (d < bestD) {
                    bestD = d
                    best = Triple(s, l, alpha)
                }
            }
        }
        return best
    }

    private fun dist(a: Int, b: Int): Long {
        fun sq(x: Int) = (x * x).toLong()
        return sq(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            sq(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            sq((a and 0xFF) - (b and 0xFF))
    }
}

private enum class PickerFocus { Swatches, Luminance, Opacity, Dynamic, Cancel, Ok }

/**
 * The colour picker side panel. Nothing applies until OK; BACK is Cancel. It opens with focus on
 * Luminance; UP goes to the swatches, DOWN to Opacity, the switch, then Cancel and OK.
 *
 * @param initial the stored colour (may be [ColorKey.UNSET]).
 * @param allowDynamic whether the *Prefer dynamic color* switch is offered.
 * @param onResult the new stored colour.
 */
@Composable
fun ColorPickerPanel(
    initial: Int,
    allowDynamic: Boolean,
    onResult: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val start = remember(initial) { ColorPickerModel.decompose(initial) }
    var swatch by remember { mutableIntStateOf(start.first) }
    var luminance by remember { mutableIntStateOf(start.second) }
    var alpha by remember { mutableIntStateOf(start.third) }
    var dynamic by remember { mutableStateOf(initial == ColorKey.UNSET || (initial != 0 && initial and 1 == 1)) }
    var focus by remember { mutableStateOf(PickerFocus.Luminance) }
    var bubble by remember { mutableStateOf<PickerFocus?>(null) }
    var bubbleStamp by remember { mutableLongStateOf(0L) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(bubbleStamp) {
        if (bubble != null) {
            delay(1500)
            bubble = null
        }
    }
    val transparent = swatch == ColorPickerModel.TRANSPARENT_INDEX
    val order = buildList {
        add(PickerFocus.Swatches)
        if (!transparent) {
            add(PickerFocus.Luminance)
            add(PickerFocus.Opacity)
        }
        if (allowDynamic) add(PickerFocus.Dynamic)
        add(PickerFocus.Cancel)
        add(PickerFocus.Ok)
    }

    fun vertical(d: Int) {
        if (focus == PickerFocus.Swatches) {
            val row = swatch / ColorPickerModel.COLUMNS + d
            if (row in 0..3) {
                swatch = row * ColorPickerModel.COLUMNS + swatch % ColorPickerModel.COLUMNS
                return
            }
            if (d < 0) return
        }
        val flat = order.map { if (it == PickerFocus.Ok) PickerFocus.Cancel else it }.distinct()
        val pos = flat.indexOf(if (focus == PickerFocus.Ok) PickerFocus.Cancel else focus)
        val next = (pos + d).coerceIn(0, flat.lastIndex)
        focus = flat[next]
        if (focus == PickerFocus.Swatches && d < 0) {
            swatch = 3 * ColorPickerModel.COLUMNS + swatch % ColorPickerModel.COLUMNS
        }
    }

    fun horizontal(d: Int) {
        when (focus) {
            PickerFocus.Swatches -> {
                val col = swatch % ColorPickerModel.COLUMNS + d
                if (col in 0 until ColorPickerModel.COLUMNS) swatch += d
            }
            PickerFocus.Luminance -> {
                luminance = (luminance + 5 * d).coerceIn(-100, 100)
                bubble = PickerFocus.Luminance
                bubbleStamp = System.nanoTime()
            }
            PickerFocus.Opacity -> {
                alpha = (alpha + 5 * d).coerceIn(0, 255)
                bubble = PickerFocus.Opacity
                bubbleStamp = System.nanoTime()
            }
            PickerFocus.Cancel -> if (d > 0) focus = PickerFocus.Ok
            PickerFocus.Ok -> if (d < 0) focus = PickerFocus.Cancel
            PickerFocus.Dynamic -> Unit
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val down = e.type == KeyEventType.KeyDown
        return when (e.key) {
            Key.DirectionUp -> { if (down) vertical(-1); true }
            Key.DirectionDown -> { if (down) vertical(1); true }
            Key.DirectionLeft -> { if (down) horizontal(-1); true }
            Key.DirectionRight -> { if (down) horizontal(1); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (e.type == KeyEventType.KeyUp) {
                    when (focus) {
                        PickerFocus.Dynamic -> dynamic = !dynamic
                        PickerFocus.Cancel -> onCancel()
                        PickerFocus.Ok -> onResult(ColorPickerModel.compose(swatch, luminance, alpha, allowDynamic && dynamic))
                        PickerFocus.Swatches -> focus = PickerFocus.Luminance.takeIf { !transparent } ?: PickerFocus.Ok
                        else -> focus = PickerFocus.Ok
                    }
                }
                true
            }
            Key.Back, Key.Escape -> { if (e.type == KeyEventType.KeyUp) onCancel(); true }
            else -> false
        }
    }

    val preview = Color(ColorPickerModel.compose(swatch, luminance, alpha, false))
    PanelCard(Modifier.focusRequester(fr).onKeyEvent { onKey(it) }.focusable().testTag("color_picker")) {
        // Swatches: 8 x 4, 28 dp at 35.5 dp pitch from (20, 20).
        ColorPickerModel.swatches.forEachIndexed { i, c ->
            val x = 20.dp + 35.5.dp * (i % ColorPickerModel.COLUMNS)
            val y = 20.dp + 35.5.dp * (i / ColorPickerModel.COLUMNS)
            val selected = i == swatch
            Box(
                Modifier
                    .offset(x, y)
                    .size(28.dp)
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, if (focus == PickerFocus.Swatches) Color.White else Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    )
                    .padding(if (selected) 3.dp else 0.dp)
                    .clip(RoundedCornerShape(if (selected) 6.dp else 8.dp))
                    .background(if (i == ColorPickerModel.TRANSPARENT_INDEX) Color.White else Color(c))
                    .testTag("swatch_$i"),
                contentAlignment = Alignment.Center,
            ) {
                if (i == ColorPickerModel.TRANSPARENT_INDEX) MaterialIcon("close", size = 24.dp, tint = Color.Black)
            }
        }
        // Preview.
        Box(
            Modifier
                .offset(132.dp, 168.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .testTag("color_preview"),
        ) {
            Checkerboard()
            Box(Modifier.matchParentSizeCompat().background(preview))
        }
        if (!transparent) {
            PickerSlider(
                label = stringResource(R.string.color_luminance),
                y = 232.dp,
                position = (luminance + 100) / 200f,
                origin = 0.5f,
                stops = 41,
                bubble = if (bubble == PickerFocus.Luminance) luminance.toString() else null,
                focused = focus == PickerFocus.Luminance,
            )
            PickerSlider(
                label = stringResource(R.string.color_opacity),
                y = 298.dp,
                position = alpha / 255f,
                origin = 0f,
                stops = 52,
                bubble = if (bubble == PickerFocus.Opacity) alpha.toString() else null,
                focused = focus == PickerFocus.Opacity,
            )
        }
        if (allowDynamic) {
            Box(
                Modifier
                    .offset(16.dp, 364.dp)
                    .width(288.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (focus == PickerFocus.Dynamic) QPalette.PanelFocusedRow else Color.Transparent)
                    .padding(horizontal = 0.dp, vertical = 6.dp),
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(end = 64.dp)) {
                    Text(stringResource(R.string.color_prefer_dynamic), color = Color.White, style = QTheme.type.panelRow)
                    Text(stringResource(R.string.color_prefer_dynamic_desc), color = Color.White.copy(alpha = 0.6f), style = QTheme.type.rowDescription)
                }
                SettingsSwitch(dynamic, true, Modifier.align(Alignment.CenterEnd))
            }
        }
        PickerButton(stringResource(R.string.settings_cancel), 71.dp, focus == PickerFocus.Cancel, "color_cancel")
        PickerButton(stringResource(R.string.settings_ok), 161.dp, focus == PickerFocus.Ok, "color_ok")
    }
}

@Composable
private fun PickerSlider(label: String, y: androidx.compose.ui.unit.Dp, position: Float, origin: Float, stops: Int, bubble: String?, focused: Boolean) {
    Box(
        Modifier
            .offset(0.dp, y)
            .width(PanelDimens.Width)
            .background(if (focused) QPalette.PanelFocusedRow else Color.Transparent),
    ) {
        Text(label, color = Color.White, style = QTheme.type.panelRow, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        StepSlider(
            position = position,
            origin = origin,
            stops = stops,
            bubble = bubble,
            modifier = Modifier
                .padding(start = 30.dp, end = 30.dp, top = 32.dp, bottom = 4.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun PickerButton(text: String, x: androidx.compose.ui.unit.Dp, focused: Boolean, tag: String) {
    Box(
        Modifier
            .offset(x, 443.5.dp)
            .size(88.dp, 47.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f))
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (focused) Color(0xFF1B1C1E) else Color.White, style = QTheme.type.panelRow)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Checkerboard() {
    Canvas(Modifier.matchParentSize()) {
        val cell = 8.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        drawRect(Color.White)
        for (r in 0 until rows) for (c in 0 until cols) {
            if ((r + c) % 2 == 0) drawRect(Color(0xFFCCCCCC), Offset(c * cell, r * cell), Size(cell, cell))
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().size(56.dp))

/** The colour a stored value shows as, for swatches in rows. */
fun storedColorArgb(stored: Int): Int = if (stored == ColorKey.UNSET) Color.Transparent.toArgb() else stored
