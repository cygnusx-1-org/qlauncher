/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstallUrlsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun parse(s: String, volume: String? = "/storage/1A2B-3C4D") = InstallUrls.parse(s) { volume }

    @Test
    fun aUrlWithoutSchemeGetsHttps() {
        assertEquals(InstallSource.Remote("https://example.org/app.apk"), parse("  example.org/app.apk "))
    }

    @Test
    fun httpAndHttpsAreKept() {
        assertEquals(InstallSource.Remote("http://example.org/a.apk"), parse("http://example.org/a.apk"))
        assertEquals(InstallSource.Remote("https://example.org/a.apk"), parse("HTTPS://example.org/a.apk"))
    }

    @Test
    fun digitsAreADownloaderCode() {
        assertEquals(InstallSource.DownloaderCode("7882519"), parse("7882519"))
        assertEquals(InstallSource.DownloaderCode("75880"), parse(" 75880 "))
        // Too short or too long to be a code.
        assertEquals(InstallSource.Remote("https://12"), parse("12"))
    }

    @Test
    fun usbMapsToTheFirstVolume() {
        assertEquals(InstallSource.LocalFile("/storage/1A2B-3C4D/apps/x.apk"), parse("usb://apps/x.apk"))
        assertEquals(InstallSource.LocalFile("/storage/1A2B-3C4D/x.apk"), parse("USB:///x.apk", "/storage/1A2B-3C4D/"))
        assertEquals(InstallSource.Invalid(InstallSource.Reason.NoUsbVolume), parse("usb://x.apk", volume = null))
    }

    @Test
    fun localPathsAndRefusals() {
        assertEquals(InstallSource.LocalFile("/sdcard/x.apk"), parse("file:///sdcard/x.apk"))
        assertEquals(InstallSource.LocalFile("/sdcard/x.apk"), parse("/sdcard/x.apk"))
        assertEquals(InstallSource.Invalid(InstallSource.Reason.UnsupportedScheme), parse("ftp://host/x.apk"))
        assertEquals(InstallSource.Invalid(InstallSource.Reason.Archive), parse("https://host/x.tar.gz"))
        assertEquals(InstallSource.Invalid(InstallSource.Reason.Empty), parse("   "))
    }

    @Test
    fun theFirstUsbVolumeIsFoundByName() {
        val root = tmp.newFolder("storage")
        File(root, "emulated").mkdirs()
        File(root, "self").mkdirs()
        File(root, "BEEF-0001").mkdirs()
        File(root, "ABCD-1234").mkdirs()
        File(root, "XXXX-YYYY.txt").writeText("not a dir")
        assertEquals(File(root, "ABCD-1234").absolutePath, InstallUrls.firstUsbVolume(root))
        assertNull(InstallUrls.firstUsbVolume(tmp.newFolder("empty")))
    }

    @Test
    fun downloadFileNames() {
        assertEquals("aerial-views-1.8.6.apk", InstallUrls.fileNameOf("https://x/y/aerial-views-1.8.6.apk?raw=1"))
        assertEquals("download.apk", InstallUrls.fileNameOf("https://x/y/download/?r=latest"))
        assertEquals("a_b.apk", InstallUrls.fileNameOf("https://x/a b"))
    }
}

class CatalogueTest {
    @Test
    fun entriesAreParsedAndCleaned() {
        val list = Catalogue.parse(
            """
            {"installPackages": [
              {"name": " App One ", "category": "Icon Pack", "package": "a.one", "url": "https://x/a.apk", "downloader": " 123456 "},
              {"name": "Store only", "category": "file manager", "package": "b.two", "url": "", "downloader": ""},
              {"name": "No category", "url": "https://x/c.apk"},
              {"name": "Odd category", "category": "Games", "package": "d"},
              {"name": "", "package": "e"},
              {"name": "Nothing to install"}
            ], "extra": 1}
            """,
        )
        assertEquals(listOf("App One", "Store only", "No category", "Odd category"), list.map { it.name })
        assertEquals(CatalogueEntry("App One", "Icon Pack", "a.one", "https://x/a.apk", "123456"), list[0])
        assertEquals(CatalogueEntry("Store only", "File Manager", "b.two", null, null), list[1])
        assertEquals(CatalogueCategory.TOOL, list[2].categoryKind)
        assertEquals(CatalogueCategory.TOOL, list[3].categoryKind)
    }

    @Test
    fun methodsAreOfferedInChooserOrder() {
        val full = CatalogueEntry("x", packageName = "p", url = "u", downloader = "1")
        assertEquals(listOf(InstallMethod.Store, InstallMethod.Direct, InstallMethod.Downloader), full.methods)
        assertEquals(InstallMethod.Direct, full.defaultMethod)
        assertEquals(InstallMethod.Downloader, CatalogueEntry("x", packageName = "p", downloader = "1").defaultMethod)
        assertEquals(InstallMethod.Store, CatalogueEntry("x", packageName = "p").defaultMethod)
        assertNull(CatalogueEntry("x").defaultMethod)
    }

    @Test
    fun brokenJsonGivesNothing() {
        assertEquals(emptyList<CatalogueEntry>(), Catalogue.parse("{not json"))
        assertEquals(emptyList<CatalogueEntry>(), Catalogue.parse("[]"))
    }

    @Test
    fun groupsFollowThePageOrder() {
        val g = Catalogue.grouped(
            listOf(
                CatalogueEntry("t", "Tool", "t"),
                CatalogueEntry("w", "Wallpaper Plugin", "w"),
                CatalogueEntry("t2", "Tool", "t2"),
            ),
        )
        assertEquals(listOf(CatalogueCategory.WALLPAPER_PLUGIN, CatalogueCategory.TOOL), g.map { it.first })
        assertEquals(listOf("t", "t2"), g[1].second.map { it.name })
    }

    @Test
    fun theBundledCatalogueIsWellFormed() {
        val file = listOf(File("src/main/assets/${Catalogue.ASSET}"), File("app/src/main/assets/${Catalogue.ASSET}")).first { it.isFile }
        val list = Catalogue.parse(file.readText())
        assertEquals(18, list.size)
        val groups = Catalogue.grouped(list).associate { it.first to it.second.size }
        assertEquals(mapOf(CatalogueCategory.WALLPAPER_PLUGIN to 4, CatalogueCategory.ICON_PACK to 3, CatalogueCategory.FILE_MANAGER to 4, CatalogueCategory.TOOL to 7), groups)
        for (e in list) {
            assertTrue("${e.name} needs a package for the installed tick", !e.packageName.isNullOrBlank())
            e.url?.let { assertTrue("${e.name}: $it must be https", it.startsWith("https://")) }
            e.downloader?.let { assertTrue("${e.name}: $it", it.all(Char::isDigit)) }
            assertTrue("${e.name} must not be Projectivy-branded", !e.name.contains("projectivy", ignoreCase = true))
        }
        assertEquals(list.size, list.map { it.packageName }.distinct().size)
    }
}
