/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryStore
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.data.ItemOverrides
import org.cygnusx1.qlauncher.data.jsonDataStore
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfile
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileList
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileStore
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.settings.backup.autobackup.AesGcmBlobCipher
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupReason
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupStore
import org.cygnusx1.qlauncher.settings.backup.projectivy.InstalledApps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class BackupManagerTest {
    @get:Rule
    val tmp = TemporaryFolder(java.io.File("build/test-tmp").apply { mkdirs() })

    /** One device: its own folders and stores. */
    private inner class Device(name: String) {
        val root: File = tmp.newFolder(name)
        val filesDir = File(root, "files").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }, scope)
        val categories = CategoryStore(jsonDataStore(File(root, "categories.json"), CategoryList.serializer(), CategoryList(), scope), scope)
        val overrides = ItemOverrideStore(jsonDataStore(File(root, "items.json"), ItemOverrides.serializer(), ItemOverrides(), scope), scope)
        val profiles = DisplayProfileStore(jsonDataStore(File(root, "profiles.json"), DisplayProfileList.serializer(), DisplayProfileList(), scope), scope)
        val params = FakeParams()
        val login = FakeLogin()
        val auto = AutoBackupStore(File(filesDir, "autobackup"), AesGcmBlobCipher { key }, keep = 5)
        val manager = BackupManager(
            paths = BackupPaths(filesDir, File(root, "cache"), "org.cygnusx1.qlauncher", exportDir = { File(root, "external/backups") }),
            settings = settings,
            categories = categories,
            itemOverrides = overrides,
            profiles = profiles,
            autoBackups = auto,
            scope = scope,
            wallpaperParams = { params },
            installedApps = { NoApps },
            redditLogin = { login },
        )
    }

    private class FakeParams : WallpaperParamsBridge {
        var params: Map<String, String> = emptyMap()
        var failImport = false
        override suspend fun exportParams() = params
        override suspend fun importParams(params: Map<String, String>) {
            if (failImport) error("wallpaper store failed")
            this.params = params
        }
    }

    private class FakeLogin : RedditLoginBridge {
        var login: RedditLogin? = null

        /** The next import stores its login and then fails, as a store failing half way would. */
        var failNextImport = false
        override suspend fun exportLogin() = login
        override suspend fun importLogin(login: RedditLogin?) {
            this.login = login
            if (failNextImport) {
                failNextImport = false
                error("token store failed")
            }
        }
    }

    private object NoApps : InstalledApps {
        override fun itemKeyForAppId(id: Int): String? = null
        override fun itemKeyForPackage(packageName: String): String? = null
        override fun isInstalled(packageName: String) = false
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val devices = ArrayList<Device>()
    private fun device(name: String) = Device(name).also { devices += it }

    @After
    fun tearDown() {
        devices.forEach { it.scope.cancel() }
    }

    private val password = "correct horse".toCharArray()

    /** Waits until the StateFlows have caught up with DataStore. */
    private suspend fun Device.settle() {
        settings.settings.first { true }
        kotlinx.coroutines.delay(200)
    }

    private suspend fun Device.populate() {
        settings.set(Keys.Look.accent, 0xFF336699.toInt())
        settings.set(Keys.Clock.formatLine1, "HH**mm**")
        settings.set(Keys.Power.idleDetection, true)
        settings.set(Keys.Parental.codeHash, "secret-hash")
        settings.set(Keys.Internal.uniqueId, "install-123")
        categories.replaceAll(CategoryList(categories = listOf(Category(id = 2, items = listOf("app:a/a.Main")), Category(id = 30, title = "Mine")), lastUserId = 30))
        val icon = File(filesDir, "icons/custom.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9, 8, 7)) }
        overrides.replaceAll(mapOf("app:a/a.Main" to ItemOverride(title = "Alpha", iconUri = "file://${icon.absolutePath}", launchCount = 4)))
        profiles.replaceAll(listOf(DisplayProfile(id = "p1", name = "Movie", brightness = 42)))
        File(filesDir, "background").writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        params.params = mapOf(WallpaperProviders.WIKIMEDIA to "MOUNTAINS")
        settle()
    }

    @Test
    fun `export is AES-256 AE-2 and round-trips onto another device`() = runBlocking {
        val a = device("a")
        a.populate()
        val file = a.manager.export(BackupKind.Settings, password)
        assertTrue(file.name.endsWith(".qlbackup"))
        assertEquals(File(a.root, "external/backups"), file.parentFile)

        ZipFile(file).use { zip ->
            assertTrue(zip.isEncrypted)
            for (h in zip.fileHeaders) {
                assertEquals(EncryptionMethod.AES, h.encryptionMethod)
                assertEquals(AesKeyStrength.KEY_STRENGTH_256, h.aesExtraDataRecord.aesKeyStrength)
                assertEquals(AesVersion.TWO, h.aesExtraDataRecord.aesVersion)
            }
            val names = zip.fileHeaders.map { it.fileName }.toSet()
            assertTrue(names.containsAll(listOf("manifest.json", "settings.json", "categories.json", "apps.json", "profiles.json", "wallpaper_params.json", "assets/icons/custom.png", "assets/background")))
            zip.setPassword(password)
            val settingsJson = zip.getInputStream(zip.getFileHeader("settings.json")).use { it.readBytes().decodeToString() }
            // Never exported: the parental hash and per-install ids.
            assertFalse(settingsJson.contains(Keys.Parental.codeHash.name))
            assertFalse(settingsJson.contains("secret-hash"))
            assertFalse(settingsJson.contains(Keys.Internal.uniqueId.name))
            val obj = Json.parseToJsonElement(settingsJson).jsonObject
            assertEquals(setOf(Keys.Look.accent.name, Keys.Clock.formatLine1.name, Keys.Power.idleDetection.name), obj.keys)
            // Paths in the files folder are stored portably.
            val apps = zip.getInputStream(zip.getFileHeader("apps.json")).use { it.readBytes().decodeToString() }
            assertTrue(apps.contains(BackupFormat.FILES_PLACEHOLDER + "/icons/custom.png"))
            assertFalse(apps.contains(a.filesDir.absolutePath))
        }

        val b = device("b")
        val session = b.manager.openFile(file)
        assertTrue(session.needsPassword)
        val plan = b.manager.plan(session, password)
        val preview = plan.preview
        assertEquals(ImportOrigin.QLauncherBackup, preview.origin)
        assertEquals(3, preview.settings)
        assertEquals(2, preview.categories)
        assertEquals(1, preview.appCustomisations)
        assertEquals(1, preview.displayProfiles)
        b.manager.apply(plan)
        session.close()
        b.settle()

        assertEquals(0xFF336699.toInt(), b.settings.current[Keys.Look.accent])
        assertEquals("HH**mm**", b.settings.current[Keys.Clock.formatLine1])
        assertEquals("", b.settings.current[Keys.Parental.codeHash])
        assertEquals(listOf(2, 30), b.categories.state.value.categories.map { it.id })
        val alpha = b.overrides.overrides.value.getValue("app:a/a.Main")
        assertEquals("file://${File(b.filesDir, "icons/custom.png").absolutePath}", alpha.iconUri)
        assertEquals(4, alpha.launchCount)
        assertEquals(42, b.profiles.profiles.value.single().brightness)
        assertEquals(mapOf(WallpaperProviders.WIKIMEDIA to "MOUNTAINS"), b.params.params)
        assertTrue(File(b.filesDir, "icons/custom.png").readBytes().contentEquals(byteArrayOf(9, 8, 7)))
        assertTrue(File(b.filesDir, "background").isFile)
        // An automatic backup was written before the import.
        assertEquals(AutoBackupReason.BeforeImport, b.auto.entries.value.first().reason)
    }

    @Test
    fun `a settings export carries the Reddit login and an import restores it`() = runBlocking {
        val a = device("a")
        a.populate()
        a.login.login = RedditLogin("refresh-a", "alice")
        val file = a.manager.export(BackupKind.Settings, password)
        ZipFile(file).use { zip ->
            val header = zip.getFileHeader(BackupFormat.REDDIT_LOGIN)
            assertTrue(header.isEncrypted)
            zip.setPassword(password)
            val text = zip.getInputStream(header).use { it.readBytes().decodeToString() }
            assertTrue(text.contains("refresh-a"))
        }

        val b = device("b")
        b.login.login = RedditLogin("refresh-b", "bob")
        val plan = b.manager.plan(b.manager.openFile(file), password)
        assertEquals("alice", plan.preview.redditUser)
        b.manager.apply(plan)
        assertEquals(RedditLogin("refresh-a", "alice"), b.login.login)

        // A file written while logged out leaves the current login alone.
        a.login.login = null
        val loggedOut = a.manager.export(BackupKind.Settings, password)
        ZipFile(loggedOut).use { zip -> assertNull(zip.getFileHeader(BackupFormat.REDDIT_LOGIN)) }
        val plan2 = b.manager.plan(b.manager.openFile(loggedOut), password)
        assertNull(plan2.preview.redditUser)
        b.manager.apply(plan2)
        assertEquals(RedditLogin("refresh-a", "alice"), b.login.login)
    }

    @Test
    fun `a theme never carries the Reddit login`() = runBlocking {
        val a = device("a")
        a.populate()
        a.login.login = RedditLogin("refresh-a", "alice")
        val file = a.manager.export(BackupKind.Theme, password)
        ZipFile(file).use { zip -> assertNull(zip.getFileHeader(BackupFormat.REDDIT_LOGIN)) }

        val b = device("b")
        b.login.login = RedditLogin("refresh-b", "bob")
        val plan = b.manager.plan(b.manager.openFile(file), password)
        assertNull(plan.redditLogin)
        b.manager.apply(plan)
        assertEquals(RedditLogin("refresh-b", "bob"), b.login.login)
    }

    @Test
    fun `a failing login import puts the previous login back`() = runBlocking {
        val a = device("a")
        a.populate()
        a.login.login = RedditLogin("refresh-a", "alice")
        val file = a.manager.export(BackupKind.Settings, password)

        val b = device("b")
        b.login.login = RedditLogin("refresh-b", "bob")
        val plan = b.manager.plan(b.manager.openFile(file), password)
        b.login.failNextImport = true
        try {
            b.manager.apply(plan)
            fail("expected the failure to surface")
        } catch (_: IllegalStateException) {
        }
        assertEquals(RedditLogin("refresh-b", "bob"), b.login.login)
    }

    @Test
    fun `a wrong password is reported plainly`() = runBlocking {
        val a = device("a")
        a.populate()
        val file = a.manager.export(BackupKind.Settings, password)
        val session = a.manager.openFile(file)
        try {
            a.manager.plan(session, "not the password".toCharArray())
            fail("expected a wrong password")
        } catch (_: ImportException.WrongPassword) {
        }
        try {
            a.manager.plan(session, null)
            fail("expected a wrong password")
        } catch (_: ImportException.WrongPassword) {
        }
        session.close()
    }

    @Test
    fun `a short password is refused before anything is written`() = runBlocking {
        val a = device("a")
        try {
            a.manager.export(BackupKind.Settings, "short".toCharArray())
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(File(a.root, "external/backups").exists())
    }

    @Test
    fun `a newer format version is refused`() = runBlocking {
        val a = device("a")
        val file = File(a.root, "new.qlbackup")
        QlArchive.write(
            file,
            password,
            mapOf("manifest.json" to """{"format":"qlauncher-backup","formatVersion":99,"kind":"settings"}""".encodeToByteArray()),
        )
        val session = a.manager.openFile(file)
        try {
            a.manager.plan(session, password)
            fail("expected TooNew")
        } catch (e: ImportException.TooNew) {
            assertEquals(99, e.formatVersion)
        }
    }

    @Test
    fun `unknown keys are listed and skipped`() = runBlocking {
        val a = device("a")
        val file = File(a.root, "x.qlbackup")
        QlArchive.write(
            file,
            password,
            mapOf(
                "manifest.json" to """{"format":"qlauncher-backup","formatVersion":1,"kind":"settings"}""".encodeToByteArray(),
                "settings.json" to """{"clock.size":48,"future.setting":true,"parental.code_hash":"x","clock.format_line1":"H"}""".encodeToByteArray(),
            ),
        )
        val plan = a.manager.plan(a.manager.openFile(file), password)
        assertEquals(listOf("future.setting"), plan.unknownKeys)
        assertEquals(setOf(Keys.Clock.size, Keys.Clock.formatLine1), plan.settings.keys)
        assertNull(plan.categories)
    }

    @Test
    fun `a failing step puts everything back`() = runBlocking {
        val a = device("a")
        a.populate()
        val file = a.manager.export(BackupKind.Settings, password)

        val b = device("b")
        b.settings.set(Keys.Clock.size, 48)
        b.categories.replaceAll(CategoryList(categories = listOf(Category(id = 40, title = "Kept"))))
        File(b.filesDir, "icons").mkdirs()
        File(b.filesDir, "icons/old.png").writeBytes(byteArrayOf(1))
        b.settle()
        b.params.failImport = true
        val plan = b.manager.plan(b.manager.openFile(file), password)
        try {
            b.manager.apply(plan)
            fail("expected the failure to surface")
        } catch (_: IllegalStateException) {
        }
        b.settle()
        assertEquals(48, b.settings.current[Keys.Clock.size])
        assertEquals(Keys.Look.accent.default, b.settings.current[Keys.Look.accent])
        assertEquals(listOf(40), b.categories.state.value.categories.map { it.id })
        assertTrue(File(b.filesDir, "icons/old.png").isFile)
        assertFalse(File(b.filesDir, "icons/custom.png").exists())
        assertTrue(b.filesDir.listFiles()!!.none { it.name.contains(".old-") })
    }

    @Test
    fun `a theme carries theme keys and wallpapers and applies into themes current`() = runBlocking {
        val a = device("a")
        a.populate()
        a.settings.set(Keys.Wallpaper.provider, WallpaperProviders.MEDIA_FILE)
        a.settle()
        val file = a.manager.export(BackupKind.Theme, password)
        assertTrue(file.name.endsWith(".qltheme"))
        ZipFile(file, password).use { zip ->
            val names = zip.fileHeaders.map { it.fileName }.toSet()
            assertEquals(setOf("manifest.json", "settings.json", "wallpaper_params.json", "assets/wallpapers/background.jpg"), names)
            val s = zip.getInputStream(zip.getFileHeader("settings.json")).use { it.readBytes().decodeToString() }
            assertFalse(s.contains(Keys.Power.idleDetection.name))
            assertTrue(s.contains(Keys.Clock.formatLine1.name))
        }

        val b = device("b")
        b.settings.set(Keys.Power.idleDetection, true)
        b.settings.set(Keys.Cards.spacing, 12)
        b.settle()
        val plan = b.manager.plan(b.manager.openFile(file), password)
        assertEquals(BackupKind.Theme, plan.kind)
        assertTrue(plan.hasThemeWallpapers)
        b.manager.apply(plan)
        b.settle()
        assertEquals(WallpaperProviders.THEME, b.settings.current[Keys.Wallpaper.provider])
        assertEquals("HH**mm**", b.settings.current[Keys.Clock.formatLine1])
        // Not a theme key: untouched. A theme key the theme does not set: back to default.
        assertEquals(true, b.settings.current[Keys.Power.idleDetection])
        assertEquals(Keys.Cards.spacing.default, b.settings.current[Keys.Cards.spacing])
        assertTrue(File(b.filesDir, "themes/current/wallpapers/background.jpg").isFile)
        assertTrue(File(b.filesDir, "themes/current/manifest.json").isFile)
    }

    @Test
    fun `restores an automatic backup without a password`() = runBlocking {
        val a = device("a")
        a.populate()
        val entry = a.manager.writeAutoBackup(AutoBackupReason.Change)
        a.settings.set(Keys.Look.accent, 0xFF000001.toInt())
        a.categories.replaceAll(CategoryList())
        a.settle()
        val plan = a.manager.plan(entry)
        assertEquals(ImportOrigin.AutomaticBackup, plan.origin)
        a.manager.apply(plan)
        a.settle()
        assertEquals(0xFF336699.toInt(), a.settings.current[Keys.Look.accent])
        assertEquals(listOf(2, 30), a.categories.state.value.categories.map { it.id })
    }

    @Test
    fun `reset returns everything to default after an automatic backup`() = runBlocking {
        val a = device("a")
        a.populate()
        a.manager.resetAll()
        a.settle()
        assertEquals(Keys.Look.accent.default, a.settings.current[Keys.Look.accent])
        assertEquals(CategoryList().categories, a.categories.state.value.categories)
        assertTrue(a.overrides.overrides.value.isEmpty())
        assertTrue(a.profiles.profiles.value.isEmpty())
        assertTrue(a.params.params.isEmpty())
        assertEquals(AutoBackupReason.BeforeReset, a.auto.entries.value.first().reason)
    }

    @Test
    fun `imports a Projectivy theme file`() = runBlocking {
        val a = device("a")
        val src = File(a.root, "theme_default.plbackup")
        javaClass.classLoader!!.getResourceAsStream("backup/theme_default.plbackup")!!.use { i -> src.outputStream().use { i.copyTo(it) } }
        val session = a.manager.openFile(src)
        assertTrue(session.isProjectivy)
        assertFalse(session.needsPassword)
        val plan = a.manager.plan(session, null)
        assertEquals(ImportOrigin.ProjectivyTheme, plan.origin)
        a.manager.apply(plan)
        a.settle()
        assertEquals(-2981819, a.settings.current[Keys.Look.accent])
        assertEquals(WallpaperProviders.REDDIT, a.settings.current[Keys.Wallpaper.provider])
        assertEquals("HH:mm", a.settings.current[Keys.Clock.formatLine1])
    }

    @Test
    fun `other files are not backups`() = runBlocking {
        val a = device("a")
        val f = File(a.root, "notes.txt").apply { writeText("hello there, this is not a backup at all, not even close.") }
        try {
            a.manager.openFile(f)
            fail("expected NotABackup")
        } catch (_: ImportException.NotABackup) {
        }
        assertTrue(a.manager.stagingRoot.listFiles().orEmpty().isEmpty())
    }
}
