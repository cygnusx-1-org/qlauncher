/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.utils.ColorUtils
import org.cygnusx1.qlauncher.settings.ColorKey
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Material You style palette seeded from the wallpaper.
 *
 * Shades follow Android's system palette naming: shade 100 is tone 90, 900 is tone 10.
 *
 * @property seed the seed colour, or null when no wallpaper colour is known yet.
 */
@Immutable
class DynamicPalette private constructor(val seed: Int?, private val palette: TonalPalette?) {

    /** Tone `t` (0..100) of the accent palette, or null without a seed. */
    fun tone(t: Int): Int? = palette?.tone(t)

    /** Android shade `n` (100..900) of the accent palette. */
    fun shade(n: Int): Int? = tone(100 - n / 10)

    /**
     * Resolves a stored colour (see [ColorKey]):
     * - [ColorKey.UNSET] gives [fallback];
     * - a colour with its lowest bit set is replaced by the palette shade of the same lightness,
     *   keeping its alpha;
     * - any other colour is used as it is.
     */
    fun resolve(stored: Int, fallback: Color): Color {
        if (stored == ColorKey.UNSET) return fallback
        if (stored and 1 == 0 || palette == null) return Color(stored)
        val alpha = stored ushr 24
        val lstar = ColorUtils.lstarFromArgb(stored or 0xFF000000.toInt())
        val index = (10 - lstar / 10).roundToInt().coerceIn(1, 9)
        val toned = shade(index * 100) ?: return Color(stored)
        return Color((toned and 0x00FFFFFF) or (alpha shl 24))
    }

    override fun equals(other: Any?): Boolean = other is DynamicPalette && other.seed == seed
    override fun hashCode(): Int = seed ?: 0

    companion object {
        /** No wallpaper colour yet. */
        val NONE = DynamicPalette(null, null)

        /**
         * A palette from [seed]: the seed's hue, with the chroma raised to at least 48 (Monet's
         * accent 1).
         */
        fun fromSeed(seed: Int): DynamicPalette {
            val hct = Hct.fromInt(seed)
            return DynamicPalette(seed, TonalPalette.fromHueAndChroma(hct.hue, max(48.0, hct.chroma)))
        }

        /**
         * True when [a] and [b] differ in the top four bits of any channel; the seed is replaced
         * only then, so tiny changes do not recolour the UI.
         */
        fun differs(a: Int, b: Int): Boolean = (a and 0x00F0F0F0) != (b and 0x00F0F0F0)
    }
}

/** The theme colours for one moment: accent and its container, derived from the palette. */
@Immutable
data class QColors(
    val accent: Color,
    val accentContainer: Color,
    val palette: DynamicPalette,
) {
    companion object {
        /**
         * @param storedAccent the *Accent color* setting (unset = follow the wallpaper).
         */
        fun from(palette: DynamicPalette, storedAccent: Int): QColors {
            val accent = when {
                storedAccent == ColorKey.UNSET -> palette.shade(400)?.let(::Color) ?: QPalette.DefaultAccent
                else -> palette.resolve(storedAccent, QPalette.DefaultAccent)
            }
            val hct = Hct.fromInt(accent.toArgb())
            val container = Color(TonalPalette.fromHueAndChroma(hct.hue, hct.chroma.coerceAtMost(36.0)).tone(30))
            return QColors(accent, container, palette)
        }

        val DEFAULT = from(DynamicPalette.NONE, ColorKey.UNSET)
    }
}
