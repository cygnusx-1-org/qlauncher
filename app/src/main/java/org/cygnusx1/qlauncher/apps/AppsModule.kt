/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import coil3.ComponentRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.actions.BuiltInActions
import org.cygnusx1.qlauncher.apps.iconpack.IconPackRepository
import org.cygnusx1.qlauncher.apps.iconpack.IconSetRepository
import org.cygnusx1.qlauncher.apps.image.AppImage
import org.cygnusx1.qlauncher.apps.image.AppImageSource
import org.cygnusx1.qlauncher.apps.image.AppImages
import org.cygnusx1.qlauncher.apps.shortcuts.PinnedShortcuts
import org.cygnusx1.qlauncher.apps.shortcuts.ShortcutRepository
import org.cygnusx1.qlauncher.channels.ChannelEditor
import org.cygnusx1.qlauncher.channels.ChannelRepository
import org.cygnusx1.qlauncher.channels.inputs.TvInputRepository
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.jsonDataStore
import org.cygnusx1.qlauncher.device.DeviceInfoRepository
import org.cygnusx1.qlauncher.install.AppInstaller
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.shared.ProgramSource

/** An entry of the *Autostart on boot* list. [value] is what `Keys.Power.autostartSource` stores. */
data class AutostartTarget(val value: String, val label: String, val kind: Kind, val itemKey: String? = null) {
    enum class Kind { None, Screensaver, Input, App }
}

