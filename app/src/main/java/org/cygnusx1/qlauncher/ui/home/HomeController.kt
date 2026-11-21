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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.shared.NowPlaying
import org.cygnusx1.qlauncher.shared.Program
import org.cygnusx1.qlauncher.shared.ProgramSource

/** What the home screen reads from the other subsystems. */
interface HomeDataSources {
    val apps: StateFlow<List<HomeApp>>
    val shortcuts: StateFlow<List<HomeShortcut>>
    val inputs: StateFlow<List<HomeInput>>
    val availability: StateFlow<ActionAvailability>
    val programs: ProgramSource?
    val notifications: NotificationSource?
    val images: CardImages
}

/**
 * Builds the home rows from the stores and data sources, and holds the category edits the home
 * screen and its panels make (moving cards, moving categories, adding and hiding items).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeController(
    private val context: Context,
    private val container: AppContainer,
    val sources: HomeDataSources,
    scope: CoroutineScope,
) {
    private val content = HomeContent(context, sources.images)
    private val randomSeed = System.currentTimeMillis()

    private val channelsEnabled: Flow<Boolean> = combine(
        container.settings.settings,
        sources.programs?.available ?: flowOf(false),
    ) { s, available ->
        available && if (s.isSet(Keys.General.enableChannels)) s[Keys.General.enableChannels] else true
    }

    private val programData: Flow<Triple<List<Channel>, Map<Long, List<Program>>, List<Program>>> = run {
        val ps = sources.programs
        if (ps == null) {
            flowOf(Triple(emptyList(), emptyMap(), emptyList()))
        } else {
            combine(ps.channels, container.categories.categories) { chans, cats ->
                val wanted = cats.filter { it.type == CategoryType.CHANNEL && it.isVisible }.mapNotNull { it.channelUniqueId }.toSet()
                chans.filter { it.uniqueId in wanted }
            }.flatMapLatest { chans ->
                val flows = chans.map { c -> ps.programs(c.id).map { c.id to it } }
                val programsFlow: Flow<Map<Long, List<Program>>> = if (flows.isEmpty()) flowOf(emptyMap()) else combine(flows) { it.toMap() }
                combine(ps.channels, programsFlow, ps.watchNext) { all, byChannel, wn -> Triple(all, byChannel, wn) }
            }
        }
    }

    private val nowPlaying: Flow<NowPlaying?> = sources.notifications?.nowPlaying ?: flowOf(null)

    private data class Base(
        val cats: List<Category>,
        val apps: List<HomeApp>,
        val overrides: Map<String, org.cygnusx1.qlauncher.data.ItemOverride>,
        val settings: org.cygnusx1.qlauncher.settings.Settings,
        val availability: ActionAvailability,
    )

    private data class Extra(
        val shortcuts: List<HomeShortcut>,
        val inputs: List<HomeInput>,
        val programs: Triple<List<Channel>, Map<Long, List<Program>>, List<Program>>,
        val nowPlaying: NowPlaying?,
        val channelsEnabled: Boolean,
    )

    private val base = combine(
        container.categories.categories,
        sources.apps,
        container.itemOverrides.overrides,
        container.settings.settings,
        sources.availability,
    ) { c, a, o, s, av -> Base(c, a, o, s, av) }

    private val extra = combine(sources.shortcuts, sources.inputs, programData, nowPlaying, channelsEnabled) { sh, inp, pr, np, ch -> Extra(sh, inp, pr, np, ch) }

    val inputs: StateFlow<HomeInputs> = combine(base, extra) { b, e ->
        HomeInputs(
            categories = b.cats,
            apps = b.apps,
            overrides = b.overrides,
            settings = b.settings,
            actions = b.availability.copy(channels = b.availability.channels && e.channelsEnabled),
            shortcuts = e.shortcuts,
            inputs = e.inputs,
            channels = e.programs.first,
            programsByChannel = e.programs.second,
            watchNext = e.programs.third,
            nowPlaying = e.nowPlaying,
            channelsEnabled = e.channelsEnabled,
            randomSeed = randomSeed,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HomeInputs(emptyList(), emptyList(), emptyMap(), container.settings.current, ActionAvailability()))

    val rows: StateFlow<List<HomeRow>> = inputs.map { content.rows(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every category including hidden ones, with its title (Edit categories). */
    fun allCategories(): List<Pair<Category, String>> = inputs.value.categories.map { it to content.title(it) }

    fun title(c: Category): String = content.title(c)

    /** Cards of any category, hidden ones included (folders, editors). */
    fun itemsOf(c: Category): List<CardItem> = content.items(c, inputs.value)

    /** Index of the default category among [rows], or 0. */
    fun defaultRowIndex(rows: List<HomeRow>): Int {
        val id = container.settings.current[Keys.Internal.defaultCategoryId]
        return rows.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
    }

    // Category edits.

    private fun edit(transform: (CategoryList) -> CategoryList) = container.categories.launchEdit(transform)

    private fun mapCategory(id: Int, f: (Category) -> Category) = edit { l -> l.copy(categories = l.categories.map { if (it.id == id) f(it) else it }) }

    /**
     * Moves a card one place in its category. TV Apps and Mobile Apps keep their order in
     * `items` too, so the visible order is written back in full. The move is computed inside the
     * store's transform, from the latest stored list, so quick repeated presses each apply to the
     * result of the previous one.
     */
    fun moveCard(categoryId: Int, key: String, delta: Int) = edit { l ->
        val c = l.categories.firstOrNull { it.id == categoryId } ?: return@edit l
        val keys = content.items(c, inputs.value.copy(categories = l.categories)).map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in keys.indices) return@edit l
        keys.removeAt(from)
        keys.add(to, key)
        val rest = c.items.filter { it !in keys }
        val moved = c.copy(items = keys + rest, orderType = org.cygnusx1.qlauncher.data.SortOrder.CUSTOM)
        l.copy(categories = l.categories.map { if (it.id == categoryId) moved else it })
    }

    /** Moves a category past its visible neighbour, computed from the latest stored list. */
    fun moveCategory(categoryId: Int, delta: Int) = edit { l ->
        val visible = content.rows(inputs.value.copy(categories = l.categories)).map { it.id }
        val i = visible.indexOf(categoryId)
        val neighbour = visible.getOrNull(i + delta) ?: return@edit l
        val cats = l.categories.toMutableList()
        val from = cats.indexOfFirst { it.id == categoryId }
        if (from < 0) return@edit l
        val c = cats.removeAt(from)
        val target = cats.indexOfFirst { it.id == neighbour } + if (delta > 0) 1 else 0
        cats.add(target.coerceIn(0, cats.size), c)
        l.copy(categories = cats)
    }

    /** Whether a category's cards can be put in a custom order. */
    fun canReorder(c: Category): Boolean = c.type in setOf(CategoryType.MANUAL, CategoryType.TV_APPS, CategoryType.MOBILE_APPS)

    fun addTo(categoryId: Int, key: String) = mapCategory(categoryId) { c ->
        if (key in c.items) c.copy(hiddenItems = c.hiddenItems - key) else c.copy(items = c.items + key, hiddenItems = c.hiddenItems - key)
    }

    fun removeFrom(categoryId: Int, key: String) = mapCategory(categoryId) { it.copy(items = it.items - key) }

    /** Hides an item from a category that is not a hand-made list. */
    fun hide(categoryId: Int, key: String) = mapCategory(categoryId) { if (key in it.hiddenItems) it else it.copy(hiddenItems = it.hiddenItems + key) }

    fun restore(categoryId: Int, key: String) = mapCategory(categoryId) { it.copy(hiddenItems = it.hiddenItems - key) }

    /** Add to [target] and take it out of [source] (remove from a list, hide elsewhere). */
    fun moveTo(source: Category, target: Int, key: String) {
        addTo(target, key)
        if (source.type == CategoryType.MANUAL) removeFrom(source.id, key) else hide(source.id, key)
    }

    fun updateCategory(id: Int, f: (Category) -> Category) = mapCategory(id, f)

    fun setItemTitle(key: String, title: String?) = container.itemOverrides.update(key) { it.copy(title = title) }

    fun setItemIcon(key: String, uri: String?) = container.itemOverrides.update(key) { it.copy(iconUri = uri) }

    fun toggleCrop(key: String) = container.itemOverrides.update(key) { it.copy(cropToFill = !it.cropToFill) }

    fun setParentalLocked(key: String, locked: Boolean) = container.itemOverrides.update(key) { it.copy(parentalLocked = locked) }

    fun setDefault(id: Int) = container.settings.update(Keys.Internal.defaultCategoryId, id)

    suspend fun newCategory(title: String): Int = container.categories.createUserCategory(title)

    fun delete(id: Int) {
        container.categories.delete(id)
        if (container.settings.current[Keys.Internal.defaultCategoryId] == id) container.settings.update(Keys.Internal.defaultCategoryId, -1)
    }

    /** Hand-made categories (Favorites, Video, Music, Games, user ones) for Add to / Move to. */
    fun manualCategories(): List<Pair<Category, String>> =
        inputs.value.categories.filter { it.type == CategoryType.MANUAL }.map { it to content.title(it) }

    companion object {
        val SECTION_IDS = setOf(CategoryIds.SECTION_SETTINGS, CategoryIds.SECTION_DISPLAY, CategoryIds.SECTION_APPLICATIONS, CategoryIds.SECTION_SYSTEM, CategoryIds.SECTION_ABOUT)
    }
}

/** Empty sources, used until the subsystems are attached and in previews. */
object EmptyHomeSources : HomeDataSources {
    override val apps = MutableStateFlow(emptyList<HomeApp>())
    override val shortcuts = MutableStateFlow(emptyList<HomeShortcut>())
    override val inputs = MutableStateFlow(emptyList<HomeInput>())
    override val availability = MutableStateFlow(ActionAvailability())
    override val programs: ProgramSource? = null
    override val notifications: NotificationSource? = null
    override val images = CardImages { _, _, _ -> null }
}
