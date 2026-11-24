/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import android.content.Context
import android.os.LocaleList
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.SortOrder
import org.cygnusx1.qlauncher.settings.CardRatio
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.NowPlaying
import org.cygnusx1.qlauncher.shared.Program
import java.util.Locale
import kotlin.random.Random
import org.cygnusx1.qlauncher.ui.LocaleHelper

/** Everything the rows are built from, at one moment. */
data class HomeInputs(
    val categories: List<Category>,
    val apps: List<HomeApp>,
    val overrides: Map<String, ItemOverride>,
    val settings: Settings,
    val actions: ActionAvailability,
    val shortcuts: List<HomeShortcut> = emptyList(),
    val inputs: List<HomeInput> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val programsByChannel: Map<Long, List<Program>> = emptyMap(),
    val watchNext: List<Program> = emptyList(),
    val nowPlaying: NowPlaying? = null,
    val channelsEnabled: Boolean = false,
    /** Seed for the Random sort order, fixed for one launcher start. */
    val randomSeed: Long = 0L,
)

/**
 * Builds the visible rows. A category shows when it is visible and not empty; an empty user
 * category shows one placeholder card.
 */
class HomeContent(private val context: Context, private val images: CardImages) {
    companion object {
        /** The hidden-items entry that hides every program of an app (Watch Next). */
        fun packageKey(packageName: String) = "pkg:$packageName"
    }


    fun rows(input: HomeInputs, includeHidden: Boolean = false): List<HomeRow> =
        input.categories.mapNotNull { c ->
            if (!includeHidden && !c.isVisible) return@mapNotNull null
            val items = items(c, input)
            val shown = when {
                items.isNotEmpty() -> items
                c.isUserCategory -> listOf(placeholder(c))
                else -> return@mapNotNull null
            }
            HomeRow(c, title(c), BuiltInCategories.icon(c), shown)
        }

    /** [context] (the application) with its strings in the app's language, kept until that changes. */
    @Volatile private var localized: Pair<LocaleList, Context>? = null

    /**
     * Rows are recomputed when the language setting changes, which on Android 13+ is before the
     * system has moved the application context to it: resolve a picked language explicitly.
     * Following the system (or a language set from Android's own settings), the application
     * context is right, and [HomeController] recomputes the rows when it changes.
     */
    private fun strings(): Context {
        val tag = LocaleHelper.tag(context)
        if (tag.isEmpty()) return context
        val locales = LocaleHelper.locales(tag)
        localized?.let { (l, c) -> if (l == locales) return c }
        return LocaleHelper.localized(context, locales).also { localized = locales to it }
    }

    fun title(c: Category): String =
        c.title ?: BuiltInCategories.title(c)?.let { strings().getString(it) } ?: ""

    fun items(c: Category, input: HomeInputs): List<CardItem> {
        val hidden = c.hiddenItems.toSet()
        val byKey = input.apps.associateBy { it.key }
        val raw: List<CardItem> = when (c.type) {
            CategoryType.MANUAL -> c.items.mapNotNull { k -> byKey[k]?.let { app(it, input) } ?: shortcut(k, input) }
            CategoryType.TV_APPS -> ordered(c, input.apps.filter { it.isTv }).map { app(it, input) }
            CategoryType.MOBILE_APPS -> ordered(c, input.apps.filter { !it.isTv && it.isMobile }).map { app(it, input) }
            CategoryType.ACTIONS -> input.actions.forSection(c.id).map { action(it) }
            CategoryType.SHORTCUTS -> input.shortcuts.map { s -> CardItem(s.key, s.label, CardKind.Shortcut, s.image, ratio = mobileRatio(input), packageName = s.packageName) }
            CategoryType.INPUTS -> input.inputs.filter { !it.isLiveSource }.map { CardItem(it.key, it.label, CardKind.Input, icon = it.icon) }
            CategoryType.LIVE_TV -> input.inputs.filter { it.isLiveSource }.map { CardItem(it.key, it.label, CardKind.Input, icon = it.icon) }
            CategoryType.FOLDERS -> input.categories.filter { it.type == CategoryType.MANUAL && it.items.isNotEmpty() && it.id != c.id }
                .map { CardItem(ItemKeys.folder(it.id), title(it), CardKind.Folder, icon = "folder") }
            CategoryType.NOW_PLAYING -> input.nowPlaying?.let { np ->
                listOf(
                    CardItem(
                        "nowplaying:" + np.packageName, np.title ?: "", CardKind.NowPlaying, np.artUri, icon = "music_note",
                        ratio = 1f, subtitle = listOfNotNull(np.artist, np.album).joinToString(" · ").ifEmpty { null }, packageName = np.packageName,
                    ),
                )
            }.orEmpty()
            CategoryType.WATCH_NEXT -> if (input.channelsEnabled) input.watchNext.map { program(it) } else emptyList()
            CategoryType.CHANNEL -> if (input.channelsEnabled) {
                val ch = input.channels.firstOrNull { it.uniqueId == c.channelUniqueId }
                ch?.let { input.programsByChannel[it.id].orEmpty().map { p -> program(p) } }.orEmpty()
            } else {
                emptyList()
            }
            else -> emptyList()
        }
        // Hidden items, and (Watch Next) programs of apps hidden from the row.
        val visible = raw.filter { it.key !in hidden && (it.packageName == null || packageKey(it.packageName) !in hidden) }
        return sort(c, visible, input)
    }

