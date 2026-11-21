/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.projectivy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.ImportOrigin
import org.cygnusx1.qlauncher.settings.backup.NotCarried
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectivyImportTest {
    @get:Rule
    val tmp = TemporaryFolder(java.io.File("build/test-tmp").apply { mkdirs() })

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("backup/$name")) { name }.use { it.readBytes() }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** A device with a few of the apps Nathan's sample backup refers to. */
    private class FakeApps(vararg packages: String) : InstalledApps {
        private val keys = packages.associateWith { ItemKeys.app(it, "$it.MainActivity") }
        override fun itemKeyForAppId(id: Int) = keys.entries.firstOrNull { it.key.hashCode() == id }?.value
        override fun itemKeyForPackage(packageName: String) = keys[packageName]
        override fun isInstalled(packageName: String) = packageName in keys
    }

    @Test
    fun `decodes the bundled default theme payload`() {
        val decoded = ProjectivyDecoder.decodeBackup(resource("theme_default.plbackup"))
        assertEquals(json(resource("theme_default.json").decodeToString()), json(decoded))
    }

    @Test(expected = ProjectivyDecoder.FormatException::class)
    fun `random bytes are not a backup`() {
        ProjectivyDecoder.decodeBackup(ByteArray(96) { it.toByte() })
    }

    @Test
    fun `app ids are the hash of the package name`() {
        // Ids seen in a real export, for packages on the test device.
        assertEquals(-341478038, ProjectivyDecoder.appId("org.cygnusx1.electriceel.stable"))
        assertEquals(709550618, ProjectivyDecoder.appId("com.github.damontecres.wholphin"))
        assertEquals(-1812241477, ProjectivyDecoder.appId("org.smarttube.beta"))
    }

    @Test
    fun `imports the default theme with its mapped values`() {
        val payload = json(ProjectivyDecoder.decodeBackup(resource("theme_default.plbackup")))
        assertTrue(ProjectivyTranslator.looksLikeTheme(payload))
        val plan = ProjectivyTranslator(FakeApps()).translate(payload, isTheme = true, current = CategoryList(), currentSettingsVersion = 3)

        assertEquals(ImportOrigin.ProjectivyTheme, plan.origin)
        assertEquals(BackupKind.Theme, plan.kind)
        assertEquals("91", plan.writtenBy)
        assertEquals(emptyList<String>(), plan.unknownKeys)
        assertEquals(emptyList<NotCarried>(), plan.notCarried)

        val s = plan.settings
        assertEquals(-2981819, s[Keys.Look.accent])
        assertEquals("default", s[Keys.Font.name])
        assertEquals(false, s[Keys.Font.bold])
        assertEquals(1.0f, s[Keys.Font.scale])
        assertEquals(WallpaperProviders.REDDIT, s[Keys.Wallpaper.provider])
        assertEquals(10, s[Keys.Wallpaper.updateDelay])
        assertEquals(0, s[Keys.Wallpaper.displayMode])
        assertEquals(2, s[Keys.Wallpaper.transition])
        assertEquals(3, s[Keys.Wallpaper.wrongFormatMode])
        assertEquals(-16777215, s[Keys.Wallpaper.tint])
        assertEquals(0.2f, s[Keys.Wallpaper.shadowHLength])
        assertEquals(0.3f, s[Keys.Cards.roundedCorners])
        assertEquals(8, s[Keys.Cards.spacing])
        assertEquals(-13421773, s[Keys.Cards.background])
        assertEquals(0, s[Keys.Cards.tvRatio])
        assertEquals(3, s[Keys.Cards.mobileRatio])
        assertEquals(3, s[Keys.Focus.scale])
        assertEquals(2, s[Keys.Focus.effect])
        assertEquals(0, s[Keys.Focus.glowColor])
        assertEquals(0.5f, s[Keys.Categories.topMargin])
        assertEquals(0.95f, s[Keys.Categories.endMargin])
        assertEquals(-1, s[Keys.Internal.defaultCategoryId])
        assertEquals(2.0f, s[Keys.Idle.timeout])
        assertEquals(true, s[Keys.Idle.preventScreensaver])
        assertEquals(2013200126, s[Keys.StatusBar.iconColor])
        assertEquals(850045611, s[Keys.StatusBar.iconBackground])
        assertEquals(32, s[Keys.Clock.size])
        assertEquals("HH:mm", s[Keys.Clock.formatLine1])
        assertEquals("", s[Keys.Clock.formatLine2])
        assertEquals(2013200126, s[Keys.Clock.color])
        // Every value is a theme key; Projectivy's version never becomes QLauncher's.
        assertTrue(s.keys.all { it.theme })
        assertFalse(Keys.Internal.settingsVersion in s)
        // 89 appearance keys, less the action-row placeholder.
        assertEquals(88, s.size)

        // The built-in categories are restyled, in the theme's order, items kept.
        val cats = requireNotNull(plan.categories).categories
        assertEquals(
            listOf(12, 1, 2, 20, 21, 22, 3, 4, 10, 11, 13, 14, 5, 6, 7, 8, 9),
            cats.map { it.id },
        )
        val nowPlaying = cats.first { it.id == CategoryIds.NOW_PLAYING }
        assertEquals(1.0f, nowPlaying.scale)
        assertTrue(nowPlaying.hasMovingSelection)
        val favorites = cats.first { it.id == CategoryIds.FAVORITES }
        assertFalse(favorites.isVisible)
        assertEquals(0.8f, favorites.scale)
        assertEquals(0.6f, cats.first { it.id == CategoryIds.INPUTS }.scale)
        assertNull(plan.itemOverrides)
        assertNull(plan.profiles)
    }

    @Test
    fun `a theme reorders built-ins and keeps user categories and items in place`() {
        val current = CategoryList(
            categories = CategoryList().categories.take(3).map { if (it.id == CategoryIds.FAVORITES) it.copy(items = listOf("app:a/b")) else it } +
                org.cygnusx1.qlauncher.data.Category(id = 30, title = "Mine"),
        )
        val theme = json(
            """{"action_default_categories_settings":"[{\"id\":2,\"scale\":0.6,\"isMultiline\":true,\"endMargin\":93,\"backgroundColor\":1358954494},{\"id\":12,\"type\":8},{\"id\":1,\"type\":1}]"}""",
        )
        val plan = ProjectivyTranslator(FakeApps()).translate(theme, isTheme = true, current = current, currentSettingsVersion = 0)
        val cats = requireNotNull(plan.categories).categories
        assertEquals(listOf(2, 12, 1, 30), cats.map { it.id })
        val fav = cats[0]
        assertEquals(listOf("app:a/b"), fav.items)
        assertTrue(fav.isMultiline)
        assertEquals(93, fav.endMargin)
        assertEquals(1358954494, fav.backgroundColor)
        assertEquals("Mine", cats[3].title)
    }

    @Test
    fun `imports a settings backup with categories, customisations and wallpaper parameters`() {
        val payload = json(resource("projectivy_settings_sample.json").decodeToString())
        assertFalse(ProjectivyTranslator.looksLikeTheme(payload))
        val apps = FakeApps(
            "org.cygnusx1.electriceel.stable",
            "com.github.damontecres.wholphin",
            "org.xbmc.kodi",
            "org.jellyfin.androidtv",
            "org.smarttube.beta",
        )
        val plan = ProjectivyTranslator(apps).translate(payload, isTheme = false, current = CategoryList(), currentSettingsVersion = 3)

        assertEquals(ImportOrigin.ProjectivyBackup, plan.origin)
        assertEquals("98", plan.writtenBy)
        assertEquals(WallpaperProviders.WIKIMEDIA, plan.settings[Keys.Wallpaper.provider])
        assertEquals(1.0f, plan.settings[Keys.Cards.size])
        // Projectivy's reporting switches are not carried over: they agreed to a different service.
        assertFalse(plan.settings.keys.any { it == Keys.General.crashErrorReports })
        assertEquals(3, plan.settings[Keys.Internal.settingsVersion])
        // Internal, never-exported values are not imported.
        assertFalse(Keys.Internal.onboardingCompleted in plan.settings)
        assertEquals(mapOf(WallpaperProviders.WIKIMEDIA to "MOUNTAINS"), plan.wallpaperParams)
        assertEquals(emptyList<String>(), plan.unknownKeys)
        assertEquals(emptyList<org.cygnusx1.qlauncher.displayprofiles.DisplayProfile>(), plan.profiles)

        val cats = requireNotNull(plan.categories)
        assertEquals(17, cats.categories.size)
        val key = { pkg: String -> ItemKeys.app(pkg, "$pkg.MainActivity") }
        val favorites = cats.categories.first { it.id == CategoryIds.FAVORITES }
        assertEquals(
            listOf(key("org.cygnusx1.electriceel.stable"), key("com.github.damontecres.wholphin"), key("org.xbmc.kodi"), key("org.jellyfin.androidtv"), key("org.smarttube.beta")),
            favorites.items,
        )
        assertEquals(0.8f, favorites.scale)
        val tvApps = cats.categories.first { it.id == CategoryIds.TV_APPS }
        assertEquals(5, tvApps.items.size)
        assertEquals(5, tvApps.hiddenItems.size)
        // 25 TV-apps ids, 5 of them installed here: the other 20 are reported, once each.
        assertEquals(20, plan.skippedItems.size)
        assertTrue(cats.categories.first { it.id == CategoryIds.SECTION_SETTINGS }.isVisible.not())
        assertEquals(CategoryIds.FIRST_USER - 1, cats.lastUserId)

        val overrides = requireNotNull(plan.itemOverrides)
        val eel = requireNotNull(overrides[key("org.cygnusx1.electriceel.stable")])
        assertEquals(2, eel.launchCount)
        assertEquals(1795130156959L, eel.lastLaunch)
    }

    @Test
    fun `what cannot carry over is reported`() {
        val payload = json(
            """{
              "parental_control_code": "1a2b",
              "parental_control_apps": "[-1812241477, 5]",
              "display_profiles_input_hdr_HDMI1": "0f0c",
              "display_profiles_input_sdr_HDMI1": "",
              "power_autostart_source": "2",
              "appearance_wallpaper_provider": "11111111-2222-3333-4444-555555555555",
              "appearance_wallpaper_provider_packagename": "com.example.wallpaper.plugin",
              "action_categories_list": "[{\"id\":1,\"type\":1,\"items\":[11,12]},{\"id\":23,\"title\":\"Kids\",\"customIconUri\":\"content://com.spocky.projengmenu.files/icon.png\",\"endMargin\":100}]",
              "remotecontrol_decrease_input_lag": true,
              "display_profiles_list": ["{\"profileName\":\"Movie\",\"id\":\"abc\",\"brightness\":40,\"isMemc\":true,\"zoom\":10}"]
            }""",
        )
        val plan = ProjectivyTranslator(FakeApps("org.smarttube.beta")).translate(payload, isTheme = false, current = CategoryList(), currentSettingsVersion = 1)
        assertTrue(NotCarried.ParentalCode in plan.notCarried)
        assertTrue(NotCarried.DisplayProfileBindings(1) in plan.notCarried)
        assertTrue(NotCarried.InputBindings(3) in plan.notCarried) // two input cards, autostart on HDMI 2
        assertTrue(NotCarried.PluginNotInstalled("com.example.wallpaper.plugin") in plan.notCarried)
        assertTrue(NotCarried.PrivateIcons(1) in plan.notCarried)
        assertFalse(Keys.Wallpaper.provider in plan.settings)
        assertEquals("", plan.settings[Keys.Power.autostartSource])
        assertEquals(listOf("remotecontrol_decrease_input_lag"), plan.unknownKeys)
        assertEquals(listOf(5), plan.skippedItems)

        val kids = requireNotNull(plan.categories).categories.first { it.id == 23 }
        assertNull(kids.customIconUri)
        assertEquals(0, kids.endMargin)
        assertEquals(23, plan.categories!!.lastUserId)
        assertTrue(plan.itemOverrides!![ItemKeys.app("org.smarttube.beta", "org.smarttube.beta.MainActivity")]!!.parentalLocked)

        val profile = plan.profiles!!.single()
        assertEquals("Movie", profile.name)
        assertEquals("abc", profile.id)
        assertEquals(40, profile.brightness)
        assertTrue(profile.memc)
        assertEquals(30, profile.zoom)
    }

    @Test
    fun `an autostart app maps to its activity`() {
        val payload = json("""{"power_autostart_source":"org.smarttube.beta"}""")
        val plan = ProjectivyTranslator(FakeApps("org.smarttube.beta")).translate(payload, isTheme = false, current = CategoryList(), currentSettingsVersion = 1)
        assertEquals("org.smarttube.beta/org.smarttube.beta.MainActivity", plan.settings[Keys.Power.autostartSource])
    }

    @Test
    fun `reads a pltheme with manifest, payload and wallpapers`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("manifest"))
                z.write("""{"projectivyLauncherVersion":98,"themeVersion":1,"author":"Me","name":"Night","desc":"x"}""".encodeToByteArray())
                z.putNextEntry(ZipEntry("appearance.plbackup"))
                z.write(resource("theme_default.plbackup"))
                z.putNextEntry(ZipEntry("wallpapers/one.jpg"))
                z.write(byteArrayOf(1, 2, 3))
                z.putNextEntry(ZipEntry("wallpapers/../../evil.jpg"))
                z.write(byteArrayOf(4))
            }
        }.toByteArray()
        val dir = tmp.newFolder("walls")
        val theme = ProjectivyDecoder.readTheme(ByteArrayInputStream(zip), dir)
        assertEquals("Night", theme.manifest?.name)
        assertEquals("Me", theme.manifest?.author)
        assertEquals(98, theme.manifest?.projectivyLauncherVersion)
        assertEquals(setOf("one.jpg", "evil.jpg"), theme.wallpapers.map { it.name }.toSet())
        assertTrue(theme.wallpapers.all { it.parentFile == dir })
        assertEquals(json(resource("theme_default.json").decodeToString()), json(theme.settingsJson))
    }
}
