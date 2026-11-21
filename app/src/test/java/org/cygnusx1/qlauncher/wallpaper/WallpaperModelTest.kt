/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperModelTest {
    @Test
    fun roundTripsAndStoresProjectivyTypeCodes() {
        val w = Wallpaper("https://x/a.mp4", WallpaperType.VIDEO, WallpaperDisplayModes.FIT_CENTER, "T", "https://x", "me", "CC0", "intent:#x", 1920, 1080)
        val json = w.toJson()
        assertTrue(json.contains("\"type\":4"))
        assertEquals(w, Wallpaper.fromJson(json))
    }

    @Test
    fun readsTypeNamesAndUnknownFields() {
        assertEquals(WallpaperType.LOTTIE, Wallpaper.fromJson("""{"uri":"file:///a.json","type":"lottie","extra":1}""")?.type)
        assertEquals(WallpaperType.IMAGE, Wallpaper.fromJson("""{"uri":"file:///a.png","type":1}""")?.type)
        assertNull(Wallpaper.fromJson(""))
        assertNull(Wallpaper.fromJson("""{"uri":""}"""))
    }

    @Test
    fun colours() {
        assertTrue(Wallpaper.isColorValue("#a1B2c3"))
        assertFalse(Wallpaper.isColorValue("#a1B2c"))
        assertEquals(0xFFA1B2C3.toInt(), Wallpaper.parseColor("#a1B2c3"))
        assertEquals(0x80A1B2C3.toInt(), Wallpaper.parseColor("#80A1B2C3"))
        assertNull(Wallpaper.parseColor("blue"))
    }

    @Test
    fun eventsReachProvidersByUpdateMode() {
        assertTrue(WallpaperEvent.TimeElapsed.reaches(17))
        assertTrue(WallpaperEvent.LauncherIdleModeChanged(true).reaches(17))
        assertFalse(WallpaperEvent.CardFocused(null, null, 0, 0).reaches(17))
        assertTrue(WallpaperEvent.LoadingError.reaches(UpdateMode.CARD_FOCUSED))
        assertEquals(8, WallpaperEvent.ProgramCardFocused(null, null, null, -1).eventType)
    }

    @Test
    fun sameAsIgnoresMetadata() {
        assertTrue(Wallpaper("u", title = "a").sameAs(Wallpaper("u", title = "b")))
        assertFalse(Wallpaper("u").sameAs(Wallpaper("u", WallpaperType.VIDEO)))
        assertTrue(Wallpaper.DEFAULT.isInternal)
        assertFalse(Wallpaper.NONE.isInternal)
    }
}
