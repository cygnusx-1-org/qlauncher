/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceTest {
    private val auto = DisplayMode.AUTOMATIC.ordinal
    private val skip = Acceptance.WRONG_FORMAT_SKIP
    private val blur = DisplayMode.BLUR.ordinal

    @Test
    fun wrongFormatIsPortraitSquareOrNarrow() {
        assertTrue(Acceptance.isWrongFormat(1080, 1920, 800))
        assertTrue(Acceptance.isWrongFormat(1000, 1000, 800))
        assertTrue(Acceptance.isWrongFormat(700, 400, 800))
        assertFalse(Acceptance.isWrongFormat(800, 450, 800))
        assertFalse(Acceptance.isWrongFormat(1920, 1080, 800))
        // Unknown sizes are accepted.
        assertFalse(Acceptance.isWrongFormat(0, 0, 800))
    }

    @Test
    fun automaticUsesTheWallpapersOwnModeElseCrop() {
        assertEquals(DisplayMode.CROP, Acceptance.baseMode(auto, WallpaperDisplayModes.DEFAULT))
        assertEquals(DisplayMode.BLUR, Acceptance.baseMode(auto, WallpaperDisplayModes.BLUR))
        assertEquals(DisplayMode.FIT_CENTER, Acceptance.baseMode(auto, WallpaperDisplayModes.FIT_CENTER))
        assertEquals(DisplayMode.FIT_CENTER_BLURRED, Acceptance.baseMode(auto, WallpaperDisplayModes.FIT_CENTER_BLURRED_BG))
        // An explicit setting wins.
        assertEquals(DisplayMode.STRETCH, Acceptance.baseMode(DisplayMode.STRETCH.ordinal, WallpaperDisplayModes.BLUR))
    }

    @Test
    fun goodPicturesUseTheDisplayMode() {
        assertEquals(Placement.Draw(DisplayMode.CROP, false), Acceptance.place(1920, 1080, auto, blur, 800))
    }

    @Test
    fun wrongFormatUsesThePolicy() {
        assertEquals(Placement.Draw(DisplayMode.BLUR, true), Acceptance.place(1080, 1920, auto, blur, 800))
        assertEquals(Placement.Skip, Acceptance.place(1080, 1920, auto, skip, 800))
        assertEquals(Placement.Skip, Acceptance.place(640, 360, DisplayMode.CROP.ordinal, skip, 800))
    }

    @Test
    fun fitModesAcceptAnyFormat() {
        val fit = DisplayMode.FIT_CENTER.ordinal
        val fitBlur = DisplayMode.FIT_CENTER_BLURRED.ordinal
        assertEquals(Placement.Draw(DisplayMode.FIT_CENTER, false), Acceptance.place(1080, 1920, fit, skip, 800))
        assertEquals(Placement.Draw(DisplayMode.FIT_CENTER_BLURRED, false), Acceptance.place(500, 500, fitBlur, skip, 800))
        // Automatic resolving to a fit mode counts too.
        assertEquals(Placement.Draw(DisplayMode.FIT_CENTER, false), Acceptance.place(1080, 1920, auto, skip, 800, WallpaperDisplayModes.FIT_CENTER))
    }

    @Test
    fun drawModeShowsSkippedPicturesWithTheBaseMode() {
        assertEquals(DisplayMode.CROP, Acceptance.drawMode(1080, 1920, auto, skip, 800))
        assertEquals(DisplayMode.FIT_CENTER, Acceptance.drawMode(1080, 1920, auto, DisplayMode.FIT_CENTER.ordinal, 800))
    }

    @Test
    fun extraBlurDownSampleIsBSquaredOver16PlusOne() {
        assertNull(Acceptance.extraBlur(0))
        assertEquals(BlurSpec(1, 1), Acceptance.extraBlur(1))
        assertEquals(BlurSpec(1, 3), Acceptance.extraBlur(3))
        assertEquals(BlurSpec(2, 4), Acceptance.extraBlur(4))
        assertEquals(BlurSpec(5, 8), Acceptance.extraBlur(8))
        assertEquals(BlurSpec(17, 16), Acceptance.extraBlur(16))
    }

    @Test
    fun modeBlursMatchProjectivy() {
        assertEquals(BlurSpec(8, 8), Acceptance.FIT_BACKGROUND_BLUR)
        assertEquals(BlurSpec(16, 25), Acceptance.BLUR_MODE_BLUR)
    }
}
