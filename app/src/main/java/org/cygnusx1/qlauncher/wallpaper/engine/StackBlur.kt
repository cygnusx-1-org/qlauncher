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
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap

/**
 * A stack blur on ARGB pixels: two passes (rows, then columns) of a triangle-weighted moving
 * average of width `2r + 1`, updated incrementally so the cost does not depend on the radius.
 * Runs on any API level, unlike RenderEffect (API 31+).
 */
object StackBlur {
    /** Blurs [pixels] (`width × height`, ARGB) in place with [radius]. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width <= 0 || height <= 0) return
        val r = radius
        val line = IntArray(maxOf(width, height))
        val result = IntArray(maxOf(width, height))
        // Rows.
        for (y in 0 until height) {
            val off = y * width
            for (x in 0 until width) line[x] = pixels[off + x]
            pass(line, width, r, result)
            for (x in 0 until width) pixels[off + x] = result[x]
        }
        // Columns.
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = pixels[y * width + x]
            pass(line, height, r, result)
            for (y in 0 until height) pixels[y * width + x] = result[y]
        }
    }

    /**
     * One triangle-weighted pass over `src[0 until n]`, edges clamped. Weights are
     * `r + 1 − |d|` for offsets `d` in `−r..r`, total `(r + 1)²`.
     */
    private fun pass(src: IntArray, n: Int, r: Int, result: IntArray) {
        val div = (r + 1) * (r + 1)
        // Sums of the weighted window, and the sums of its rising (in) and falling (out) halves.
        var sumA = 0L; var sumR = 0L; var sumG = 0L; var sumB = 0L
        var inA = 0L; var inR = 0L; var inG = 0L; var inB = 0L
        var outA = 0L; var outR = 0L; var outG = 0L; var outB = 0L
        fun at(i: Int): Int = src[i.coerceIn(0, n - 1)]
        // Initial window centred on 0.
        for (d in -r..r) {
            val p = at(d)
            val w = (r + 1 - kotlin.math.abs(d)).toLong()
            val a = (p ushr 24) and 0xFF; val rr = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            sumA += a * w; sumR += rr * w; sumG += g * w; sumB += b * w
            if (d <= 0) { outA += a; outR += rr; outG += g; outB += b } else { inA += a; inR += rr; inG += g; inB += b }
        }
        // `out` holds offsets −r..0 (they lose weight as the centre moves right), `in` holds 1..r.
        for (i in 0 until n) {
            result[i] = (((sumA / div).toInt() and 0xFF) shl 24) or
                (((sumR / div).toInt() and 0xFF) shl 16) or
                (((sumG / div).toInt() and 0xFF) shl 8) or
                ((sumB / div).toInt() and 0xFF)
            // Move the centre from i to i + 1.
            sumA -= outA; sumR -= outR; sumG -= outG; sumB -= outB
            val leaving = at(i - r)
            outA -= (leaving ushr 24) and 0xFF; outR -= (leaving shr 16) and 0xFF; outG -= (leaving shr 8) and 0xFF; outB -= leaving and 0xFF
            val entering = at(i + r + 1)
            inA += (entering ushr 24) and 0xFF; inR += (entering shr 16) and 0xFF; inG += (entering shr 8) and 0xFF; inB += entering and 0xFF
            sumA += inA; sumR += inR; sumG += inG; sumB += inB
            val centre = at(i + 1)
            val ca = (centre ushr 24) and 0xFF; val cr = (centre shr 16) and 0xFF; val cg = (centre shr 8) and 0xFF; val cb = centre and 0xFF
            outA += ca; outR += cr; outG += cg; outB += cb
            inA -= ca; inR -= cr; inG -= cg; inB -= cb
        }
    }

    /**
     * Scales [src] (region [srcRect], whole bitmap when null) into a `width ÷ downSample` ×
     * `height ÷ downSample` bitmap and blurs it with [radius]. The result is meant to be drawn
     * stretched over `width × height` with bilinear filtering.
     */
    fun downSampled(src: Bitmap, srcRect: Rect?, width: Int, height: Int, downSample: Int, radius: Int): Bitmap {
        val w = (width / downSample.coerceAtLeast(1)).coerceAtLeast(1)
        val h = (height / downSample.coerceAtLeast(1)).coerceAtLeast(1)
        val small = createBitmap(w, h)
        Canvas(small).drawBitmap(src, srcRect, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        blurBitmap(small, radius)
        return small
    }

    /** Blurs a mutable ARGB_8888 [bitmap] in place. */
    fun blurBitmap(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        blur(pixels, w, h, radius)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
