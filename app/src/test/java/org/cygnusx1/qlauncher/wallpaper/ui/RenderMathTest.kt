/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import androidx.compose.ui.unit.IntSize
import org.cygnusx1.qlauncher.wallpaper.engine.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RenderMathTest {
    @Test
    fun cropTakesTheCentredPart() {
        val g = StillGeometry.of(4000, 2000, DisplayMode.CROP, 1920, 1080)
        assertEquals(Box4(222, 0, 3778, 2000), g.src)
        assertEquals(Box4(0, 0, 1920, 1080), g.dst)
    }

    @Test
    fun fitCentresTheWholePicture() {
        val g = StillGeometry.of(1000, 1000, DisplayMode.FIT_CENTER, 1920, 1080)
        assertEquals(Box4(0, 0, 1000, 1000), g.src)
        assertEquals(Box4(420, 0, 1500, 1080), g.dst)
        assertEquals(Box4(0, 218, 1000, 781), g.cover)
    }

    @Test
    fun stretchFillsTheView() {
        val g = StillGeometry.of(800, 600, DisplayMode.STRETCH, 1920, 1080)
        assertEquals(Box4(0, 0, 800, 600), g.src)
        assertEquals(Box4(0, 0, 1920, 1080), g.dst)
    }

    @Test
    fun videoBoxCoversOrFits() {
        assertEquals(2592 to 1080, videoBox(IntSize(1920, 800), IntSize(1920, 1080), DisplayMode.CROP))
        assertEquals(1920 to 800, videoBox(IntSize(1920, 800), IntSize(1920, 1080), DisplayMode.FIT_CENTER))
        assertEquals(1920 to 1080, videoBox(IntSize(1920, 800), IntSize(1920, 1080), DisplayMode.STRETCH))
        assertEquals(1920 to 1080, videoBox(IntSize.Zero, IntSize(1920, 1080), DisplayMode.CROP))
    }

    @Test
    fun transitionsResolveAndRandomPicksAnAnimatedOne() {
        assertEquals(TransitionKind.NONE, TransitionKind.resolve(0))
        assertEquals(TransitionKind.ZOOM, TransitionKind.resolve(3))
        assertEquals(200, TransitionKind.NONE.durationMs)
        assertEquals(800, TransitionKind.VERTICAL_SLIDE.durationMs)
        val random = Random(7)
        repeat(50) {
            val k = TransitionKind.resolve(1, random)
            assertTrue(k in setOf(TransitionKind.FADE, TransitionKind.ZOOM, TransitionKind.HORIZONTAL_SLIDE, TransitionKind.VERTICAL_SLIDE))
        }
    }

    @Test
    fun videoPositionsFollowTheRules() {
        assertTrue(VideoPositionMemory.shouldKeep(10_000, 60_000))
        assertFalse(VideoPositionMemory.shouldKeep(55_000, 60_000))
        assertFalse(VideoPositionMemory.shouldKeep(5_000, 29_000))
        val m = VideoPositionMemory()
        m.leave("a", 10_000, 60_000)
        m.leave("b", 10_000, 20_000)
        assertNull(m.take("b"))
        m.keepOnly("a")
        assertEquals(10_000L, m.take("a"))
        assertNull(m.take("a"))
    }
}
