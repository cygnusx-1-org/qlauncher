/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategorySize
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.SortOrder
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.shared.Channel
import org.cygnusx1.qlauncher.shared.MediaAction
import org.cygnusx1.qlauncher.ui.components.ColorPickerPanel
import org.cygnusx1.qlauncher.ui.components.PanelRow
import org.cygnusx1.qlauncher.ui.components.PanelState
import org.cygnusx1.qlauncher.ui.components.PanelTrailing
import org.cygnusx1.qlauncher.ui.components.SidePanel
import org.cygnusx1.qlauncher.ui.components.StepSlider
import org.cygnusx1.qlauncher.ui.components.TextEntryPanel
import org.cygnusx1.qlauncher.ui.home.BuiltInAction
import org.cygnusx1.qlauncher.ui.home.CardItem
import org.cygnusx1.qlauncher.ui.home.CardKind
import org.cygnusx1.qlauncher.ui.home.HomeController
import org.cygnusx1.qlauncher.ui.home.HomeInputs
import org.cygnusx1.qlauncher.ui.home.HomeRow
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.cygnusx1.qlauncher.ui.components.WallpaperGallery
import org.cygnusx1.qlauncher.ui.components.GalleryItem

/** The side panels the home screen opens. */
@Immutable
sealed interface PanelSpec {
    data object Root : PanelSpec
    data object AllApps : PanelSpec
    data object EditCategories : PanelSpec
    data class CategoryEditor(val id: Int) : PanelSpec
    data class CategoryAdvanced(val id: Int) : PanelSpec
    data class CategorySort(val id: Int) : PanelSpec
    data class ManageApps(val id: Int) : PanelSpec
    data class HiddenItems(val id: Int) : PanelSpec
    data class DeleteCategory(val id: Int) : PanelSpec
    data class CategoryColor(val id: Int) : PanelSpec
    data class AppMenu(val categoryId: Int, val key: String) : PanelSpec
    data class PickCategory(val sourceId: Int, val key: String, val move: Boolean) : PanelSpec
    data class Rename(val itemKey: String?, val categoryId: Int?) : PanelSpec
    data class ChangeIcon(val itemKey: String?, val categoryId: Int?) : PanelSpec
    data class Pictures(val items: List<org.cygnusx1.qlauncher.wallpaper.source.MediaItem>, val itemKey: String?, val categoryId: Int?) : PanelSpec
    data class Folder(val categoryId: Int) : PanelSpec
    data object Notifications : PanelSpec
    data object Channels : PanelSpec
    data class ChannelApp(val packageName: String) : PanelSpec
}

/** What the panels ask the activity to do. */
interface HomeActions {
    fun open(card: CardItem, row: HomeRow?)
    fun runAction(action: BuiltInAction)
    fun appInfo(packageName: String)
    fun uninstall(packageName: String)
    fun isLaunchOnBoot(key: String): Boolean
    fun setLaunchOnBoot(key: String)
    fun setParental(key: String, locked: Boolean)
    /** The system image picker (*Other location…*); only offered when [hasImagePicker]. */
    fun pickImage(onPicked: (String) -> Unit)

    /** Whether the TV has a system image picker. */
    fun hasImagePicker(): Boolean

    /** The pictures QLauncher can list itself, after asking for media access; not called when there are none. */
    fun findPictures(onFound: (List<org.cygnusx1.qlauncher.wallpaper.source.MediaItem>) -> Unit)

    /** Installed icon packs: (package, label). */
    fun iconPacks(): List<Pair<String, String>>

    /** Picks one icon from [pack] for [itemKey] with the pack's own picker. */
    fun pickIconFromPack(pack: String, itemKey: String)
    fun mediaAction(action: MediaAction)
    fun requestNotificationAccess()
    fun startCardReorder(categoryId: Int, key: String)

    /** The app's own launcher shortcuts: (label, launch). */
    fun appShortcuts(packageName: String): List<Pair<String, () -> Unit>>
}

