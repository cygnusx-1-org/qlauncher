/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The *Picture adjustment* colour matrix, as a 4 × 5 row-major
 * array in Android's `ColorMatrix` layout (channel values 0…255, offsets in the fifth column).
 *
 * The steps are applied in this order, each post-concatenated: hue, saturation, contrast,
 * brightness, tint. Pure Kotlin so it can be checked against hand-computed values.
 */
object PictureAdjust {
    /** Luma weights used by hue, saturation and tint. */
    const val LUM_R = 0.3086f
    const val LUM_G = 0.6094f
    const val LUM_B = 0.0820f

    /** Contrast factors for c = 0…100 (the widely published ColorFilterGenerator table). */
    val CONTRAST_TABLE: FloatArray = floatArrayOf(
        0f, 0.01f, 0.02f, 0.04f, 0.05f, 0.06f, 0.07f, 0.08f, 0.1f, 0.11f, 0.12f, 0.14f, 0.15f, 0.16f, 0.17f, 0.18f, 0.2f,
        0.21f, 0.22f, 0.24f, 0.25f, 0.27f, 0.28f, 0.3f, 0.32f, 0.34f, 0.36f, 0.38f, 0.4f, 0.42f, 0.44f, 0.46f, 0.48f, 0.5f,
        0.53f, 0.56f, 0.59f, 0.62f, 0.65f, 0.68f, 0.71f, 0.74f, 0.77f, 0.8f, 0.83f, 0.86f, 0.89f, 0.92f, 0.95f, 0.98f, 1.0f,
        1.06f, 1.12f, 1.18f, 1.24f, 1.3f, 1.36f, 1.42f, 1.48f, 1.54f, 1.6f, 1.66f, 1.72f, 1.78f, 1.84f, 1.9f, 1.96f, 2.0f,
        2.12f, 2.25f, 2.37f, 2.5f, 2.62f, 2.75f, 2.87f, 3.0f, 3.2f, 3.4f, 3.6f, 3.8f, 4.0f, 4.3f, 4.7f, 4.9f, 5.0f, 5.5f,
        6.0f, 6.5f, 6.8f, 7.0f, 7.3f, 7.5f, 7.8f, 8.0f, 8.4f, 8.7f, 9.0f, 9.4f, 9.6f, 9.8f, 10.0f,
    )

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** True when [m] changes nothing. */
    fun isIdentity(m: FloatArray): Boolean = m.contentEquals(identity())

    /**
     * The full matrix, or the identity when every value is neutral.
     *
     * @param hue −180…180 degrees.
     * @param saturation −100…100.
     * @param contrast −100…100.
     * @param brightness −255…255, added to R, G and B.
     * @param tint ARGB tint colour already resolved (dynamic colours replaced); 0 = none.
     * @param tintAmount 0…100.
     */
    fun matrix(hue: Int, saturation: Int, contrast: Int, brightness: Int, tint: Int, tintAmount: Int): FloatArray {
        var m = identity()
        hueMatrix(hue)?.let { m = postConcat(m, it) }
        saturationMatrix(saturation)?.let { m = postConcat(m, it) }
        contrastMatrix(contrast)?.let { m = postConcat(m, it) }
        brightnessMatrix(brightness)?.let { m = postConcat(m, it) }
        tintMatrix(tint, tintAmount)?.let { m = postConcat(m, it) }
        return m
    }

    /** Rotation about the luma axis by `h ÷ 180 × π`; null for 0. */
    fun hueMatrix(hue: Int): FloatArray? {
        val angle = hue.coerceIn(-180, 180) / 180.0 * PI
        if (angle == 0.0) return null
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        val r = LUM_R
        val g = LUM_G
        val b = LUM_B
        return floatArrayOf(
            r + c * (1 - r) + s * (-r), g + c * (-g) + s * (-g), b + c * (-b) + s * (1 - b), 0f, 0f,
            r + c * (-r) + s * 0.143f, g + c * (1 - g) + s * 0.140f, b + c * (-b) + s * (-0.283f), 0f, 0f,
            r + c * (-r) + s * (-(1 - r)), g + c * (-g) + s * g, b + c * (1 - b) + s * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** `x = 1 + (s > 0 ? 3s ÷ 100 : s ÷ 100)`; rows `lum × (1 − x)` plus `x` on the diagonal; null for 0. */
    fun saturationMatrix(saturation: Int): FloatArray? {
        val s = saturation.coerceIn(-100, 100).toFloat()
        if (s == 0f) return null
        val x = 1f + if (s > 0f) 3f * s / 100f else s / 100f
        val r = LUM_R * (1f - x)
        val g = LUM_G * (1f - x)
        val b = LUM_B * (1f - x)
        return floatArrayOf(
            r + x, g, b, 0f, 0f,
            r, g + x, b, 0f, 0f,
            r, g, b + x, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** `v = k × 127 + 127`, scale `v ÷ 127`, offset `(127 − v) × 0.5`; null for 0. */
    fun contrastMatrix(contrast: Int): FloatArray? {
        val c = contrast.coerceIn(-100, 100)
        if (c == 0) return null
        val k = if (c < 0) c / 100f else CONTRAST_TABLE[c]
        val v = k * 127f + 127f
        val scale = v / 127f
        val offset = (127f - v) * 0.5f
        return floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Adds [brightness] to R, G and B; null for 0. */
    fun brightnessMatrix(brightness: Int): FloatArray? {
        val b = brightness.coerceIn(-255, 255).toFloat()
        if (b == 0f) return null
        return floatArrayOf(
            1f, 0f, 0f, 0f, b,
            0f, 1f, 0f, 0f, b,
            0f, 0f, 1f, 0f, b,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Each output channel is `(1 − f) × channel + f × tint.channel × luma(input)`, `f = amount ÷ 100`;
     * null when the colour or the amount is 0.
     */
    fun tintMatrix(tint: Int, tintAmount: Int): FloatArray? {
        if (tint == 0 || tintAmount == 0) return null
        val f = tintAmount.coerceIn(-100, 100) / 100f
        if (f == 0f) return null
        val keep = 1f - f
        val tr = ((tint shr 16) and 0xFF) / 255f * f
        val tg = ((tint shr 8) and 0xFF) / 255f * f
        val tb = (tint and 0xFF) / 255f * f
        return floatArrayOf(
            tr * LUM_R + keep, tr * LUM_G, tr * LUM_B, 0f, 0f,
            tg * LUM_R, tg * LUM_G + keep, tg * LUM_B, 0f, 0f,
            tb * LUM_R, tb * LUM_G, tb * LUM_B + keep, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Android's `ColorMatrix.postConcat`: the result applies [first], then [then]. */
    fun postConcat(first: FloatArray, then: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var v = 0f
                for (k in 0 until 4) v += then[row * 5 + k] * first[k * 5 + col]
                if (col == 4) v += then[row * 5 + 4]
                out[row * 5 + col] = v
            }
        }
        return out
    }

    /** Applies [m] to one ARGB colour, clamping each channel to 0…255. */
    fun apply(m: FloatArray, argb: Int): Int {
        val a = ((argb ushr 24) and 0xFF).toFloat()
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        fun ch(row: Int): Int {
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * a + m[row * 5 + 4]
            return v.coerceIn(0f, 255f).toInt()
        }
        return (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}
