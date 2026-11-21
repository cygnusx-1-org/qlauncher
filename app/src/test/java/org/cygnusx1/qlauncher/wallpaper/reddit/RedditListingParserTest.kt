/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import org.cygnusx1.qlauncher.wallpaper.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedditListingParserTest {
    private val json = Fixtures.read("reddit_listing.json")

    @Test
    fun keepsImagePostsAndGalleriesOnly() {
        val listing = RedditListingParser.parse(json, minAcceptedWidth = 800, skipWrongFormat = true)!!
        assertEquals(
            listOf(
                "https://preview.redd.it/fog.jpg?width=3840&format=pjpg&auto=webp&s=abc",
                "https://preview.redd.it/m2.jpg?width=3840&format=pjpg&s=2",
                "https://preview.redd.it/m1.jpg?width=2560&format=pjpg&s=1",
                "https://i.redd.it/nopreview.jpg",
            ),
            listing.wallpapers.map { it.uri },
        )
        assertEquals("t3_next", listing.after)
    }

    @Test
    fun withoutSkipBadFormatsStay() {
        val listing = RedditListingParser.parse(json, minAcceptedWidth = 800, skipWrongFormat = false)!!
        assertEquals(6, listing.wallpapers.size)
        assertEquals("https://preview.redd.it/portrait.jpg", listing.wallpapers[4].uri)
        assertEquals("https://preview.redd.it/tiny.jpg", listing.wallpapers[5].uri)
    }

    @Test
    fun carriesTheMetadataForThisWallpaper() {
        val first = RedditListingParser.parse(json, 800, true)!!.wallpapers.first()
        assertEquals("Morning fog over the valley [3840x2160] [OC]", first.title)
        assertEquals("u/alice · r/EarthPorn", first.author)
        assertEquals("https://www.reddit.com/r/EarthPorn/comments/a1/morning_fog_over_the_valley/", first.source)
        assertEquals(3840, first.width)
        assertEquals(2160, first.height)
        val gallery = RedditListingParser.parse(json, 800, true)!!.wallpapers[1]
        assertEquals("Coastline set", gallery.title)
        assertEquals(3840, gallery.width)
    }

    @Test
    fun minimumWidthComesFromTheListingSizes() {
        val listing = RedditListingParser.parse(json, minAcceptedWidth = 3000, skipWrongFormat = true)!!
        // 2560-wide m1 is dropped; the unknown-size post stays.
        assertEquals(
            listOf("https://preview.redd.it/fog.jpg?width=3840&format=pjpg&auto=webp&s=abc", "https://preview.redd.it/m2.jpg?width=3840&format=pjpg&s=2", "https://i.redd.it/nopreview.jpg"),
            RedditListingParser.parse(json, 3000, true)!!.wallpapers.map { it.uri },
        )
        assertEquals(3, listing.wallpapers.size)
    }

    @Test
    fun notAListingGivesNull() {
        assertNull(RedditListingParser.parse("""{"message":"Forbidden","error":403}""", 800, true))
        assertNull(RedditListingParser.parse("<html>", 800, true))
    }

    @Test
    fun presetsAndCustomSubreddits() {
        assertEquals("Earth", RedditPresets.presetOf("EARTHPORN")?.label)
        assertEquals("Dall·e & Sora", RedditPresets.presetOf("dalle2")?.label)
        assertNull(RedditPresets.presetOf("pics+wallpapers"))
        assertEquals("pics+wallpapers+Earth_Porn", RedditPresets.subredditsOf("r/pics + wallpapers+/r/Earth_Porn+PICS"))
        assertEquals("", RedditPresets.subredditsOf(" + "))
        assertEquals(16, RedditPresets.entries.size)
        assertEquals(0, RedditPresets.entries.count { it.label.startsWith("Projectivy") })
    }
}
