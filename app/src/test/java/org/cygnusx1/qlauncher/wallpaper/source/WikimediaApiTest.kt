/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.source

import org.cygnusx1.qlauncher.wallpaper.Fixtures
import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikimediaApiTest {
    private val parsed = WikimediaApi.parse(Fixtures.read("wikimedia_search.json"))!!

    @Test
    fun keepsOnlyLargeLandscapeJpegPngWebpWithALicence() {
        assertEquals(
            listOf(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Alpine_lake_at_dawn.jpg/1920px-Alpine_lake_at_dawn.jpg",
                "https://upload.wikimedia.org/Desert_road.webp",
                "https://upload.wikimedia.org/thumb/Long_credit.png/1920px-Long_credit.png",
                "https://upload.wikimedia.org/thumb/Anonymous.jpg/1920px-Anonymous.jpg",
            ),
            parsed.map { it.uri },
        )
    }

    @Test
    fun carriesTheMetadata() {
        val first = parsed[0]
        assertEquals("Alpine lake at dawn", first.title)
        assertEquals("Example Photographer & friends", first.author)
        assertEquals("CC BY-SA 4.0", first.licence)
        assertEquals("https://commons.wikimedia.org/wiki/File:Alpine_lake_at_dawn.jpg", first.source)
        assertEquals(WallpaperType.IMAGE, first.type)
        assertEquals(WallpaperDisplayModes.CROP, first.displayMode)
        assertEquals(1920, first.width)
        assertEquals(1248, first.height)
    }

    @Test
    fun usesTheOriginalWhenThereIsNoThumbnail() {
        val road = parsed[1]
        assertEquals(2560, road.width)
        assertEquals(1440, road.height)
        assertEquals("Photo: A. Writer", road.author)
    }

    @Test
    fun longAuthorsAreCutAt80Characters() {
        val author = parsed[2].author!!
        assertEquals(80, author.length)
        assertEquals('…', author.last())
        assertEquals("Wikimedia Commons", parsed[3].author)
    }

    @Test
    fun errorsAndGarbageGiveNull() {
        assertNull(WikimediaApi.parse("""{"error":{"code":"badvalue","info":"bad"}}"""))
        assertNull(WikimediaApi.parse("<html>"))
        assertEquals(emptyList<Any>(), WikimediaApi.parse("""{"batchcomplete":true}"""))
    }

    @Test
    fun requestMatchesProjectivys() {
        val url = WikimediaApi.url(WikimediaCategory.MOUNTAINS.query)
        assertEquals("commons.wikimedia.org", url.host)
        assertEquals("/w/api.php", url.encodedPath)
        val expected = mapOf(
            "action" to "query",
            "format" to "json",
            "formatversion" to "2",
            "generator" to "search",
            "gsrsearch" to "incategory:\"Featured pictures of mountains\"",
            "gsrnamespace" to "6",
            "gsrlimit" to "50",
            "gsrsort" to "random",
            "prop" to "imageinfo",
            "iiprop" to "url|size|mime|extmetadata",
            "iiurlwidth" to "1920",
            "iiextmetadatalanguage" to "en",
            "iiextmetadatafilter" to "Artist|Attribution|LicenseShortName|LicenseUrl|UsageTerms",
        )
        assertEquals(expected, url.queryParameterNames.associateWith { url.queryParameter(it) })
    }

    @Test
    fun categoriesAndCustomSearch() {
        assertEquals(WikimediaCategory.LANDSCAPES, WikimediaCategory.of(""))
        assertEquals(WikimediaCategory.MOUNTAINS, WikimediaCategory.of("MOUNTAINS"))
        assertEquals(WikimediaCategory.CUSTOM, WikimediaCategory.of("q:lighthouse"))
        assertEquals("incategory:\"Commons featured desktop backgrounds\"", WikimediaCategory.queryFor("WALLPAPERS"))
        assertEquals("deepcat:\"Featured pictures of cityscapes\" night", WikimediaCategory.queryFor("NIGHT"))
        assertEquals("deepcat:\"Featured pictures of astronomy\"", WikimediaCategory.queryFor("SPACE"))
        assertEquals("incategory:\"Featured pictures on Wikimedia Commons\" red  lighthouse", WikimediaCategory.queryFor("q: \"red\" lighthouse "))
        assertNull(WikimediaCategory.queryFor("q:  "))
        val long = "x".repeat(150)
        assertEquals("incategory:\"Featured pictures on Wikimedia Commons\" " + "x".repeat(100), WikimediaCategory.queryFor("q:$long"))
    }

    @Test
    fun stripHtmlDecodesEntitiesAndCollapsesSpace() {
        assertEquals("A & B <c>", WikimediaApi.stripHtml("<b>A</b> &amp;\n\n B &lt;c&gt;"))
        assertEquals("Sunset over sea", WikimediaApi.titleOf("File:Sunset_over_sea.jpeg"))
    }
}