/** Renders the top panel of [stack]. */
@Composable
fun HomePanelHost(stack: SnapshotStateList<PanelSpec>, controller: HomeController, actions: HomeActions) {
    val spec = stack.lastOrNull() ?: return
    val nav = PanelNav(stack)
    // Each stacked panel keeps its selection, so BACK returns to the row that opened a sub-panel.
    val states = remember { mutableMapOf<PanelSpec, PanelState>() }
    states.keys.retainAll(stack.toSet())
    val state = states.getOrPut(spec) { PanelState() }
    when (spec) {
        is PanelSpec.CategoryColor -> {
            val inputs by controller.inputs.collectAsStateWithLifecycle()
            val c = inputs.categories.firstOrNull { it.id == spec.id }
            ColorPickerPanel(
                initial = c?.backgroundColor ?: 0x80000000.toInt(),
                allowDynamic = true,
                onResult = { v ->
                    controller.updateCategory(spec.id) { it.copy(backgroundColor = v) }
                    nav.pop()
                },
                onCancel = { nav.pop() },
            )
        }
        is PanelSpec.Rename -> RenamePanel(spec, controller, nav)
        // *Change icon › From picture*: QLauncher's own gallery, so no file manager is needed.
        is PanelSpec.Pictures -> WallpaperGallery(
            items = spec.items.map { GalleryItem(it.uri, it.name) },
            onPick = { i ->
                spec.items.getOrNull(i)?.let { picture ->
                    val uri = picture.uri.toString()
                    if (spec.itemKey != null) controller.setItemIcon(spec.itemKey, uri) else spec.categoryId?.let { id -> controller.updateCategory(id) { it.copy(customIconUri = uri) } }
                }
                nav.pop()
                nav.pop()
            },
            onBack = { nav.pop() },
            title = stringResource(R.string.menu_change_icon),
        )
        else -> key(spec) {
            val built = panelContent(spec, controller, actions, nav)
            SidePanel(
                rows = built.rows,
                state = state,
                onBack = { nav.pop() },
                title = built.title,
                titleIcon = built.icon,
                header = built.header,
                wrap = built.wrap,
                tag = "panel_" + spec::class.simpleName.orEmpty().lowercase(),
            )
        }
    }
}

