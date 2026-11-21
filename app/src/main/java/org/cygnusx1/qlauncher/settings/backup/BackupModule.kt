/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import android.content.Context
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileList
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileStore
import org.cygnusx1.qlauncher.displayprofiles.PictureManager
import org.cygnusx1.qlauncher.data.jsonDataStore
import org.cygnusx1.qlauncher.fonts.FontResolver
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupReason
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupStore
import org.cygnusx1.qlauncher.settings.backup.autobackup.KeystoreBlobCipher
import org.cygnusx1.qlauncher.settings.backup.projectivy.PackageManagerApps
import java.io.File

/**
 * Backup, import, themes, automatic backup, display profiles and fonts.
 *
 * Creating the module starts the automatic-backup watcher (a write about ten seconds after
 * settings, categories, app customisations or display profiles change).
 */
class BackupModule(private val container: AppContainer) {
    private val app = container.app

    /** The display profiles (`datastore/display_profiles.json`). */
    val profiles: DisplayProfileStore = DisplayProfileStore(
        jsonDataStore(app, "display_profiles.json", DisplayProfileList.serializer(), DisplayProfileList(), container.scope),
        container.scope,
    )

    /** Maps the *Font* settings to a Compose font family. */
    val fonts: FontResolver by lazy { FontResolver(app, container.okHttp, container.scope, container.settings) }

    @Volatile
    private var wallpaperParams: WallpaperParamsBridge? = null

    val manager: BackupManager = BackupManager(
        paths = BackupPaths(app),
        settings = container.settings,
        categories = container.categories,
        itemOverrides = container.itemOverrides,
        profiles = profiles,
        autoBackups = AutoBackupStore(File(app.filesDir, "autobackup"), KeystoreBlobCipher()),
        scope = container.scope,
        wallpaperParams = { wallpaperParams },
        installedApps = { PackageManagerApps(app) },
    )

    init {
        manager.cleanStaging()
        manager.startAutoBackup()
    }

    /**
     * Lets exports carry the wallpaper subsystem's per-provider parameters and imports restore
     * them. Call once at start-up from the wallpaper module.
     */
    fun registerWallpaperParams(bridge: WallpaperParamsBridge) {
        wallpaperParams = bridge
    }

    /** The vendor picture manager; null on every device for now (*No picture manager loaded*). */
    val pictureManager: PictureManager? by lazy { PictureManager.find(app) }

    /**
     * *Reset to default*: an automatic backup, then settings, categories (back to
     * `CategoryList()`), app customisations, display profiles and wallpaper parameters are
     * cleared, and the launcher restarts. Does not return.
     */
    suspend fun resetAll(context: Context): Nothing {
        manager.resetAll()
        LauncherRestart.restart(context)
    }

    /** Restarts the launcher (after an import applied outside `ImportScreen`). */
    fun restartLauncher(context: Context): Nothing = LauncherRestart.restart(context)

    /** Call before migrating settings after an app update. */
    suspend fun backupBeforeMigration() {
        manager.writeAutoBackup(AutoBackupReason.BeforeMigration)
    }
}
