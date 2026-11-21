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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowOverlayTest {
    @Test
    fun stopsFollowTheFormula() {
        assertArrayEquals(floatArrayOf(0f, 0.2f, 0.54f, 1f), ShadowOverlay.stops(0.2f), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0.1f, 0.37f, 1f), ShadowOverlay.stops(0.1f), 1e-5f)
        // 1.7 * 0.5 + 0.2 = 1.05, capped at 1.
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1f), ShadowOverlay.stops(-0.5f), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 1f), ShadowOverlay.stops(1f), 1e-5f)
    }

    @Test
    fun zeroLengthHasNoFadeOffset() {
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), ShadowOverlay.stops(0f), 0f)
    }

    @Test
    fun directionsFollowTheSign() {
        val w = ShadowOverlay.WIDTH.toFloat()
        val h = ShadowOverlay.HEIGHT.toFloat()
        // Vertical length >= 0 starts at the bottom, < 0 at the top.
        assertArrayEquals(floatArrayOf(0f, h, 0f, 0f), ShadowOverlay.vertical(0.1f), 0f)
        assertArrayEquals(floatArrayOf(0f, h, 0f, 0f), ShadowOverlay.vertical(0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, h), ShadowOverlay.vertical(-0.1f), 0f)
        // Horizontal length >= 0 starts at the left, < 0 at the right.
        assertArrayEquals(floatArrayOf(0f, 0f, w, 0f), ShadowOverlay.horizontal(0.2f), 0f)
        assertArrayEquals(floatArrayOf(w, 0f, 0f, 0f), ShadowOverlay.horizontal(-0.2f), 0f)
    }

    @Test
    fun offWhenOpacityOrBothLengthsAreZero() {
        assertFalse(ShadowOverlay.isVisible(0.2f, 0.1f, 0f))
        assertFalse(ShadowOverlay.isVisible(0f, 0f, 0.3f))
        assertTrue(ShadowOverlay.isVisible(0f, -0.1f, 0.3f))
        assertTrue(ShadowOverlay.isVisible(0.2f, 0.1f, 0.3f))
    }
}