/** Stack navigation for panels. */
class PanelNav(private val stack: SnapshotStateList<PanelSpec>) {
    fun push(spec: PanelSpec) {
        stack.add(spec)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun close() = stack.clear()

    /** Replaces the top panel. */
    fun replace(spec: PanelSpec) {
        pop()
        push(spec)
    }
}

private class Built(
    val rows: List<PanelRow>,
    val title: String? = null,
    val icon: String? = null,
    val header: (@Composable BoxScope.() -> Unit)? = null,
    val wrap: Boolean = false,
)

@Composable
private fun panelContent(spec: PanelSpec, controller: HomeController, actions: HomeActions, nav: PanelNav): Built {
    val inputs by controller.inputs.collectAsStateWithLifecycle()
    val homeRows by controller.rows.collectAsStateWithLifecycle()
    val source = controller.sources.notifications
    val notifications = source?.notifications?.collectAsStateWithLifecycle()?.value.orEmpty()
    val nowPlaying = source?.nowPlaying?.collectAsStateWithLifecycle()?.value
    val notificationAccess = source?.accessGranted?.collectAsStateWithLifecycle()?.value == true
    // Collected here so a panel showing channels rebuilds when they or the categories change.
    val channels = controller.sources.programs?.channels?.collectAsStateWithLifecycle()?.value.orEmpty()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    fun s(id: Int, vararg a: Any) = resources.getString(id, *a)
    fun cat(id: Int): Category? = inputs.categories.firstOrNull { it.id == id }

    return when (spec) {
        PanelSpec.Root -> Built(
            rows = buildList {
                BuiltInAction.sections.forEach { (section, title) ->
                    val acts = inputs.actions.forSection(section)
                    if (acts.isEmpty()) return@forEach
                    add(PanelRow.Section("s$section", s(title)))
                    acts.forEach { a -> add(PanelRow.Item(a.id, s(a.title), icon = a.icon, onClick = { actions.runAction(a) })) }
                }
            },
            header = { RootHeader() },
            wrap = true,
        )

        PanelSpec.AllApps -> {
            val tv = inputs.apps.filter { it.isTv }.sortedBy { it.label.lowercase() }
            val mobile = inputs.apps.filter { !it.isTv && it.isMobile }.sortedBy { it.label.lowercase() }
            Built(
                title = s(R.string.home_action_all_apps), icon = "apps",
                rows = buildList {
                    listOf(R.string.home_cat_tv_apps to tv, R.string.home_cat_mobile_apps to mobile).forEach { (t, list) ->
                        if (list.isEmpty()) return@forEach
                        add(PanelRow.Section("s$t", s(t)))
                        list.forEach { a ->
                            val o = inputs.overrides[a.key]
                            val card = CardItem(a.key, o?.title ?: a.label, if (a.isTv) CardKind.TvApp else CardKind.MobileApp, packageName = a.packageName)
                            add(
                                PanelRow.Item(
                                    a.key, card.title,
                                    leading = { BannerThumb(controller.sources.images.app(a.key, a.isTv, o?.iconUri), a.isTv) },
                                    onClick = { actions.open(card, null) },
                                ),
                            )
                        }
                    }
                },
            )
        }

        PanelSpec.EditCategories -> Built(
            title = s(R.string.home_action_edit_categories), icon = "list",
            rows = buildList {
                add(
                    PanelRow.Item("new", s(R.string.home_new_category), icon = "add", onClick = {
                        scope.launch {
                            val id = controller.newCategory(s(R.string.home_new_category))
                            nav.push(PanelSpec.CategoryEditor(id))
                        }
                    }),
                )
                controller.allCategories().forEach { (c, title) ->
                    add(
                        PanelRow.Item(
                            "c${c.id}", title, icon = org.cygnusx1.qlauncher.ui.home.BuiltInCategories.icon(c),
                            trailing = PanelTrailing.Hidden(!c.isVisible),
                            onClick = { nav.push(PanelSpec.CategoryEditor(c.id)) },
                        ),
                    )
                }
            },
        )

        is PanelSpec.CategoryEditor -> {
            val c = cat(spec.id) ?: return Built(emptyList())
            categoryEditor(c, controller, actions, nav, inputs.settings[Keys.Internal.defaultCategoryId] == c.id, notificationAccess, ::s)
        }

        is PanelSpec.CategoryAdvanced -> {
            val c = cat(spec.id) ?: return Built(emptyList())
            val global = inputs.settings
            fun percentRow(key: String, title: Int, value: Int, autoValue: Int, setter: (Int) -> Category.(Int) -> Category, max: Int = 100, autoAt: Set<Int> = setOf(0)) =
                sliderItem(
                    key, s(title), value, 0, max,
                    label = { v -> if (v in autoAt) s(R.string.editor_auto_value, "$autoValue%") else "$v%" },
                    onChange = { v -> controller.updateCategory(c.id) { cc -> setter(v)(cc, v) } },
                )
            Built(
                title = s(R.string.editor_advanced), icon = "tune",
                rows = buildList {
                    add(percentRow("start", R.string.editor_start_margin, c.startMargin, (global[Keys.Categories.startMargin] * 100).toInt(), { { v -> copy(startMargin = v) } }))
                    add(percentRow("end", R.string.editor_end_margin, c.endMargin, (global[Keys.Categories.endMargin] * 100).toInt(), { { v -> copy(endMargin = v) } }, autoAt = setOf(0, 100)))
                    add(
                        sliderItem(
                            "top", s(R.string.editor_top_margin), c.topMargin, 0, 100,
                            label = { v -> if (v == 0) s(R.string.editor_auto) else "$v%" },
                            onChange = { v -> controller.updateCategory(c.id) { it.copy(topMargin = v) } },
                        ),
                    )
                    if (c.isMultiline) {
                        add(sliderItem("per_row", s(R.string.editor_cards_per_row), c.maxItemsPerLine, 0, 20, label = { v -> if (v == 0) s(R.string.editor_auto) else "$v" }, onChange = { v -> controller.updateCategory(c.id) { it.copy(maxItemsPerLine = v) } }))
                        add(sliderItem("rows", s(R.string.editor_row_count), c.maxVisibleRows, 0, 20, label = { v -> if (v == 0) s(R.string.editor_auto) else "$v" }, onChange = { v -> controller.updateCategory(c.id) { it.copy(maxVisibleRows = v) } }))
                    }
                },
            )
        }

        is PanelSpec.CategorySort -> {
            val c = cat(spec.id) ?: return Built(emptyList())
            val appsLike = c.type in setOf(CategoryType.MANUAL, CategoryType.TV_APPS, CategoryType.MOBILE_APPS, CategoryType.WATCH_NEXT)
            val options = listOf(
                SortOrder.CUSTOM to R.string.editor_sort_custom,
                SortOrder.NAME to R.string.editor_sort_name,
                SortOrder.LAST_USED to R.string.editor_sort_last,
                SortOrder.MOST_USED to R.string.editor_sort_most,
                SortOrder.RANDOM to R.string.editor_sort_random,
            ).filter { (v, _) -> appsLike || (v != SortOrder.LAST_USED && v != SortOrder.MOST_USED) }
            Built(
                title = s(R.string.editor_sort), icon = "sort",
                rows = options.map { (v, t) ->
                    PanelRow.Item("o$v", s(t), radio = c.orderType == v, onClick = {
                        controller.updateCategory(c.id) { it.copy(orderType = v) }
                        nav.pop()
                    })
                },
            )
        }

        is PanelSpec.ManageApps -> {
            val c = cat(spec.id) ?: return Built(emptyList())
            val tv = inputs.apps.filter { it.isTv }.sortedBy { it.label.lowercase() }
            val mobile = inputs.apps.filter { !it.isTv && it.isMobile }.sortedBy { it.label.lowercase() }
            val manual = c.type == CategoryType.MANUAL
            if (c.type == CategoryType.WATCH_NEXT) {
                // Watch Next is managed per app: a hidden app's programs are left out.
                val packages = inputs.watchNext.map { it.packageName }.distinct()
                val labels = inputs.apps.associate { it.packageName to it.label }
                return Built(
                    title = s(R.string.editor_manage_apps), icon = "apps",
                    rows = packages.map { pkg ->
                        val hiddenKey = org.cygnusx1.qlauncher.ui.home.HomeContent.packageKey(pkg)
                        val member = hiddenKey !in c.hiddenItems
                        PanelRow.Item(pkg, labels[pkg] ?: pkg, trailing = PanelTrailing.Switch(member), onClick = {
                            if (member) controller.hide(c.id, hiddenKey) else controller.restore(c.id, hiddenKey)
                        })
                    },
                )
            }
            val groups = when (c.type) {
                CategoryType.TV_APPS -> listOf(R.string.home_cat_tv_apps to tv)
                CategoryType.MOBILE_APPS -> listOf(R.string.home_cat_mobile_apps to mobile)
                else -> listOf(R.string.home_cat_tv_apps to tv, R.string.home_cat_mobile_apps to mobile)
            }
            Built(
                title = s(R.string.editor_manage_apps), icon = "apps",
                rows = buildList {
                    groups.forEach { (t, list) ->
                        if (list.isEmpty()) return@forEach
                        add(PanelRow.Section("s$t", s(t)))
                        list.forEach { a ->
                            val member = if (manual) a.key in c.items else a.key !in c.hiddenItems
                            add(
                                PanelRow.Item(a.key, inputs.overrides[a.key]?.title ?: a.label, trailing = PanelTrailing.Switch(member), onClick = {
                                    when {
                                        manual && member -> controller.removeFrom(c.id, a.key)
                                        manual -> controller.addTo(c.id, a.key)
                                        member -> controller.hide(c.id, a.key)
                                        else -> controller.restore(c.id, a.key)
                                    }
                                }),
                            )
                        }
                    }
                },
            )
        }

        is PanelSpec.HiddenItems -> {
            val c = cat(spec.id) ?: return Built(emptyList())
            val names = inputs.apps.associate { it.key to (inputs.overrides[it.key]?.title ?: it.label) }
            Built(
                title = s(R.string.editor_hidden_items), icon = "visibility_off",
                rows = c.hiddenItems.map { k ->
                    PanelRow.Item(k, names[k] ?: k, subtitle = s(R.string.editor_restore), icon = "visibility", onClick = { controller.restore(c.id, k) })
                },
            )
        }

        is PanelSpec.DeleteCategory -> Built(
            title = s(R.string.editor_delete_confirm), icon = "delete",
            rows = listOf(
                PanelRow.Item("no", s(R.string.firstrun_no), radio = false, onClick = { nav.pop() }),
                PanelRow.Item("yes", s(R.string.firstrun_yes), radio = false, onClick = {
                    controller.delete(spec.id)
                    nav.pop()
                    nav.pop()
                }),
            ),
        )

        is PanelSpec.AppMenu -> appMenu(spec, homeRows, inputs, channels, controller, actions, nav, nowPlaying, ::s)

        is PanelSpec.PickCategory -> {
            val source = cat(spec.sourceId)
            Built(
                title = s(if (spec.move) R.string.menu_move_to else R.string.menu_add_to), icon = if (spec.move) "drive_file_move" else "playlist_add",
                rows = controller.manualCategories().filter { it.first.id != spec.sourceId }.map { (c, t) ->
                    PanelRow.Item("c${c.id}", t, icon = org.cygnusx1.qlauncher.ui.home.BuiltInCategories.icon(c), onClick = {
                        if (spec.move && source != null) controller.moveTo(source, c.id, spec.key) else controller.addTo(c.id, spec.key)
                        nav.close()
                    })
                },
            )
        }

        is PanelSpec.ChangeIcon -> {
            val current = spec.itemKey?.let { inputs.overrides[it]?.iconUri } ?: spec.categoryId?.let { cat(it)?.customIconUri }
            Built(
                title = s(R.string.menu_change_icon), icon = "image",
                rows = buildList {
                    add(PanelRow.Item("picture", s(R.string.menu_icon_from_picture), icon = "photo_library", onClick = {
                        actions.findPictures { pictures -> nav.push(PanelSpec.Pictures(pictures, spec.itemKey, spec.categoryId)) }
                    }))
                    if (actions.hasImagePicker()) {
                        add(PanelRow.Item("picture_other", s(R.string.menu_icon_other_location), icon = "folder_open", onClick = {
                            actions.pickImage { uri ->
                                if (spec.itemKey != null) controller.setItemIcon(spec.itemKey, uri) else spec.categoryId?.let { id -> controller.updateCategory(id) { it.copy(customIconUri = uri) } }
                            }
                            nav.pop()
                        }))
                    }
                    if (spec.itemKey != null) {
                        actions.iconPacks().forEach { (pkg, label) ->
                            add(PanelRow.Item("pack_$pkg", label, icon = "apps", onClick = {
                                actions.pickIconFromPack(pkg, spec.itemKey)
                                nav.pop()
                            }))
                        }
                    }
                    if (current != null) {
                        add(PanelRow.Item("reset", s(R.string.menu_icon_reset), icon = "restart_alt", onClick = {
                            if (spec.itemKey != null) controller.setItemIcon(spec.itemKey, null) else spec.categoryId?.let { id -> controller.updateCategory(id) { it.copy(customIconUri = null) } }
                            nav.pop()
                        }))
                    }
                },
            )
        }

        is PanelSpec.Folder -> {
            val c = cat(spec.categoryId) ?: return Built(emptyList())
            val items = controller.itemsOf(c)
            Built(
                title = controller.title(c), icon = "folder",
                rows = items.map { card ->
                    PanelRow.Item(
                        card.key, card.title,
                        leading = { BannerThumb(card.image, card.kind == CardKind.TvApp) },
                        onClick = { actions.open(card, null) },
                    )
                },
            )
        }

        PanelSpec.Notifications -> notificationsPanel(source, notifications, ::s)

        PanelSpec.Channels -> channelsPanel(inputs, channels, controller, nav, ::s)

        is PanelSpec.ChannelApp -> channelAppPanel(spec.packageName, inputs, channels, controller)

        is PanelSpec.CategoryColor, is PanelSpec.Rename, is PanelSpec.Pictures -> Built(emptyList())
    }
}

private fun categoryEditor(
    c: Category,
    controller: HomeController,
    actions: HomeActions,
    nav: PanelNav,
    isDefault: Boolean,
    notificationAccess: Boolean,
    s: (Int, Array<out Any>) -> String,
): Built {
    fun t(id: Int, vararg a: Any) = s(id, a)
    val title = controller.title(c)
    val empty = controller.itemsOf(c).isEmpty()
    val manageable = c.type in setOf(CategoryType.MANUAL, CategoryType.TV_APPS, CategoryType.MOBILE_APPS, CategoryType.WATCH_NEXT)
    val rows = buildList {
        if (c.type == CategoryType.NOW_PLAYING && !notificationAccess) {
            add(PanelRow.Item("np_permission", t(R.string.editor_request_notifications), icon = "notifications", onClick = { actions.requestNotificationAccess() }))
        }
        if (c.isVisible && empty && !c.isUserCategory) {
            add(PanelRow.Item("empty_note", t(R.string.editor_empty_note), icon = "info", enabled = false))
        }
        add(PanelRow.Item("title", t(R.string.editor_title), subtitle = title, icon = "title", onClick = { nav.push(PanelSpec.Rename(null, c.id)) }))
        add(PanelRow.Item("icon", t(R.string.editor_change_icon), icon = "image", onClick = { nav.push(PanelSpec.ChangeIcon(null, c.id)) }))
        add(PanelRow.Item("visible", t(R.string.editor_visible), icon = "visibility", trailing = PanelTrailing.Switch(c.isVisible), onClick = { controller.updateCategory(c.id) { it.copy(isVisible = !it.isVisible) } }))
        add(PanelRow.Item("grid", t(R.string.editor_grid), icon = "grid_view", trailing = PanelTrailing.Switch(c.isMultiline), onClick = { controller.updateCategory(c.id) { it.copy(isMultiline = !it.isMultiline) } }))
        if (c.isMultiline) {
            add(PanelRow.Item("background", t(R.string.editor_background), icon = "format_color_fill", onClick = { nav.push(PanelSpec.CategoryColor(c.id)) }))
        } else {
            add(PanelRow.Item("sliding", t(R.string.editor_sliding_row), icon = "view_carousel", trailing = PanelTrailing.Switch(!c.hasMovingSelection), onClick = { controller.updateCategory(c.id) { it.copy(hasMovingSelection = !it.hasMovingSelection) } }))
        }
        add(PanelRow.Item("show_title", t(R.string.editor_show_title), icon = "subtitles", trailing = PanelTrailing.Switch(!c.isAppTitleHidden), onClick = { controller.updateCategory(c.id) { it.copy(isAppTitleHidden = !it.isAppTitleHidden) } }))
        val size = c.size
        add(
            sliderItem(
                "size", t(R.string.editor_size), size.ordinal, 0, CategorySize.entries.size - 1,
                label = { v -> CategorySize.entries[v].label },
                onChange = { v -> controller.updateCategory(c.id) { it.copy(scale = CategorySize.entries[v].scale) } },
                icon = "photo_size_select_large",
            ),
        )
        add(PanelRow.Item("advanced", t(R.string.editor_advanced), icon = "tune", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.CategoryAdvanced(c.id)) }))
        add(PanelRow.Item("sort", t(R.string.editor_sort), subtitle = t(sortLabel(c.orderType)), icon = "sort", onClick = { nav.push(PanelSpec.CategorySort(c.id)) }))
        add(
            PanelRow.Item(
                "default", t(R.string.editor_set_default), subtitle = if (isDefault) t(R.string.editor_is_default) else null, icon = "home",
                onClick = { controller.setDefault(c.id) },
            ),
        )
        if (manageable) add(PanelRow.Item("manage", t(R.string.editor_manage_apps), icon = "apps", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.ManageApps(c.id)) }))
        if (c.hiddenItems.isNotEmpty()) add(PanelRow.Item("hidden", t(R.string.editor_hidden_items), icon = "visibility_off", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.HiddenItems(c.id)) }))
        if (c.id >= CategoryIds.FIRST_USER && c.type == CategoryType.MANUAL) {
            add(PanelRow.Item("delete", t(R.string.editor_delete), icon = "delete", onClick = { nav.push(PanelSpec.DeleteCategory(c.id)) }))
        }
    }
    return Built(rows, title = title, icon = org.cygnusx1.qlauncher.ui.home.BuiltInCategories.icon(c))
}

