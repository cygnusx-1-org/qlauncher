/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeSizeTest {
    @Test
    fun scalesDownJustEnoughToCoverTheScreen() {
        assertEquals(1920 to 1440, DecodeSize.target(4000, 3000, 1920, 1080))
        assertEquals(1920 to 1080, DecodeSize.target(3840, 2160, 1920, 1080))
    }

    @Test
    fun neverUpscales() {
        assertEquals(1000 to 500, DecodeSize.target(1000, 500, 1920, 1080))
    }

    @Test
    fun capsTheLongestSide() {
        assertEquals(4096 to 205, DecodeSize.target(20000, 1000, 1920, 1080))
        assertEquals(410 to 4096, DecodeSize.target(3000, 30000, 1920, 1080))
    }

    @Test
    fun fitInsideKeepsTheAspect() {
        assertEquals(64 to 36, DecodeSize.fitInside(1920, 1080, 64, 36))
        assertEquals(24 to 36, DecodeSize.fitInside(1000, 1500, 64, 36))
        assertEquals(10 to 5, DecodeSize.fitInside(10, 5, 64, 36))
    }
}