    /** Stored order first, then the rest by name (TV Apps and Mobile Apps). */
    private fun ordered(c: Category, apps: List<HomeApp>): List<HomeApp> {
        val pos = c.items.withIndex().associate { it.value to it.index }
        return apps.sortedWith(compareBy<HomeApp> { pos[it.key] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase(Locale.getDefault()) })
    }

    private fun sort(c: Category, items: List<CardItem>, input: HomeInputs): List<CardItem> = when (c.orderType) {
        SortOrder.NAME -> items.sortedBy { it.title.lowercase(Locale.getDefault()) }
        SortOrder.LAST_USED -> items.sortedByDescending { input.overrides[it.key]?.lastLaunch ?: 0L }
        SortOrder.MOST_USED -> items.sortedByDescending { input.overrides[it.key]?.launchCount ?: 0 }
        SortOrder.RANDOM -> items.shuffled(Random(input.randomSeed + c.id))
        else -> items
    }

    private fun tvRatio(input: HomeInputs) = CardRatio.entries.getOrElse(input.settings[Keys.Cards.tvRatio]) { CardRatio.R16_9 }.ratio
    private fun mobileRatio(input: HomeInputs) = CardRatio.entries.getOrElse(input.settings[Keys.Cards.mobileRatio]) { CardRatio.R1_1 }.ratio

    private fun app(a: HomeApp, input: HomeInputs): CardItem {
        val o = input.overrides[a.key]
        val tv = a.isTv
        return CardItem(
            key = a.key,
            title = o?.title ?: a.label,
            kind = if (tv) CardKind.TvApp else CardKind.MobileApp,
            image = images.app(a.key, banner = tv, overrideUri = o?.iconUri),
            ratio = if (tv) tvRatio(input) else mobileRatio(input),
            packageName = a.packageName,
            cropToFill = o?.cropToFill ?: false,
            parentalLocked = o?.parentalLocked ?: false,
        )
    }

    private fun shortcut(key: String, input: HomeInputs): CardItem? =
        input.shortcuts.firstOrNull { it.key == key }?.let { s -> CardItem(s.key, s.label, CardKind.Shortcut, s.image, ratio = mobileRatio(input), packageName = s.packageName) }

    private fun action(a: BuiltInAction) = CardItem(a.key, strings().getString(a.title), CardKind.Action, icon = a.icon)

    private fun placeholder(c: Category) = CardItem("placeholder:${c.id}", "", CardKind.Placeholder, icon = "add")

    private fun program(p: Program): CardItem {
        val ratio = when (p.posterArtAspectRatio) {
            1 -> 3f / 2f
            2 -> 4f / 3f
            3 -> 1f
            4 -> 2f / 3f
            5 -> 1f / 1.441f
            else -> 16f / 9f
        }
        val meta = buildList {
            p.seasonNumber?.let { add(strings().getString(org.cygnusx1.qlauncher.R.string.home_season, it)) }
            p.episodeNumber?.let { add(strings().getString(org.cygnusx1.qlauncher.R.string.home_episode, it)) }
            if (p.durationMillis > 0) add(strings().getString(org.cygnusx1.qlauncher.R.string.home_minutes, (p.durationMillis / 60000).toInt()))
        }.joinToString(" · ").ifEmpty { null }
        val progress = if (p.durationMillis > 0 && p.lastPlaybackPositionMillis > 0) (p.lastPlaybackPositionMillis.toFloat() / p.durationMillis).coerceIn(0f, 1f) else null
        return CardItem(
            ItemKeys.program(p.id), p.title, CardKind.Program, p.posterArtUri ?: p.thumbnailUri, ratio = ratio, program = p,
            subtitle = meta, progress = progress, packageName = p.packageName,
        )
    }
}

/** Coil models for card images; the apps subsystem supplies the real one. */
fun interface CardImages {
    fun app(key: String, banner: Boolean, overrideUri: String?): Any?
}
