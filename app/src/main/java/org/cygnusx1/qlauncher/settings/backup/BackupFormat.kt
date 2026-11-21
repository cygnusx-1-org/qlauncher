/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What an export holds. */
@Serializable
enum class BackupKind {
    /** Everything: settings, categories, app customisations, display profiles, icons, wallpaper. */
    @SerialName("settings")
    Settings,

    /** Appearance only: theme keys, wallpaper parameters and the wallpaper. */
    @SerialName("theme")
    Theme,
    ;

    val extension: String get() = if (this == Settings) BackupFormat.EXT_BACKUP else BackupFormat.EXT_THEME
}

/**
 * `manifest.json`, the first entry of every QLauncher export.
 *
 * @property formatVersion the layout of the archive; an import refuses newer versions.
 * @property createdAt milliseconds since the epoch.
 */
@Serializable
data class BackupManifest(
    val format: String = BackupFormat.FORMAT_ID,
    val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    val appVersion: String = "",
    val appVersionCode: Long = 0,
    val createdAt: Long = 0,
    val kind: BackupKind = BackupKind.Settings,
    /** Theme name and author, when the exporter knows them. */
    val name: String? = null,
    val author: String? = null,
)

/** Names and constants of QLauncher's export format. */
object BackupFormat {
    const val FORMAT_ID = "qlauncher-backup"
    const val FORMAT_VERSION = 1

    const val EXT_BACKUP = "qlbackup"
    const val EXT_THEME = "qltheme"
    const val EXT_PL_BACKUP = "plbackup"
    const val EXT_PL_THEME = "pltheme"

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val CATEGORIES = "categories.json"
    const val APPS = "apps.json"
    const val PROFILES = "profiles.json"
    const val WALLPAPER_PARAMS = "wallpaper_params.json"
    const val ASSETS = "assets/"

    /** Custom icons, `filesDir/icons`. */
    const val ASSET_ICONS = "assets/icons/"

    /** The user wallpaper, `filesDir/background` (a file, or a folder of files). */
    const val ASSET_BACKGROUND = "assets/background"

    /** The applied theme, `filesDir/themes/current`, in a settings backup. */
    const val ASSET_THEME = "assets/theme/"

    /** The wallpapers a theme carries. */
    const val ASSET_WALLPAPERS = "assets/wallpapers/"

    /** The minimum export password length. */
    const val MIN_PASSWORD_LENGTH = 8

    /** Stands for `filesDir` in stored paths and URIs, so they survive a move to another device. */
    const val FILES_PLACEHOLDER = "qlauncher-files:"

    /** Pretty, complete JSON for files people may open. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        prettyPrint = true
    }
}

/** Why an import cannot go ahead. */
sealed class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The password is wrong (or missing). */
    class WrongPassword : ImportException("wrong password")

    /** The file is not a QLauncher or Projectivy backup or theme. */
    class NotABackup(cause: Throwable? = null) : ImportException("not a backup", cause)

    /** The file is damaged or incomplete. */
    class Damaged(cause: Throwable? = null) : ImportException("damaged", cause)

    /** The file was made by a newer QLauncher. */
    class TooNew(val formatVersion: Int) : ImportException("format $formatVersion is newer than ${BackupFormat.FORMAT_VERSION}")

    /** The automatic backup cannot be decrypted (for example after the app data moved devices). */
    class Unreadable(cause: Throwable? = null) : ImportException("unreadable", cause)
}