/**
 * Apps, shortcuts, icon packs, built-in actions, channels, TV inputs, the installer and device
 * information. One instance per process (`container.apps`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppsModule(private val container: AppContainer) {
    private val context: Context = container.app
    private val scope = container.scope

    val apps: AppRepository = AppRepository(context, scope, container.itemOverrides)

    val shortcuts: ShortcutRepository = ShortcutRepository(
        context,
        scope,
        jsonDataStore(context, "apps_shortcuts.json", PinnedShortcuts.serializer(), PinnedShortcuts(), scope),
        apps.packageEvents,
    )

    val iconPacks: IconPackRepository = IconPackRepository(context, scope, container.settings, container.itemOverrides, apps.packageEvents)

    /** *Cards › Icon set*: icons downloaded from a GitHub repository. */
    val iconSets: IconSetRepository = IconSetRepository(context, scope, { container.okHttp }, apps, container.itemOverrides, iconPacks)

    val inputs: TvInputRepository = TvInputRepository(context, scope, container.settings, container.itemOverrides)

    /** The TV provider ([ProgramSource]). */
    val channels: ChannelRepository = ChannelRepository(context, scope, container.settings, container.itemOverrides)

    /** Channel categories and the *Edit channels* panel. */
    val channelEditor: ChannelEditor = ChannelEditor(scope, container.categories, channels, apps::appLabel)

    val actions: BuiltInActions = BuiltInActions(
        context,
        container.settings,
        { channels.enabled.value && channels.available.value },
        apps::systemSettingsIntent,
    )

    val installer: AppInstaller by lazy { AppInstaller(context, scope, { container.okHttp }, apps.packageEvents) }

    val deviceInfo: DeviceInfoRepository by lazy { DeviceInfoRepository(context, container.settings) }

    /** Sort orders; the random order is dealt once per process. */
    val sorter: ItemSorter = ItemSorter()

    val images: AppImages = AppImages(context, apps, container.itemOverrides, iconPacks, shortcuts, inputs)

    /** Adds the [AppImage] mapper, keyer and fetcher to the app's Coil image loader. */
    fun registerImageComponents(builder: ComponentRegistry.Builder) = images.register(builder)

    /** Which image [image] shows (a banner, or an icon to draw on the card colour). */
    fun imageSource(image: AppImage): AppImageSource = images.source(image)

    /** Call from the home activity's `onResume`: permissions and install state may have changed. */
    fun onResume() {
        channels.onResume()
        installer.onResume()
        scope.launch { shortcuts.reconcile() }
    }

    // --- Rows ----------------------------------------------------------------------------------

    /** The item keys of the row of category [categoryId], current as anything they depend on changes. */
    fun rowItems(categoryId: Int): Flow<List<String>> = container.categories.categories
        .map { list -> list.firstOrNull { it.id == categoryId } }
        .distinctUntilChanged()
        .flatMapLatest { c -> if (c == null) flowOf(emptyList()) else rowItems(c) }

    /**
     * The item keys of [category]'s row in display order: what exists of its kind, merged with
     * its stored order, hidden items removed, then sorted by its sort order. Now Playing is not
     * built here (the notification listener owns it) and gives an empty list.
     */
    fun rowItems(category: Category): Flow<List<String>> {
        val overrides = container.itemOverrides.overrides
        val available: Flow<List<String>> = when (category.type) {
            CategoryType.TV_APPS -> apps.tvApps.map { l -> l.map { it.key } }
            CategoryType.MOBILE_APPS -> apps.mobileApps.map { l -> l.map { it.key } }
            CategoryType.SHORTCUTS -> shortcuts.pinned.map { l -> l.map { it.key } }
            CategoryType.INPUTS -> inputs.inputs.map { l -> l.map { it.key } }
            CategoryType.LIVE_TV -> inputs.liveSources.map { l -> l.map { it.key } }
            CategoryType.WATCH_NEXT -> channels.watchNext.map { l -> l.map { ItemKeys.program(it.id) } }
            CategoryType.CHANNEL -> channels.channels
                .map { l -> l.firstOrNull { it.uniqueId == category.channelUniqueId }?.id }
                .distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else channels.programs(id).map { l -> l.map { p -> ItemKeys.program(p.id) } } }
            CategoryType.ACTIONS -> combine(channels.enabled, channels.available, container.settings.flow(Keys.Wallpaper.provider)) { _, _, _ ->
                actions.section(category.id).map { it.key }
            }
            CategoryType.FOLDERS -> container.categories.categories.map { l ->
                l.filter { it.type == CategoryType.MANUAL }.map { ItemKeys.folder(it.id) }
            }
            CategoryType.MANUAL -> combine(apps.byKey, shortcuts.pinnedByKey, inputs.byKey) { a, s, i ->
                CategoryItems.manual(category.items, category.hiddenItems) { key -> exists(key, a, s, i) }
            }
            else -> flowOf(emptyList())
        }
        if (category.type == CategoryType.MANUAL) {
            return combine(available, overrides) { keys, ov -> sort(category, keys, ov) }.distinctUntilChanged()
        }
        return combine(available, overrides) { keys, ov ->
            sort(category, CategoryItems.merge(category.items, keys, category.hiddenItems), ov)
        }.distinctUntilChanged()
    }

    private fun exists(key: String, apps: Map<String, AppInfo>, pinned: Map<String, *>, inputs: Map<String, *>): Boolean =
        when (AppKeys.kind(key)) {
            ItemKind.App -> key in apps
            ItemKind.Shortcut -> key in pinned
            ItemKind.Input -> key in inputs
            else -> true
        }

    private fun sort(category: Category, keys: List<String>, ov: Map<String, ItemOverride>): List<String> =
        sorter.sortKeys(keys, category.orderType, category.id, ::title) { ov[it] }

    /** The title a card shows for [itemKey]: the user's rename, else the item's own name. */
    fun title(itemKey: String): String {
        container.itemOverrides[itemKey]?.title?.takeIf { it.isNotBlank() }?.let { return it }
        return when (AppKeys.kind(itemKey)) {
            ItemKind.App -> apps[itemKey]?.label ?: AppKeys.packageOf(itemKey).orEmpty()
            ItemKind.Shortcut -> shortcuts.pinnedByKey.value[itemKey]?.label.orEmpty()
            ItemKind.Input -> inputs[itemKey]?.label.orEmpty()
            ItemKind.Program -> AppKeys.programId(itemKey)?.let { channels.program(it)?.title }.orEmpty()
            ItemKind.Action -> AppKeys.actionId(itemKey)?.let { actions.byId(it) }?.let { context.getString(it.label) }.orEmpty()
            ItemKind.Folder -> AppKeys.folderCategoryId(itemKey)?.let { container.categories.get(it)?.title }.orEmpty()
            ItemKind.Unknown -> ""
        }
    }

    // --- Launching -------------------------------------------------------------------------------

    /**
     * Opens the item behind [itemKey]: an app, a pinned shortcut, a TV input, a program, or an
     * external built-in action. Returns false for QLauncher's own panels (internal actions,
     * folders), which the home screen opens itself, and when nothing could be started. Successful
     * launches are recorded for the usage sort orders. Parental locks are checked by the caller.
     */
    fun launch(context: Context, itemKey: String, options: Bundle? = null): Boolean = when (AppKeys.kind(itemKey)) {
        ItemKind.App -> apps.launch(context, itemKey, options)
        ItemKind.Shortcut -> shortcuts.start(context, itemKey, null, options).also { if (it) container.itemOverrides.recordLaunch(itemKey) }
        ItemKind.Input -> inputs.open(context, itemKey, options)
        ItemKind.Program -> AppKeys.programId(itemKey)?.let { channels.program(it) }?.let { channels.open(context, it, options) } ?: false
        ItemKind.Action -> {
            val action = AppKeys.actionId(itemKey)?.let { actions.byId(it) }
            if (action != null && action.external) {
                actions.launch(context, action.id).also { if (it) container.itemOverrides.recordLaunch(itemKey) }
            } else {
                false
            }
        }
        ItemKind.Folder, ItemKind.Unknown -> false
    }

    // --- App menu -------------------------------------------------------------------------------

    /** *Launch on boot*: makes [itemKey] (an app or input) the *Autostart on boot* target. */
    fun setLaunchOnBoot(itemKey: String) {
        AppKeys.autostartValue(itemKey)?.let { container.settings.update(Keys.Power.autostartSource, it) }
    }

    fun isLaunchOnBoot(itemKey: String): Boolean =
        AppKeys.autostartValue(itemKey)?.let { it == container.settings.current[Keys.Power.autostartSource] } == true

    // --- Autostart on boot --------------------------------------------------------------------

    /**
     * The *Autostart on boot* list: None, the screensaver when it can be started, the TV inputs,
     * then every app by title.
     */
    fun autostartTargets(): List<AutostartTarget> = buildList {
        add(AutostartTarget("", context.getString(R.string.apps_autostart_none), AutostartTarget.Kind.None))
        if (actions.isScreensaverAvailable()) {
            add(AutostartTarget(AppKeys.AUTOSTART_SCREENSAVER, context.getString(R.string.apps_autostart_screensaver), AutostartTarget.Kind.Screensaver))
        }
        inputs.inputs.value.forEach { add(AutostartTarget(it.key, it.label, AutostartTarget.Kind.Input, it.key)) }
        apps.apps.value.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).forEach {
            add(AutostartTarget(AppKeys.autostartValue(it.key)!!, it.title, AutostartTarget.Kind.App, it.key))
        }
    }

    /** The label of a stored *Autostart on boot* value, for the row's subtitle. */
    fun autostartLabel(value: String): String = when {
        value.isEmpty() -> context.getString(R.string.apps_autostart_none)
        value == AppKeys.AUTOSTART_SCREENSAVER -> context.getString(R.string.apps_autostart_screensaver)
        else -> AppKeys.itemKeyOfAutostart(value)?.let(::title)?.ifEmpty { null } ?: value
    }

    /** Starts the *Autostart on boot* target [value] (for the system subsystem). */
    fun startAutostart(context: Context, value: String): Boolean = when {
        value.isEmpty() -> false
        value == AppKeys.AUTOSTART_SCREENSAVER -> actions.startScreensaver(context)
        else -> AppKeys.itemKeyOfAutostart(value)?.let { launch(context, it) } ?: false
    }

    /** The intent for an *Autostart on boot* value, or null for none and unknown values. */
    fun autostartIntent(value: String): Intent? = when {
        value.isEmpty() -> null
        value == AppKeys.AUTOSTART_SCREENSAVER -> actions.screensaverIntent()
        else -> {
            val key = AppKeys.itemKeyOfAutostart(value)
            when {
                key == null -> null
                AppKeys.kind(key) == ItemKind.Input -> inputs[key]?.let(inputs::openIntent)
                else -> apps[key]?.let(apps::launchIntent) ?: AppKeys.component(key)?.let {
                    Intent(Intent.ACTION_MAIN).setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }
}
