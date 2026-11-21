/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureAdjustTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun channels(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** Channels may land one below the exact value through float rounding. */
    private fun assertNear(expected: Triple<Int, Int, Int>, actual: Triple<Int, Int, Int>) {
        val ok = kotlin.math.abs(expected.first - actual.first) <= 1 &&
            kotlin.math.abs(expected.second - actual.second) <= 1 &&
            kotlin.math.abs(expected.third - actual.third) <= 1
        assertTrue("expected $expected, got $actual", ok)
    }

    private fun apply(hue: Int = 0, sat: Int = 0, con: Int = 0, bri: Int = 0, tint: Int = 0, amount: Int = 0, color: Int) =
        channels(PictureAdjust.apply(PictureAdjust.matrix(hue, sat, con, bri, tint, amount), color))

    @Test
    fun neutralValuesGiveTheIdentity() {
        val m = PictureAdjust.matrix(0, 0, 0, 0, 0, 0)
        assertTrue(PictureAdjust.isIdentity(m))
        assertNull(PictureAdjust.tintMatrix(0xFFFF0000.toInt(), 0))
        assertNull(PictureAdjust.tintMatrix(0, 50))
    }

    @Test
    fun brightnessIsAddedToEachChannel() {
        // 0x10 + 50 = 66, 0x20 + 50 = 82, 0x30 + 50 = 98.
        assertNear(Triple(66, 82, 98), apply(bri = 50, color = rgb(0x10, 0x20, 0x30)))
        assertNear(Triple(0, 0, 0), apply(bri = -255, color = rgb(200, 100, 50)))
    }

    @Test
    fun negativeContrastUsesTheLinearFactor() {
        // k = -0.5, v = 63.5, scale 0.5, offset 31.75: 200 -> 131.75, 0 -> 31.75.
        assertNear(Triple(131, 31, 81), apply(con = -50, color = rgb(200, 0, 100)))
    }

    @Test
    fun positiveContrastUsesTheTable() {
        // T[50] = 1.0, v = 254, scale 2, offset -63.5: 100 -> 136.5.
        assertNear(Triple(136, 0, 255), apply(con = 50, color = rgb(100, 10, 200)))
        // T[100] = 10: v = 1397, scale 11, offset -635: 60 -> 25.
        assertEquals(25, apply(con = 100, color = rgb(60, 60, 60)).first)
        assertEquals(101, PictureAdjust.CONTRAST_TABLE.size)
    }

    @Test
    fun fullDesaturationGivesTheLuma() {
        // Red 255 -> 0.3086 * 255 = 78.69 in every channel.
        assertNear(Triple(78, 78, 78), apply(sat = -100, color = rgb(255, 0, 0)))
    }

    @Test
    fun saturationKeepsGreys() {
        // x = 4 for s = 100; every row sums to 1, so a grey stays grey.
        assertNear(Triple(100, 100, 100), apply(sat = 100, color = rgb(100, 100, 100)))
        val m = PictureAdjust.saturationMatrix(100)!!
        assertEquals(0.3086f * -3f + 4f, m[0], 1e-5f)
        assertEquals(0.6094f * -3f, m[1], 1e-5f)
    }

    @Test
    fun hueRotationBy180TurnsRedToCyan() {
        // cos = -1, sin = 0: R' = (2 lr - 1) R < 0, G' = 2 lr R = 157.4, B' = 2 lr R = 157.4.
        assertNear(Triple(0, 157, 157), apply(hue = 180, color = rgb(255, 0, 0)))
    }

    @Test
    fun hueRotationBy90MatchesTheFormula() {
        val m = PictureAdjust.hueMatrix(90)!!
        // cos 0, sin 1: row 0 = lr - lr, lg - lg, lb + 1 - lb.
        assertEquals(0f, m[0], 1e-5f)
        assertEquals(-0f, m[1], 1e-5f)
        assertEquals(1f, m[2], 1e-5f)
        // Row 1 = lr + 0.143, lg + 0.140, lb - 0.283.
        assertEquals(0.3086f + 0.143f, m[5], 1e-5f)
        assertEquals(0.6094f + 0.140f, m[6], 1e-5f)
        assertEquals(0.0820f - 0.283f, m[7], 1e-5f)
        // Row 2 = lr - (1 - lr), lg + lg, lb + lb.
        assertEquals(0.3086f - 0.6914f, m[10], 1e-5f)
        assertEquals(2 * 0.6094f, m[11], 1e-5f)
        assertEquals(2 * 0.0820f, m[12], 1e-5f)
    }

    @Test
    fun fullTintReplacesTheColourByTintTimesLuma() {
        val red = 0xFFFF0000.toInt()
        assertNear(Triple(255, 0, 0), apply(tint = red, amount = 100, color = rgb(255, 255, 255)))
        assertNear(Triple(100, 0, 0), apply(tint = red, amount = 100, color = rgb(100, 100, 100)))
    }

    @Test
    fun halfTintMixes() {
        // f = 0.5, blue tint on (200, 100, 0): R' = 100, G' = 50, B' = 0.5 * (0.3086*200 + 0.6094*100) = 61.33.
        assertNear(Triple(100, 50, 61), apply(tint = 0xFF0000FF.toInt(), amount = 50, color = rgb(200, 100, 0)))
    }

    @Test
    fun stepsApplyInOrder() {
        // Contrast then brightness: 100 * 2 - 63.5 + 10 = 146.5. The other order would give 156.5.
        assertEquals(146, apply(con = 50, bri = 10, color = rgb(100, 100, 100)).first)
    }

    @Test
    fun postConcatAppliesFirstThenSecond() {
        val first = PictureAdjust.brightnessMatrix(10)!!
        val then = PictureAdjust.contrastMatrix(50)!!
        val m = PictureAdjust.postConcat(first, then)
        // (x + 10) * 2 - 63.5 = 2x - 43.5.
        assertArrayEquals(floatArrayOf(2f, 0f, 0f, 0f, -43.5f), m.copyOfRange(0, 5), 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 1e-6f)
    }
}
