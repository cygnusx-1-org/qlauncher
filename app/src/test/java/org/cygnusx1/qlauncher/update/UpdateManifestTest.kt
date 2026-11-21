/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManifestTest {
    private val feed = """
        {
          "package": {
            "downloadUrlList_arm64-v8a": ["https://example.org/QLauncher_0.3.0_arm64-v8a.apk"],
            "downloadUrlList": ["https://example.org/QLauncher_0.3.0.apk"]
          },
          "0.3.0": { "versionCode": 3, "changelog": ["Third"], "changelog_de": ["Dritte"] },
          "0.2.10": { "versionCode": 2, "changelog": ["Second"] },
          "0.2.9": { "versionCode": 2, "changelog": ["Older"] },
          "0.1.0": { "versionCode": 1, "changelog": ["First"] }
        }
    """.trimIndent()

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(UpdateManifest.compareVersions("0.2.10", "0.2.9") > 0)
        assertEquals(0, UpdateManifest.compareVersions("0.2", "0.2.0"))
        assertEquals(0, UpdateManifest.compareVersions("1.0.3-beta", "1.0.3"))
        assertTrue(UpdateManifest.compareVersions("1.0", "0.99.99") > 0)
    }

    @Test
    fun aNewerVersionComesWithTheChangesSinceTheInstalledOne() {
        val r = UpdateManifest.parse(feed, "0.2.9", "x86", "en") as ManifestResult.Available
        assertEquals("0.3.0", r.update.versionName)
        assertEquals(3, r.update.versionCode)
        assertEquals(listOf("Third", "Second"), r.update.changelog)
        assertEquals(listOf("https://example.org/QLauncher_0.3.0.apk"), r.update.downloadUrls)
    }

    @Test
    fun theDevicesAbiAndLanguageWin() {
        val r = UpdateManifest.parse(feed, "0.2.10", "arm64-v8a", "de") as ManifestResult.Available
        assertEquals(listOf("https://example.org/QLauncher_0.3.0_arm64-v8a.apk"), r.update.downloadUrls)
        assertEquals(listOf("Dritte"), r.update.changelog)
    }

    @Test
    fun theLatestOrADevelopmentBuildIsUpToDate() {
        assertEquals(ManifestResult.UpToDate("0.3.0"), UpdateManifest.parse(feed, "0.3.0", null, null))
        assertEquals(ManifestResult.UpToDate("0.3.0"), UpdateManifest.parse(feed, "0.4.0", null, null))
    }

    @Test
    fun aBrokenFeedIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse("[]", "0.1.0", null, null) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse("""{"0.2.0":{}}""", "0.1.0", null, null) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse("""{"package":{}}""", "0.1.0", null, null) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse("""{"package":{},"0.2.0":{}}""", "0.1.0", null, null) }
    }

    @Test
    fun theRepositorysFeedParses() {
        val text = File("../updates/qlauncher.json").readText()
        assertEquals(ManifestResult.UpToDate("0.1.0"), UpdateManifest.parse(text, "0.1.0", "arm64-v8a", "en"))
        val r = UpdateManifest.parse(text, "0.0.9", "arm64-v8a", "en") as ManifestResult.Available
        assertTrue(r.update.downloadUrls.single().endsWith(".apk"))
    }
}
