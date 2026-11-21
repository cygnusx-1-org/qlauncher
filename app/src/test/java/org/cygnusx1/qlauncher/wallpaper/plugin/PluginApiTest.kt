/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.plugin

import android.os.Binder
import android.os.Parcel
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The parcel layout must match what the plugins' Parcelize classes read and write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginApiTest {
    private fun written(event: WallpaperEvent): Parcel = Parcel.obtain().apply {
        PluginApi.writeEvent(this, event)
        setDataPosition(0)
    }

    @Test
    fun timeElapsedIsTheSingleIntOne() {
        val p = written(WallpaperEvent.TimeElapsed)
        assertEquals(1, p.readInt())
        assertEquals(4, p.dataSize())
        p.recycle()
    }

    @Test
    fun cardFocusedWritesTypeColoursTitlePackage() {
        val p = written(WallpaperEvent.CardFocused("Title", "com.example", 0x11223344, 0x55667788))
        assertEquals(4, p.readInt())
        assertEquals(0x11223344, p.readInt())
        assertEquals(0x55667788, p.readInt())
        assertEquals("Title", p.readString())
        assertEquals("com.example", p.readString())
        p.recycle()
    }

    @Test
    fun programCardFocusedWritesANullableType() {
        val withType = written(WallpaperEvent.ProgramCardFocused("Show", 4, "content://art", 0))
        assertEquals(8, withType.readInt())
        assertEquals("Show", withType.readString())
        assertEquals(1, withType.readInt())
        assertEquals(4, withType.readInt())
        assertEquals("content://art", withType.readString())
        assertEquals(0, withType.readInt())
        val noType = written(WallpaperEvent.ProgramCardFocused(null, null, null, -1))
        assertEquals(8, noType.readInt())
        assertNull(noType.readString())
        assertEquals(0, noType.readInt())
        assertNull(noType.readString())
        assertEquals(-1, noType.readInt())
    }

    @Test
    fun nowPlayingAndIdle() {
        val np = written(WallpaperEvent.NowPlayingChanged(true, "t", "a", "al", "icon"))
        assertEquals(listOf(2, 1), listOf(np.readInt(), np.readInt()))
        assertEquals(listOf("t", "a", "al", "icon"), List(4) { np.readString() })
        val idle = written(WallpaperEvent.LauncherIdleModeChanged(false))
        assertEquals(listOf(16, 0), listOf(idle.readInt(), idle.readInt()))
    }

    @Test
    fun readsATypedListAsThePluginWritesIt() {
        val a = Wallpaper("https://x/a.jpg", WallpaperType.IMAGE, WallpaperDisplayModes.CROP, "A", "https://x/a", "me", actionUri = null)
        val b = Wallpaper("https://x/b.mp4", WallpaperType.VIDEO, WallpaperDisplayModes.FIT_CENTER, null, null, null, actionUri = "intent:#b")
        val p = Parcel.obtain()
        p.writeInt(3)
        p.writeInt(1); PluginApi.writeWallpaper(p, a)
        p.writeInt(0)
        p.writeInt(1); PluginApi.writeWallpaper(p, b)
        p.setDataPosition(0)
        assertEquals(listOf(a, b), PluginApi.readWallpaperList(p))
        p.recycle()
    }

    @Test
    fun proxyTalksToAStubWithTheRightDescriptor() {
        val stub = object : Binder() {
            var prefs: String? = "initial"
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(PluginApi.DESCRIPTOR)
                val r = reply ?: return false
                when (code) {
                    PluginApi.TRANSACTION_GET_WALLPAPERS -> {
                        assertEquals(1, data.readInt())
                        assertEquals(16, data.readInt())
                        assertEquals(1, data.readInt())
                        r.writeNoException()
                        r.writeInt(1)
                        r.writeInt(1)
                        PluginApi.writeWallpaper(r, Wallpaper("#336699", WallpaperType.COLOR))
                    }
                    PluginApi.TRANSACTION_GET_PREFERENCES -> {
                        r.writeNoException()
                        r.writeString(prefs)
                    }
                    PluginApi.TRANSACTION_SET_PREFERENCES -> {
                        prefs = data.readString()
                        r.writeNoException()
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }
        val proxy = WallpaperProviderServiceProxy(stub)
        assertEquals(listOf(Wallpaper("#336699", WallpaperType.COLOR)), proxy.getWallpapers(WallpaperEvent.LauncherIdleModeChanged(true)))
        assertEquals("initial", proxy.getPreferences())
        proxy.setPreferences("{\"k\":1}")
        assertEquals("{\"k\":1}", proxy.getPreferences())
    }
}
