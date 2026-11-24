/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.shared.Program

enum class CardKind { TvApp, MobileApp, Action, Input, Program, Shortcut, Folder, NowPlaying, Placeholder }

/** One card on the home screen. */
@Immutable
data class CardItem(
    val key: String,
    val title: String,
    val kind: CardKind,
    /** A Coil model for the card image, or null to draw [icon] or the title. */
    val image: Any? = null,
    /** A Material icon name, for action, folder and placeholder cards. */
    val icon: String? = null,
    /** Width / height. */
    val ratio: Float = 16f / 9f,
    val program: Program? = null,
    /** Program meta line ("Season 1 · Episode 3 · 54min"). */
    val subtitle: String? = null,
    /** 0..1 watch progress, or null. */
    val progress: Float? = null,
    val packageName: String? = null,
    /** *Crop to fill*: the card's own picture fills the card, cut to its shape. */
    val cropToFill: Boolean = false,
    val parentalLocked: Boolean = false,
    /** [image] is the user's own picture (*Change icon*), not the app's banner or icon. */
    val customImage: Boolean = false,
    /** [image] is an icon set's transparent icon: drawn straight on the wallpaper, with no card. */
    val transparentImage: Boolean = false,
)

/** One visible category row. */
@Immutable
data class HomeRow(
    val category: Category,
    val title: String,
    val icon: String,
    val items: List<CardItem>,
) {
    val id: Int get() = category.id
    val isGrid: Boolean get() = category.isMultiline
}

/** An app as the home screen needs it (filled from the apps subsystem). */
@Immutable
data class HomeApp(
    val key: String,
    val packageName: String,
    val label: String,
    val isTv: Boolean,
    val isMobile: Boolean,
    val firstInstallTime: Long = 0L,
)

/** An installed pinned shortcut. */
@Immutable
data class HomeShortcut(val key: String, val packageName: String, val label: String, val image: Any?)

/** A TV input or live source. */
@Immutable
data class HomeInput(val key: String, val label: String, val icon: String = "input", val isLiveSource: Boolean = false)

/** Names and icons of the built-in categories. */
object BuiltInCategories {
    @StringRes
    fun title(c: Category): Int? = when (c.id) {
        CategoryIds.NOW_PLAYING -> R.string.home_cat_now_playing
        CategoryIds.INPUTS -> R.string.home_cat_inputs
        CategoryIds.FAVORITES -> R.string.home_cat_favorites
        CategoryIds.VIDEO -> R.string.home_cat_video
        CategoryIds.MUSIC -> R.string.home_cat_music
        CategoryIds.GAMES -> R.string.home_cat_games
        CategoryIds.TV_APPS -> R.string.home_cat_tv_apps
        CategoryIds.MOBILE_APPS -> R.string.home_cat_mobile_apps
        CategoryIds.WATCH_NEXT -> R.string.home_cat_watch_next
        CategoryIds.SHORTCUTS -> R.string.home_cat_shortcuts
        CategoryIds.FOLDERS -> R.string.home_cat_folders
        CategoryIds.LIVE_TV -> R.string.home_cat_live_tv
        CategoryIds.SECTION_SETTINGS -> R.string.home_cat_settings
        CategoryIds.SECTION_DISPLAY -> R.string.home_cat_display
        CategoryIds.SECTION_APPLICATIONS -> R.string.home_cat_applications
        CategoryIds.SECTION_SYSTEM -> R.string.home_cat_system
        CategoryIds.SECTION_ABOUT -> R.string.home_cat_about
        else -> null
    }

    fun icon(c: Category): String = when (c.id) {
        CategoryIds.NOW_PLAYING -> "play_circle_outline"
        CategoryIds.INPUTS -> "input"
        CategoryIds.FAVORITES -> "star_border"
        CategoryIds.VIDEO -> "movie"
        CategoryIds.MUSIC -> "music_note"
        CategoryIds.GAMES -> "sports_esports"
        CategoryIds.TV_APPS -> "tv"
        CategoryIds.MOBILE_APPS -> "smartphone"
        CategoryIds.WATCH_NEXT -> "playlist_play"
        CategoryIds.SHORTCUTS -> "shortcut"
        CategoryIds.FOLDERS -> "folder"
        CategoryIds.LIVE_TV -> "live_tv"
        CategoryIds.SECTION_SETTINGS -> "settings"
        CategoryIds.SECTION_DISPLAY -> "settings_brightness"
        CategoryIds.SECTION_APPLICATIONS -> "apps"
        CategoryIds.SECTION_SYSTEM -> "settings_applications"
        CategoryIds.SECTION_ABOUT -> "info"
        else -> when (c.type) {
            CategoryType.CHANNEL -> "live_tv"
            else -> "label"
        }
    }
}
