/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.Program
import org.cygnusx1.qlauncher.shared.ProgramSource
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProvidersTest {
    @Test
    fun mediaFileReadsColoursJsonAndBareUris() {
        assertEquals(Wallpaper("#12AB34", WallpaperType.COLOR), MediaFileProvider.parse("#12ab34"))
        val stored = Wallpaper("file:///data/x/background/a.mp4", WallpaperType.VIDEO, title = "a")
        assertEquals(stored, MediaFileProvider.parse(stored.toJson()))
        assertEquals(WallpaperType.VIDEO, MediaFileProvider.parse("content://media/external/video/42.mkv")?.type)
        assertEquals(WallpaperType.LOTTIE, MediaFileProvider.parse("file:///sdcard/anim.json")?.type)
        assertEquals(WallpaperType.IMAGE, MediaFileProvider.parse("content://media/external/images/42")?.type)
        assertNull(MediaFileProvider.parse("  "))
        assertNull(MediaFileProvider.parse("{broken"))
    }

    @Test
    fun typesFromMimeAndName() {
        assertEquals(WallpaperType.LOTTIE, wallpaperTypeOf("application/json", null))
        assertEquals(WallpaperType.VIDEO, wallpaperTypeOf("video/mp4", null))
        assertEquals(WallpaperType.ANIMATED, wallpaperTypeOf(null, "loop.gif"))
        assertEquals(WallpaperType.IMAGE, wallpaperTypeOf(null, "a.JPG"))
        assertEquals(WallpaperType.VIDEO, wallpaperTypeOf(null, "a.webm"))
        assertNull(wallpaperTypeOf("text/plain", null))
        assertNull(wallpaperTypeOf(null, "notes.txt"))
    }

    private fun program(id: Long, aspect: Int, duration: Long, art: String? = "https://art/$id.jpg") =
        Program(id = id, packageName = "p", channelId = 1, title = "t$id", durationMillis = duration, posterArtUri = art, posterArtAspectRatio = aspect, intentUri = "intent:#$id")

    @Test
    fun randomProgramRules() {
        val selected = RandomProgramProvider.select(
            listOf(
                program(1, -1, 0),
                program(2, 0, 20 * 60_000L),
                program(3, 1, 10 * 60_000L),
                program(4, 2, 16 * 60_000L),
                program(5, 3, 0),
                program(6, 5, 0),
                program(7, 0, 0, art = null),
            ),
        )
        assertEquals(listOf("https://art/1.jpg", "https://art/2.jpg", "https://art/4.jpg"), selected.map { it.uri })
        assertEquals("intent:#2", selected[1].actionUri)
    }

    @Test
    fun randomProgramWithoutChannelsFails() = runTest {
        val provider = RandomProgramProvider { null }
        try {
            provider.getWallpapers(WallpaperEvent.TimeElapsed, "")
            org.junit.Assert.fail()
        } catch (e: ProviderException) {
            assertEquals(org.cygnusx1.qlauncher.R.string.wallpaper_error_no_channels, e.messageRes)
        }
        val source = object : ProgramSource {
            override val available: StateFlow<Boolean> = MutableStateFlow(true)
            override val channels: StateFlow<List<Channel>> = MutableStateFlow(emptyList())
            override fun programs(channelId: Long): StateFlow<List<Program>> = MutableStateFlow(emptyList())
            override val watchNext: StateFlow<List<Program>> = MutableStateFlow(emptyList())
            override suspend fun allPreviewPrograms(): List<Program> = listOf(program(1, 0, 0))
            override suspend fun refresh() = Unit
        }
        assertEquals(1, RandomProgramProvider { source }.getWallpapers(WallpaperEvent.TimeElapsed, "").size)
    }

    @Test
    fun focusedProgramKeepsTheLastArt() = runTest {
        val p = FocusedProgramProvider()
        assertEquals(emptyList<Wallpaper>(), p.getWallpapers(WallpaperEvent.TimeElapsed, ""))
        val list = p.getWallpapers(WallpaperEvent.ProgramCardFocused("Show", 4, "https://art/x.jpg", 0), "")
        assertEquals("https://art/x.jpg", list.single().uri)
        assertEquals("Show", list.single().title)
        assertEquals(list, p.getWallpapers(WallpaperEvent.ProgramCardFocused("No art", 4, null, 0), ""))
    }

    @Test
    fun nowPlayingCarriesTitleArtistAndAlbum() = runTest {
        val p = NowPlayingProvider { null }
        val list = p.getWallpapers(WallpaperEvent.NowPlayingChanged(true, "Song", "Artist", "Album", "content://art/1"), "")
        assertEquals("content://art/1", list.single().uri)
        assertEquals("Song", list.single().title)
        assertEquals("Artist · Album", list.single().author)
        assertEquals(emptyList<Wallpaper>(), p.getWallpapers(WallpaperEvent.NowPlayingChanged(false, null, null, null, null), ""))
    }
}
