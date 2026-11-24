/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import org.cygnusx1.qlauncher.apps.iconpack.IconMatcher
import org.cygnusx1.qlauncher.apps.iconpack.IconSetFeed
import org.cygnusx1.qlauncher.apps.iconpack.IconSetNames
import org.cygnusx1.qlauncher.apps.iconpack.IconSetSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconSetNamesTest {
    @Test
    fun repoFromNameOrUrl() {
        val repo = "DrZacharySmith/projectivy-transparent-icons"
        assertEquals(repo, IconSetNames.parseRepo(repo))
        assertEquals(repo, IconSetNames.parseRepo("  $repo  "))
        assertEquals(repo, IconSetNames.parseRepo("https://github.com/$repo"))
        assertEquals(repo, IconSetNames.parseRepo("github.com/$repo.git"))
        assertEquals(repo, IconSetNames.parseRepo("https://www.github.com/$repo/tree/main/icons"))
        assertNull(IconSetNames.parseRepo(""))
        assertNull(IconSetNames.parseRepo("projectivy-transparent-icons"))
        assertNull(IconSetNames.parseRepo("owner/.."))
        assertNull(IconSetNames.parseRepo("-owner/repo"))
        assertNull(IconSetNames.parseRepo("owner/re po"))
    }

    @Test
    fun onlyIconsDirectlyInAStyleFolder() {
        val top = "DrZacharySmith-projectivy-transparent-icons-da3e5fd"
        assertEquals("glossy-cartoon" to "Netflix-Glossy.png", IconSetNames.styleEntry("$top/icons/styles/glossy-cartoon/Netflix-Glossy.png", "icons/styles"))
        assertEquals("steampunk" to "KODI-Metal.PNG", IconSetNames.styleEntry("$top/icons/styles/steampunk/KODI-Metal.PNG", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/steampunk/README.md", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/Projectivy-Icons-Proof-Sheet.png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/steampunk/sub/x.png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/../x.png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/../../x.png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/a/..png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/a/.hidden.png", "icons/styles"))
        assertNull(IconSetNames.styleEntry("$top/gallery/icons/styles/a/x.png", "icons/styles"))
    }

    @Test
    fun entriesUnderAnotherPath() {
        val top = "o-r-abc1234"
        assertEquals("dark" to "Plex.png", IconSetNames.styleEntry("$top/sets/dark/Plex.png", "sets"))
        assertEquals("dark" to "Plex.png", IconSetNames.styleEntry("$top/dark/Plex.png", ""))
        assertEquals("dark" to "Plex.png", IconSetNames.styleEntry("$top/a/b/c/dark/Plex.png", "a/b/c"))
        assertNull(IconSetNames.styleEntry("$top/icons/styles/dark/Plex.png", "sets"))
        assertNull(IconSetNames.styleEntry("$top/sets/dark/Plex.png", "sets/dark"))
        assertNull(IconSetNames.styleEntry("$top/Plex.png", ""))
    }

    @Test
    fun pathsAsTyped() {
        assertEquals("icons/styles", IconSetNames.parsePath("icons/styles"))
        assertEquals("icons/styles", IconSetNames.parsePath(" /icons//styles/ "))
        assertEquals("", IconSetNames.parsePath(""))
        assertEquals("", IconSetNames.parsePath("/"))
        assertNull(IconSetNames.parsePath("icons/../secret"))
        assertNull(IconSetNames.parsePath("./icons"))
        assertNull(IconSetNames.parsePath("icons\\styles"))
    }

    @Test
    fun sourcesKeepTheDefaultFirst() {
        val def = IconSetNames.DEFAULT_SOURCE
        val added = IconSetSource("someone/icons", "png/sets")
        val stored = IconSetNames.encodeSources(listOf(added))
        assertEquals(listOf(added), IconSetNames.decodeSources(stored))
        assertEquals(listOf(def), IconSetNames.sources("", def.repo))
        assertEquals(listOf(def, added), IconSetNames.sources(stored, added.repo))
        // A repository chosen before sources could be added is still offered, with the default path.
        assertEquals(listOf(def, IconSetSource("old/repo", "icons/styles")), IconSetNames.sources("", "old/repo"))
        // Adding the default repository again changes its path, and it stays first.
        val moved = IconSetSource(def.repo, "icons")
        assertEquals(listOf(moved, added), IconSetNames.sources(IconSetNames.encodeSources(listOf(moved, added)), def.repo))
        // Lines that are not a source are dropped.
        assertEquals(listOf(added), IconSetNames.decodeSources("junk\n" + stored + "\nx/y\t../z"))
    }

    @Test
    fun displayNamesDropTheSharedSuffix() {
        val glossy = listOf("Plex-Glossy.png", "Kodi-Glossy.png", "Netflix-Glossy.png", "Netflix (alt)-Glossy.png", "Wi-Fi Analyzer-Glossy.png", "Vimu - 16x9.png", "Trakt16x9.png", "Echo Gram 16x9.png", "Jellybeam.png")
        val names = IconSetNames.displayNames(glossy)
        assertEquals("Netflix", names["Netflix-Glossy.png"])
        assertEquals("Netflix (alt)", names["Netflix (alt)-Glossy.png"])
        assertEquals("Wi-Fi Analyzer", names["Wi-Fi Analyzer-Glossy.png"])
        assertEquals("Vimu", names["Vimu - 16x9.png"])
        assertEquals("Trakt", names["Trakt16x9.png"])
        assertEquals("Echo Gram", names["Echo Gram 16x9.png"])
        assertEquals("Jellybeam", names["Jellybeam.png"])

        val metal = IconSetNames.displayNames(listOf("Plex-Metal.png", "Cedar-metal.png", "Metal Pro Icon.png"))
        assertEquals("Plex", metal["Plex-Metal.png"])
        assertEquals("Cedar", metal["Cedar-metal.png"])
        assertEquals("Metal Pro Icon", metal["Metal Pro Icon.png"])
    }

    @Test
    fun aSuffixFewFilesShareIsPartOfTheName() {
        val names = IconSetNames.displayNames(listOf("Wi-Fi.png", "Plex.png", "Kodi.png"))
        assertEquals("Wi-Fi", names["Wi-Fi.png"])
    }

    @Test
    fun styleLabels() {
        assertEquals("Glossy cartoon", IconSetNames.styleLabel("glossy-cartoon"))
        assertEquals("Steampunk", IconSetNames.styleLabel("steampunk"))
    }

    @Test
    fun matchesTheObviousOnes() {
        val icons = listOf("Netflix", "Netflix (alt)", "Disney Plus", "Prime", "YouTube", "YouTube TV", "Youtube Music", "Plex", "Kodi", "Settings", "Settings (alt)", "КИОН", "Vimu", "iMPlayer", "Spotify", "Twitch")
        fun best(vararg titles: String): String? = IconMatcher.best(titles.toList(), icons.map { it to IconMatcher.key(it) })
        assertEquals("Netflix", best("Netflix"))
        assertEquals("Disney Plus", best("Disney+"))
        assertEquals("Prime", best("Prime Video"))
        assertEquals("YouTube", best("YouTube"))
        assertEquals("YouTube", best("YouTube for Android TV"))
        assertEquals("YouTube TV", best("YouTube TV"))
        assertEquals("Youtube Music", best("YouTube Music"))
        assertEquals("Settings", best("Settings"))
        assertEquals("Kodi", best("KODI"))
        // A generic last word is dropped, and a near spelling of something else is not a match.
        assertEquals("Vimu", best("Vimu Player"))
        // A tagline after ":" or " - " is not part of the name.
        assertEquals("Spotify", best("Spotify - Music and Podcasts"))
        assertEquals("Twitch", best("Twitch: Live Streaming"))
        assertEquals("КИОН", best("Кион"))
        // The user's own title counts as well as the label.
        assertEquals("Plex", best("Plex: Stream Movies & TV", "Plex"))
        assertNull(best("Hulu"))
        assertNull(best("TV"))
        assertNull(best(""))
    }

    @Test
    fun rankPutsMatchesFirstAndKeepsTheRest() {
        val icons = listOf("Apple TV", "Kodi", "Netflix (alt)", "Netflix", "Plex")
        assertEquals(listOf("Netflix", "Netflix (alt)", "Apple TV", "Kodi", "Plex"), IconMatcher.rank("Netflix", icons) { it })
        assertEquals(icons, IconMatcher.rank("", icons) { it })
    }

    @Test
    fun scoresStayInRange() {
        for ((a, b) in listOf("Netflix" to "Netflix", "Plex" to "Plexamp", "Prime Video" to "Prime", "a" to "b", "TV" to "TV")) {
            val s = IconMatcher.score(a, b)
            assertTrue("$a / $b = $s", s in 0.0..1.0)
        }
        assertEquals(1.0, IconMatcher.score("Disney+", "Disney Plus"), 0.0)
    }

    @Test
    fun releasesFromGitHub() {
        val body = """
            [
              {"tag_name": "v1.47", "published_at": "2026-11-22T20:09:54Z", "zipball_url": "https://api.github.com/repos/o/r/zipball/v1.47", "draft": false, "assets": []},
              {"tag_name": "v1.48", "draft": true},
              {"tag_name": "v1.46", "published_at": "2026-11-19T15:54:59Z"},
              {"tag_name": "v1.45", "published_at": "2026-11-19T12:43:28Z"},
              {"tag_name": "v1.44", "published_at": "2026-11-17T23:43:05Z"}
            ]
        """.trimIndent()
        val list = IconSetFeed.parseReleases("o/r", body, 3)
        assertEquals(listOf("v1.47", "v1.46", "v1.45"), list.map { it.tag })
        assertEquals("https://api.github.com/repos/o/r/zipball/v1.47", list[0].zipUrl)
        assertEquals("https://api.github.com/repos/o/r/zipball/v1.46", list[1].zipUrl)
        assertEquals("2026-11-19T15:54:59Z", list[1].publishedAt)
        assertTrue(IconSetFeed.parseReleases("o/r", "[]", 3).isEmpty())
    }
}
