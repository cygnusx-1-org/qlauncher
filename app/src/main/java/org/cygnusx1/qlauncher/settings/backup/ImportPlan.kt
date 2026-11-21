/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfile
import org.cygnusx1.qlauncher.settings.SettingKey
import java.io.File

/** Where an import comes from. */
enum class ImportOrigin { QLauncherBackup, QLauncherTheme, ProjectivyBackup, ProjectivyTheme, AutomaticBackup }

/** Something in a Projectivy file that QLauncher cannot carry over, said plainly on the confirmation screen. */
sealed interface NotCarried {
    /** The parental control code (and its recovery code): set a new one in Parental control. */
    data object ParentalCode : NotCarried

    /** Premium or licence state; QLauncher has no premium. */
    data object PremiumState : NotCarried

    /** Bindings to TV inputs this device does not have (input cards, autostart on an input). */
    data class InputBindings(val count: Int) : NotCarried

    /** Display profile bindings to inputs; there are no inputs or picture manager here. */
    data class DisplayProfileBindings(val count: Int) : NotCarried

    /** A wallpaper plugin that is not installed; the wallpaper source stays as it is. */
    data class PluginNotInstalled(val packageName: String) : NotCarried

    /** Custom icons stored inside Projectivy's own files, which QLauncher cannot read. */
    data class PrivateIcons(val count: Int) : NotCarried
}

/** A file to put in place under `filesDir` when the import is applied. */
data class StagedAsset(
    /** The extracted copy. */
    val staged: File,
    /** Path relative to `filesDir` that it replaces, e.g. `icons` or `themes/current`. */
    val destination: String,
)

/**
 * A decoded import, ready to apply in one go. Built by the QLauncher reader or the Projectivy
 * translator; nothing is written until it is applied.
 *
 * Null fields leave that part of the setup as it is.
 */
data class ImportPlan(
    val origin: ImportOrigin,
    val kind: BackupKind,
    /** Settings to store; with [clearOtherSettings], every other exported (theme) key returns to default. */
    val settings: Map<SettingKey<*>, Any>,
    val clearOtherSettings: Boolean,
    val categories: CategoryList? = null,
    val itemOverrides: Map<String, ItemOverride>? = null,
    val profiles: List<DisplayProfile>? = null,
    val wallpaperParams: Map<String, String>? = null,
    /** Folders and files replaced under `filesDir`. */
    val assets: List<StagedAsset> = emptyList(),
    /** The theme carries wallpapers: the wallpaper source becomes Theme. */
    val hasThemeWallpapers: Boolean = false,
    val themeName: String? = null,
    val themeAuthor: String? = null,
    /** The version of the app that wrote the file, for display. */
    val writtenBy: String? = null,
    val createdAt: Long? = null,
    /** Setting names QLauncher does not know; they are skipped. */
    val unknownKeys: List<String> = emptyList(),
    /** Projectivy item ids (apps, actions, inputs) that match nothing on this device. */
    val skippedItems: List<Int> = emptyList(),
    val notCarried: List<NotCarried> = emptyList(),
) {
    /** The counts the confirmation screen shows. */
    val preview: ImportPreview
        get() = ImportPreview(
            origin = origin,
            kind = kind,
            settings = settings.size,
            categories = categories?.categories?.size,
            appCustomisations = itemOverrides?.size,
            displayProfiles = profiles?.size,
            wallpapers = assets.count { it.destination.startsWith("themes/") || it.destination == "background" },
            unknownKeys = unknownKeys,
            skippedItems = skippedItems.size,
            notCarried = notCarried,
            themeName = themeName,
            themeAuthor = themeAuthor,
            writtenBy = writtenBy,
            createdAt = createdAt,
        )
}

/** What the confirmation screen says will be replaced. Null counts are left untouched. */
data class ImportPreview(
    val origin: ImportOrigin,
    val kind: BackupKind,
    val settings: Int,
    val categories: Int?,
    val appCustomisations: Int?,
    val displayProfiles: Int?,
    val wallpapers: Int,
    val unknownKeys: List<String>,
    val skippedItems: Int,
    val notCarried: List<NotCarried>,
    val themeName: String?,
    val themeAuthor: String?,
    val writtenBy: String?,
    val createdAt: Long?,
)
