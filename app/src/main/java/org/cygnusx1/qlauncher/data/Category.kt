/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.data

import kotlinx.serialization.Serializable

/** What a category holds; the numbers are Projectivy's category types, so imports map. */
object CategoryType {
    /** A list filled by hand: Favorites, Video, Music, Games and every user category. */
    const val MANUAL = 0
    const val INPUTS = 1
    const val TV_APPS = 2
    const val MOBILE_APPS = 3

    /** One of the root-panel sections (Settings, Display, Applications, System, About). */
    const val ACTIONS = 4
    const val WATCH_NEXT = 5
    const val CHANNEL = 6
    const val SHORTCUTS = 7
    const val NOW_PLAYING = 8
    const val FOLDERS = 9
    const val LIVE_TV = 10
}

/** The fixed ids of the built-in categories (Projectivy's). User categories start at [FIRST_USER]. */
object CategoryIds {
    const val INPUTS = 1
    const val FAVORITES = 2
    const val TV_APPS = 3
    const val MOBILE_APPS = 4
    const val SECTION_SETTINGS = 5
    const val SECTION_DISPLAY = 6
    const val SECTION_APPLICATIONS = 7
    const val SECTION_SYSTEM = 8
    const val SECTION_ABOUT = 9
    const val WATCH_NEXT = 10
    const val SHORTCUTS = 11
    const val NOW_PLAYING = 12
    const val FOLDERS = 13
    const val LIVE_TV = 14
    const val VIDEO = 20
    const val MUSIC = 21
    const val GAMES = 22
    const val FIRST_USER = 23
    const val FIRST_CHANNEL = 2101
}

/** Category size steps (XS..XL) and their card scale. */
enum class CategorySize(val scale: Float, val label: String) {
    XS(0.6f, "XS"),
    S(0.8f, "S"),
    M(1.0f, "M"),
    L(1.2f, "L"),
    XL(1.4f, "XL"),
    ;

    companion object {
        fun of(scale: Float): CategorySize = entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}

/** Sort orders (Projectivy's values). */
object SortOrder {
    const val CUSTOM = 0
    const val NAME = 1
    const val LAST_USED = 2
    const val MOST_USED = 3
    const val RANDOM = 4
}

/**
 * One home-screen category. The fields are those of Projectivy's own category model, so an
 * imported setup maps one to one.
 *
 * Items are referenced by an item key (see [ItemKeys]).
 */
@Serializable
data class Category(
    val id: Int,
    val type: Int = CategoryType.MANUAL,
    /** Null for built-ins: their localised name is used. */
    val title: String? = null,
    val customIconUri: String? = null,
    val scale: Float = 1.0f,
    val isVisible: Boolean = true,
    /** Grid instead of a single row. */
    val isMultiline: Boolean = false,
    /** Grid: cards per row, 0 = automatic. */
    val maxItemsPerLine: Int = 0,
    /** Grid: row count, 0 = automatic. */
    val maxVisibleRows: Int = 0,
    /** True: focus moves along the row. False: a sliding row, focus stays in the first slot. */
    val hasMovingSelection: Boolean = false,
    val isAppTitleHidden: Boolean = false,
    val orderType: Int = SortOrder.CUSTOM,
    val items: List<String> = emptyList(),
    val hiddenItems: List<String> = emptyList(),
    /** Channel categories: package name plus channel display name. */
    val channelUniqueId: String? = null,
    /** Grid background colour; null = none. */
    val backgroundColor: Int? = null,
    /** Percent of the width, 0 = follow the global setting. */
    val startMargin: Int = 0,
    /** Percent of the width, 0 or 100 = follow the global setting. */
    val endMargin: Int = 0,
    /** Percent of the height, 0 = follow the global setting. */
    val topMargin: Int = 0,
) {
    val isUserCategory: Boolean get() = id >= CategoryIds.FIRST_USER && type == CategoryType.MANUAL
    val size: CategorySize get() = CategorySize.of(scale)
}

/** Item keys used inside categories. */
object ItemKeys {
    /** An activity: `app:<package>/<class>`. */
    fun app(packageName: String, className: String) = "app:$packageName/$className"

    /** A built-in action such as `action:all_apps`. */
    fun action(id: String) = "action:$id"
    fun input(id: String) = "input:$id"
    fun shortcut(packageName: String, id: String) = "shortcut:$packageName/$id"
    fun program(id: Long) = "program:$id"
    fun folder(categoryId: Int) = "folder:$categoryId"

    fun isApp(key: String) = key.startsWith("app:")

    /** Package and class of an app key, or null. */
    fun parseApp(key: String): Pair<String, String>? {
        if (!isApp(key)) return null
        val body = key.removePrefix("app:")
        val slash = body.indexOf('/')
        if (slash <= 0 || slash == body.length - 1) return null
        return body.substring(0, slash) to body.substring(slash + 1)
    }
}

/**
 * The built-in categories a fresh install starts with, in Projectivy's order. Only Favorites and
 * TV Apps start visible; the rest can be turned on in the category settings.
 */
object DefaultCategories {
    fun create(): List<Category> = listOf(
        Category(CategoryIds.NOW_PLAYING, CategoryType.NOW_PLAYING, scale = 0.8f, hasMovingSelection = true, isVisible = false),
        Category(CategoryIds.INPUTS, CategoryType.INPUTS, scale = 0.6f, hasMovingSelection = true, isVisible = false),
        Category(CategoryIds.FAVORITES, CategoryType.MANUAL, scale = 0.8f),
        Category(CategoryIds.VIDEO, CategoryType.MANUAL, scale = 0.8f, isVisible = false),
        Category(CategoryIds.MUSIC, CategoryType.MANUAL, scale = 0.8f, isVisible = false),
        Category(CategoryIds.GAMES, CategoryType.MANUAL, scale = 0.8f, isVisible = false),
        Category(CategoryIds.TV_APPS, CategoryType.TV_APPS, scale = 0.8f),
        Category(CategoryIds.MOBILE_APPS, CategoryType.MOBILE_APPS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.WATCH_NEXT, CategoryType.WATCH_NEXT, scale = 1.2f, isVisible = false),
        Category(CategoryIds.SHORTCUTS, CategoryType.SHORTCUTS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.FOLDERS, CategoryType.FOLDERS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.LIVE_TV, CategoryType.LIVE_TV, scale = 0.8f, isVisible = false),
        Category(CategoryIds.SECTION_SETTINGS, CategoryType.ACTIONS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.SECTION_DISPLAY, CategoryType.ACTIONS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.SECTION_APPLICATIONS, CategoryType.ACTIONS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.SECTION_SYSTEM, CategoryType.ACTIONS, scale = 0.8f, isVisible = false),
        Category(CategoryIds.SECTION_ABOUT, CategoryType.ACTIONS, scale = 0.8f, isVisible = false),
    )
}

/** Per-item customisation and usage, keyed by item key. */
@Serializable
data class ItemOverride(
    val title: String? = null,
    val iconUri: String? = null,
    val cropToFill: Boolean = false,
    val parentalLocked: Boolean = false,
    val launchCount: Int = 0,
    val lastLaunch: Long = 0L,
)