private fun sortLabel(order: Int) = when (order) {
    SortOrder.NAME -> R.string.editor_sort_name
    SortOrder.LAST_USED -> R.string.editor_sort_last
    SortOrder.MOST_USED -> R.string.editor_sort_most
    SortOrder.RANDOM -> R.string.editor_sort_random
    else -> R.string.editor_sort_custom
}

private fun appMenu(
    spec: PanelSpec.AppMenu,
    rows: List<HomeRow>,
    inputs: HomeInputs,
    channels: List<Channel>,
    controller: HomeController,
    actions: HomeActions,
    nav: PanelNav,
    np: org.cygnusx1.qlauncher.shared.NowPlaying?,
    s: (Int, Array<out Any>) -> String,
): Built {
    fun t(id: Int, vararg a: Any) = s(id, a)
    val row = rows.firstOrNull { it.id == spec.categoryId }
    val category = inputs.categories.firstOrNull { it.id == spec.categoryId }
    val card = row?.items?.firstOrNull { it.key == spec.key } ?: return Built(emptyList())
    val isApp = ItemKeys.isApp(card.key)
    val pkg = card.packageName
    val rows = buildList {
        if (isApp && pkg != null) add(PanelRow.Item("app_settings", t(R.string.menu_app_settings), icon = "settings", onClick = { actions.appInfo(pkg); nav.close() }))
        add(PanelRow.Item("open", t(R.string.menu_open), icon = "open_in_new", onClick = { nav.close(); actions.open(card, row) }))
        if (card.kind == CardKind.Program) add(PanelRow.Item("details", t(R.string.menu_details), icon = "info", onClick = { nav.close(); actions.open(card, row) }))
        if (card.kind == CardKind.NowPlaying) {
            if (np?.canPlayPause == true) add(PanelRow.Item("pp", t(R.string.menu_play_pause), icon = "play_arrow", onClick = { actions.mediaAction(MediaAction.PlayPause) }))
            if (np?.canStop == true) add(PanelRow.Item("stop", t(R.string.menu_stop), icon = "stop", onClick = { actions.mediaAction(MediaAction.Stop) }))
            if (np?.canSkipPrevious == true) add(PanelRow.Item("prev", t(R.string.menu_previous), icon = "skip_previous", onClick = { actions.mediaAction(MediaAction.Previous) }))
            if (np?.canSkipNext == true) add(PanelRow.Item("next", t(R.string.menu_next), icon = "skip_next", onClick = { actions.mediaAction(MediaAction.Next) }))
        }
        if (isApp && pkg != null) add(PanelRow.Item("uninstall", t(R.string.menu_uninstall), icon = "delete", onClick = { actions.uninstall(pkg); nav.close() }))
        if (isApp || card.kind == CardKind.Input) {
            val on = actions.isLaunchOnBoot(card.key)
            add(PanelRow.Item("boot", t(R.string.menu_launch_on_boot), subtitle = if (on) t(R.string.menu_launch_on_boot_on) else null, icon = "power_settings_new", onClick = { actions.setLaunchOnBoot(card.key) }))
        }
        if (isApp) {
            val locked = card.parentalLocked
            add(PanelRow.Item("parental", t(if (locked) R.string.menu_remove_parental else R.string.menu_add_parental), icon = if (locked) "lock_open" else "lock", onClick = { actions.setParental(card.key, !locked); nav.close() }))
        }
        if (pkg != null && isApp) {
            val shortcuts = actions.appShortcuts(pkg)
            if (shortcuts.isNotEmpty()) {
                add(PanelRow.Section("shortcuts", t(R.string.menu_shortcuts)))
                shortcuts.forEachIndexed { i, (label, launch) -> add(PanelRow.Item("sc$i", label, icon = "shortcut", onClick = { nav.close(); launch() })) }
            }
        }
        val visibility = buildList {
            if (row.items.size > 1 && category != null && controller.canReorder(category)) add(PanelRow.Item("reorder", t(R.string.menu_reorder), icon = "swap_horiz", onClick = { nav.close(); actions.startCardReorder(row.id, card.key) }))
            if (category != null) {
                if (category.type == CategoryType.MANUAL) {
                    add(PanelRow.Item("remove", t(R.string.menu_remove_from, row.title), icon = "remove_circle_outline", onClick = { controller.removeFrom(category.id, card.key); nav.close() }))
                } else {
                    add(PanelRow.Item("hide", t(R.string.menu_hide), icon = "visibility_off", onClick = { controller.hide(category.id, card.key); nav.close() }))
                }
            }
            if (isApp) {
                add(PanelRow.Item("add_to", t(R.string.menu_add_to), icon = "playlist_add", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.PickCategory(spec.categoryId, card.key, move = false)) }))
                add(PanelRow.Item("move_to", t(R.string.menu_move_to), icon = "drive_file_move", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.PickCategory(spec.categoryId, card.key, move = true)) }))
                if (pkg != null && channels.any { it.packageName == pkg }) {
                    add(PanelRow.Item("channels", t(R.string.menu_channels), icon = "live_tv", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.ChannelApp(pkg)) }))
                }
            }
        }
        if (visibility.isNotEmpty()) {
            add(PanelRow.Section("visibility", t(R.string.menu_visibility)))
            addAll(visibility)
        }
        if (card.kind != CardKind.NowPlaying) {
            add(PanelRow.Section("appearance", t(R.string.menu_appearance)))
            add(PanelRow.Item("title", t(R.string.menu_title), subtitle = card.title, icon = "title", onClick = { nav.push(PanelSpec.Rename(card.key, null)) }))
            add(PanelRow.Item("icon", t(R.string.menu_change_icon), icon = "image", onClick = { nav.push(PanelSpec.ChangeIcon(card.key, null)) }))
            add(PanelRow.Item("crop", t(R.string.menu_crop), icon = "crop", trailing = PanelTrailing.Switch(card.cropToFill), onClick = { controller.toggleCrop(card.key) }))
        }
    }
    return Built(rows, title = card.title, wrap = true)
}

