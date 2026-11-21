/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Target

/**
 * The seed colour of a wallpaper: the picture scaled to at most
 * 64 × 36, an androidx Palette with only the Light Vibrant and Vibrant targets, Light Vibrant
 * first.
 */
object SeedColors {
    const val SAMPLE_WIDTH = 64
    const val SAMPLE_HEIGHT = 36

    fun extract(bitmap: Bitmap): Int? = runCatching {
        val (w, h) = DecodeSize.fitInside(bitmap.width, bitmap.height, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        val small = if (bitmap.width == w && bitmap.height == h) bitmap else bitmap.scale(w, h)
        val palette = Palette.from(small)
            .clearTargets()
            .addTarget(Target.LIGHT_VIBRANT)
            .addTarget(Target.VIBRANT)
            .resizeBitmapArea(SAMPLE_WIDTH * SAMPLE_HEIGHT)
            .generate()
        val swatch = palette.getSwatchForTarget(Target.LIGHT_VIBRANT) ?: palette.getSwatchForTarget(Target.VIBRANT)
        if (small !== bitmap) small.recycle()
        swatch?.rgb
    }.getOrNull()
}
