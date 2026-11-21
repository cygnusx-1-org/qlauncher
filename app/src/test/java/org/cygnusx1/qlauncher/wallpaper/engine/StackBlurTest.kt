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
import org.junit.Assert.assertTrue
import org.junit.Test

class StackBlurTest {
    @Test
    fun uniformImageStaysUniform() {
        val px = IntArray(20 * 10) { 0xFF336699.toInt() }
        StackBlur.blur(px, 20, 10, 5)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun radiusZeroChangesNothing() {
        val px = IntArray(9) { it * 0x010101 or (0xFF shl 24) }
        val copy = px.copyOf()
        StackBlur.blur(px, 3, 3, 0)
        assertArrayEquals(copy, px)
    }

    @Test
    fun impulseSpreadsSymmetricallyWithTriangleWeights() {
        // One white pixel in the middle of a black 1-row image, radius 2: weights 1,2,3,2,1 of 9.
        val w = 9
        val px = IntArray(w) { 0xFF000000.toInt() }
        px[4] = 0xFFFFFFFF.toInt()
        StackBlur.blur(px, w, 1, 2)
        fun red(i: Int) = (px[i] shr 16) and 0xFF
        assertEquals(255 * 3 / 9, red(4))
        assertEquals(255 * 2 / 9, red(3))
        assertEquals(255 * 2 / 9, red(5))
        assertEquals(255 * 1 / 9, red(2))
        assertEquals(255 * 1 / 9, red(6))
        assertEquals(0, red(1))
        assertEquals(0, red(7))
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }
}