private fun notificationsPanel(
    source: org.cygnusx1.qlauncher.shared.NotificationSource?,
    items: List<org.cygnusx1.qlauncher.shared.NotificationItem>,
    s: (Int, Array<out Any>) -> String,
): Built {
    fun t(id: Int) = s(id, emptyArray())
    val rows = buildList {
        items.groupBy { it.appLabel }.forEach { (app, list) ->
            add(PanelRow.Section("s$app", app))
            list.forEach { n ->
                val iconUri = n.iconUri
                add(
                    PanelRow.Item(
                        n.key, n.title ?: app, subtitle = n.text,
                        icon = if (iconUri == null) "notifications" else null,
                        // Small notification icons are often black glyphs: a light disc keeps them visible.
                        leading = iconUri?.let { uri ->
                            {
                                Box(Modifier.size(32.dp).background(Color.White.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
                                    AsyncImage(uri, null, Modifier.size(22.dp))
                                }
                            }
                        },
                        leadingWide = false,
                        onClick = { source?.open(n) },
                    ),
                )
            }
        }
        if (items.isEmpty()) add(PanelRow.Item("empty", t(R.string.notifications_empty), enabled = false))
        else add(PanelRow.Item("clear", t(R.string.notifications_clear_all), icon = "clear_all", onClick = { source?.clearAll() }))
    }
    return Built(rows, title = t(R.string.notifications_title), icon = "notifications")
}

private fun channelsPanel(
    inputs: HomeInputs,
    channels: List<Channel>,
    controller: HomeController,
    nav: PanelNav,
    s: (Int, Array<out Any>) -> String,
): Built {
    val labels = inputs.apps.associate { it.packageName to it.label }
    val watchNext = inputs.categories.firstOrNull { it.id == CategoryIds.WATCH_NEXT }
    val rows = buildList {
        if (watchNext != null) {
            add(
                PanelRow.Item(
                    "watch_next", s(R.string.home_cat_watch_next, emptyArray()), icon = "playlist_play", trailing = PanelTrailing.Switch(watchNext.isVisible),
                    onClick = { controller.updateCategory(watchNext.id) { it.copy(isVisible = !it.isVisible) } },
                ),
            )
        }
        channels.groupBy { it.packageName }.forEach { (pkg, list) ->
            val sub = if (list.size == 1) list.first().displayName else s(R.string.channels_count, arrayOf(list.size))
            add(PanelRow.Item(pkg, labels[pkg] ?: pkg, subtitle = sub, icon = "live_tv", trailing = PanelTrailing.Chevron, onClick = { nav.push(PanelSpec.ChannelApp(pkg)) }))
        }
    }
    return Built(rows, title = s(R.string.channels_title, emptyArray()), icon = "live_tv")
}

private fun channelAppPanel(pkg: String, inputs: HomeInputs, channels: List<Channel>, controller: HomeController): Built {
    val cats = inputs.categories.filter { it.type == CategoryType.CHANNEL }
    val label = inputs.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    val rows = channels.filter { it.packageName == pkg }.map { ch ->
        val c = cats.firstOrNull { it.channelUniqueId == ch.uniqueId }
        PanelRow.Item(
            "ch${ch.id}", ch.displayName, icon = "live_tv", trailing = PanelTrailing.Switch(c?.isVisible == true),
            onClick = { c?.let { cc -> controller.updateCategory(cc.id) { it.copy(isVisible = !it.isVisible) } } },
        )
    }
    return Built(rows, title = label, icon = "live_tv")
}

/** A row with a stepped slider under its title; LEFT/RIGHT change it. */
private fun sliderItem(
    key: String,
    title: String,
    value: Int,
    min: Int,
    max: Int,
    label: (Int) -> String,
    onChange: (Int) -> Unit,
    icon: String? = null,
): PanelRow.Item = PanelRow.Item(
    key, title, subtitle = label(value), icon = icon,
    onHorizontal = { d ->
        val next = (value + d).coerceIn(min, max)
        if (next != value) onChange(next)
        true
    },
    below = { _ ->
        Spacer(Modifier.height(6.dp))
        StepSlider(
            position = if (max == min) 0f else (value - min).toFloat() / (max - min),
            origin = 0f,
            stops = max - min + 1,
            bubble = null,
            modifier = Modifier.fillMaxWidth(),
            height = 20.dp,
            track = 10.dp,
        )
    },
)

/** The root panel's header: the app mark and today's date. */
@Composable
private fun BoxScope.RootHeader() {
    val accent = QTheme.colors.accent
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 24.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("Q", color = Color.Black, style = QTheme.type.panelTitle)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), color = Color.White, style = QTheme.type.panelTitle)
    }
    val date = remember { SimpleDateFormat("EEE MMM d, yyyy", Locale.getDefault()).format(Date()) }
    Text(
        date,
        color = Color.White.copy(alpha = 0.8f),
        style = QTheme.type.panelRow,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun BannerThumb(model: Any?, banner: Boolean) {
    Box(Modifier.size(64.dp, 36.dp).background(Color(0xFF333333), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(model, null, Modifier.size(if (banner) 64.dp else 30.dp, if (banner) 36.dp else 30.dp))
        } else {
            MaterialIcon("apps", size = 20.dp, tint = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun RenamePanel(spec: PanelSpec.Rename, controller: HomeController, nav: PanelNav) {
    val inputs by controller.inputs.collectAsStateWithLifecycle()
    val rows by controller.rows.collectAsStateWithLifecycle()
    val current = when {
        spec.itemKey != null -> inputs.overrides[spec.itemKey]?.title ?: rows.flatMap { it.items }.firstOrNull { it.key == spec.itemKey }?.title.orEmpty()
        spec.categoryId != null -> inputs.categories.firstOrNull { it.id == spec.categoryId }?.let { controller.title(it) }.orEmpty()
        else -> ""
    }
    TextEntryPanel(
        title = stringResource(R.string.menu_title),
        hint = stringResource(R.string.menu_rename_hint),
        initial = current,
        onDone = { v ->
            if (spec.itemKey != null) controller.setItemTitle(spec.itemKey, v.ifBlank { null })
            else if (spec.categoryId != null && v.isNotBlank()) controller.updateCategory(spec.categoryId) { it.copy(title = v) }
            nav.pop()
        },
        onCancel = { nav.pop() },
    )
}
