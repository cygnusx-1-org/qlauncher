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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.min

/**
 * The gradient shadow drawn over the wallpaper: a 240 × 135
 * bitmap, stretched with bilinear filtering, holding a vertical and a horizontal black gradient
 * combined with DARKEN.
 */
object ShadowOverlay {
    const val WIDTH = 240
    const val HEIGHT = 135

    /** Gradient colours: black, black, transparent, transparent. */
    val COLORS = intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT, Color.TRANSPARENT)

    /** Stops `0, |len|, min(1, 1.7 × |len| + 0.2), 1`; the `+ 0.2` only when `|len| > 0`. */
    fun stops(length: Float): FloatArray {
        val l = min(1f, abs(length))
        val fade = if (l > 0f) min(1f, 1.7f * l + 0.2f) else 0f
        return floatArrayOf(0f, l, fade, 1f)
    }

    /** The overlay is off when the opacity is 0 or both lengths are 0. */
    fun isVisible(hLength: Float, vLength: Float, opacity: Float): Boolean =
        opacity > 0f && (hLength != 0f || vLength != 0f)

    /**
     * Start and end points of one gradient in the overlay bitmap, as (x0, y0, x1, y1).
     * Vertical: length ≥ 0 starts at the bottom, < 0 at the top. Horizontal: ≥ 0 starts at the
     * left, < 0 at the right.
     */
    fun vertical(length: Float, width: Float = WIDTH.toFloat(), height: Float = HEIGHT.toFloat()): FloatArray =
        if (length >= 0f) floatArrayOf(0f, height, 0f, 0f) else floatArrayOf(0f, 0f, 0f, height)

    fun horizontal(length: Float, width: Float = WIDTH.toFloat(), height: Float = HEIGHT.toFloat()): FloatArray =
        if (length >= 0f) floatArrayOf(0f, 0f, width, 0f) else floatArrayOf(width, 0f, 0f, 0f)

    /** Draws the overlay; null when it is off. Alpha and tint are applied when it is drawn. */
    fun render(hLength: Float, vLength: Float): Bitmap? {
        if (hLength == 0f && vLength == 0f) return null
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var first = true
        fun draw(points: FloatArray, length: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(points[0], points[1], points[2], points[3], COLORS, stops(length), Shader.TileMode.CLAMP)
            if (!first) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
            first = false
        }
        if (vLength != 0f) draw(vertical(vLength), vLength)
        if (hLength != 0f) draw(horizontal(hLength), hLength)
        return bitmap
    }
}
