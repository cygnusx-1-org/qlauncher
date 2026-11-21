/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.ui.settings.SettingsSlots
import org.cygnusx1.qlauncher.ui.settings.model.LeftPane
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text
import org.cygnusx1.qlauncher.wallpaper.ProviderSelection
import org.cygnusx1.qlauncher.wallpaper.WallpaperModule
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditPreset
import org.cygnusx1.qlauncher.wallpaper.reddit.RedditPresets
import org.cygnusx1.qlauncher.wallpaper.source.WikimediaCategory
import org.cygnusx1.qlauncher.util.DocumentPickers

/** The wallpaper source pages: *Configure* per source and *About › This wallpaper*. */
object WallpaperPages {
    private class State(m: WallpaperModule, scope: kotlinx.coroutines.CoroutineScope) {
        val wikiCategory: StateFlow<WikimediaCategory?> = m.wikimediaCategory().stateIn(scope, SharingStarted.Eagerly, null)
        val wikiCustom: StateFlow<String> = m.wikimediaCustomSearch().stateIn(scope, SharingStarted.Eagerly, "")
        val redditPreset: StateFlow<RedditPreset?> = m.redditPreset().stateIn(scope, SharingStarted.Eagerly, null)
        val redditCustom: StateFlow<String> = m.redditCustomSubreddits().stateIn(scope, SharingStarted.Eagerly, "")
        val folder: StateFlow<String> = m.paramsFlow(WallpaperProviders.FOLDER).stateIn(scope, SharingStarted.Eagerly, "")
        val media: StateFlow<String> = m.paramsFlow(WallpaperProviders.MEDIA_FILE).stateIn(scope, SharingStarted.Eagerly, "")
    }

    @Volatile private var state: State? = null

    /** Registers the pages and points the *Wallpaper* page's hooks at the wallpaper subsystem. */
    fun attach(context: Context) {
        val c = context.container
        val m = c.wallpaper
        state = State(m, c.scope)
        SettingsPages.register(PageIds.WALLPAPER_CONFIGURE) { env, _ -> configure(env) }
        SettingsPages.register(PageIds.ABOUT_WALLPAPER) { env, _ -> about(env) }
        WallpaperPage.sources = { m.providers.value.map { WallpaperPage.Source(it.id, it.name, it.isPlugin) } }
        WallpaperPage.count = {
            val id = c.settings.current[Keys.Wallpaper.provider]
            m.controller.status.value[id]?.count ?: m.controller.items.value.size
        }
        WallpaperPage.hasConfigure = { id ->
            id in setOf(WallpaperProviders.FOLDER, WallpaperProviders.MEDIA_FILE, WallpaperProviders.THEME, WallpaperProviders.REDDIT, WallpaperProviders.WIKIMEDIA) ||
                m.providers.value.firstOrNull { it.id == id }?.settingsActivity != null
        }
        WallpaperPage.selectSource = { env, id ->
            val info = m.providers.value.firstOrNull { it.id == id }
            if (info != null) {
                when (m.selectProvider(info)) {
                    ProviderSelection.NeedsRedditLogin -> env.actions.startActivity(m.redditLoginIntent(env.context, selectOnSuccess = true))
                    else -> Unit
                }
            }
        }
    }

    private fun configure(env: PageEnv): Page {
        val c = env.container
        val m = c.wallpaper
        val st = state ?: return Page(PageIds.WALLPAPER_CONFIGURE, text(R.string.settings_wallpaper), text(R.string.settings_configure), rows = { emptyList() })
        val id = c.settings.current[Keys.Wallpaper.provider]
        val provider = m.providers.value.firstOrNull { it.id == id }
        val showWallpapers = Row.Action(
            "show_wallpapers", text(R.string.settings_show_wallpapers), { env.actions.openWallpaperGallery() },
            description = { _ -> raw((m.controller.status.value[id]?.count ?: m.controller.items.value.size).toString()) },
            icon = "image", chevron = true,
        )
        val statusRow = Row.Info(
            "wp_status", text(R.string.wp_status), { _ -> m.controller.status.value[id]?.error?.let { raw(it) } }, icon = "info",
            visible = { _ -> m.controller.status.value[id]?.error != null },
        )
        val rows: List<Row> = when (id) {
            WallpaperProviders.WIKIMEDIA -> listOf(
                Row.Dropdown(
                    "wp_category", text(R.string.wp_category),
                    options = { _ -> WikimediaCategory.entries.map { Option(text(it.labelRes)) } },
                    selected = { _ -> WikimediaCategory.entries.indexOf(st.wikiCategory.value ?: WikimediaCategory.LANDSCAPES) },
                    onSelect = { i -> m.setWikimediaCategory(WikimediaCategory.entries[i]) },
                    icon = "category",
                ),
                Row.TextEntry(
                    null, text(R.string.wp_custom_search), id = "wp_custom_search", icon = "search",
                    enabled = { _ -> st.wikiCategory.value == WikimediaCategory.CUSTOM },
                    value = { _ -> st.wikiCustom.value },
                    onCommit = { v -> m.setWikimediaCustomSearch(v) },
                ),
                statusRow,
                showWallpapers,
            )
            WallpaperProviders.REDDIT -> listOf(
                Row.Action(
                    "wp_account", text(R.string.wp_account),
                    {
                        if (m.redditUser.value == null) env.actions.startActivity(m.redditLoginIntent(env.context))
                    },
                    description = { _ ->
                        when (val u = m.redditUser.value) {
                            null -> text(R.string.wallpaper_reddit_log_in)
                            "" -> text(R.string.wallpaper_reddit_account_unknown)
                            else -> text(R.string.wallpaper_reddit_account, u)
                        }
                    },
                    icon = "account_circle",
                ),
                Row.Expander(
                    "wp_log_out", text(R.string.wp_log_out),
                    listOf(
                        Row.Action("wp_log_out_cancel", text(R.string.settings_cancel), {}),
                        Row.Action("wp_log_out_ok", text(R.string.settings_ok), { c.scope.launch { m.redditLogout() } }),
                    ),
                    icon = "logout",
                    enabled = { _ -> m.redditUser.value != null },
                ),
                Row.Dropdown(
                    "wp_subreddit", text(R.string.wp_subreddit),
                    options = { _ -> RedditPresets.entries.map { Option(raw(it.label)) } + Option(text(R.string.wallpaper_reddit_custom)) },
                    selected = { _ -> st.redditPreset.value?.let { RedditPresets.entries.indexOf(it) } ?: RedditPresets.entries.size },
                    onSelect = { i -> m.setRedditPreset(RedditPresets.entries.getOrNull(i)) },
                    icon = "forum",
                ),
                Row.TextEntry(
                    null, text(R.string.wp_custom_subreddit), text(R.string.wp_custom_subreddit_hint), id = "wp_custom_subreddit", icon = "edit",
                    enabled = { _ -> st.redditPreset.value == null },
                    value = { _ -> st.redditCustom.value },
                    onCommit = { v -> m.setRedditCustomSubreddits(v) },
                ),
                statusRow,
                showWallpapers,
            )
            WallpaperProviders.FOLDER -> listOf(
                Row.Action(
                    "wp_folder", text(R.string.wp_folder_choose),
                    { env.actions.pickWallpaperFolder { uri -> m.chooseFolder(uri) } },
                    description = { _ -> st.folder.value.takeIf { it.isNotEmpty() }?.let { raw(android.net.Uri.decode(it).removePrefix("file://")) } ?: text(R.string.wp_not_set) },
                    icon = "folder",
                ),
                // The system folder picker, only where the TV has one: QLauncher's own list needs none.
                Row.Action(
                    "wp_folder_other", text(R.string.wp_choose_other),
                    { env.actions.pickFolder { uri -> m.chooseFolder(uri) } },
                    description = { _ -> text(R.string.wp_choose_other_desc) },
                    icon = "folder_open",
                    visible = { _ -> DocumentPickers.available(env.context, android.content.Intent.ACTION_OPEN_DOCUMENT_TREE) },
                ),
                statusRow,
                showWallpapers,
            )
            WallpaperProviders.MEDIA_FILE -> listOf(
                Row.Action(
                    "wp_media", text(R.string.wp_media_choose),
                    { env.actions.pickWallpaperFile { uri, mime -> m.chooseMediaFile(uri, mime) } },
                    description = { _ -> if (st.media.value.isEmpty()) text(R.string.wp_not_set) else null },
                    icon = "insert_drive_file",
                ),
                Row.Action(
                    "wp_media_other", text(R.string.wp_choose_other),
                    { env.actions.pickDocument(arrayOf("image/*", "video/*", "application/json")) { uri -> m.chooseMediaFile(uri, null) } },
                    description = { _ -> text(R.string.wp_choose_other_desc) },
                    icon = "folder_open",
                    visible = { _ -> DocumentPickers.available(env.context, type = "image/*") },
                ),
                Row.Action(
                    "wp_media_color", text(R.string.wp_media_color),
                    { env.actions.pickColor(0xFF303030.toInt()) { argb -> m.chooseMediaColor(argb) } },
                    icon = "palette",
                ),
                statusRow,
            )
            WallpaperProviders.THEME -> listOf(
                Row.Info("wp_theme", text(R.string.wp_theme_desc), { _ -> raw(m.themeWallpaperFolder.listFiles()?.size?.toString() ?: "0") }, icon = "format_paint"),
                statusRow,
                showWallpapers,
            )
            else -> buildList {
                val settingsActivity = provider?.settingsActivity
                if (settingsActivity != null) {
                    add(Row.Action("wp_plugin", text(R.string.wp_plugin_settings), { env.actions.startActivity(Intent().setComponent(settingsActivity)) }, icon = "settings", chevron = true))
                }
                add(statusRow)
                add(showWallpapers)
            }
        }
        return Page(
            PageIds.WALLPAPER_CONFIGURE, text(R.string.settings_wallpaper), raw(provider?.name ?: env.context.getString(R.string.settings_configure)),
            icon = "build_circle",
            rows = { rows },
        )
    }

    private fun about(env: PageEnv): Page {
        val c = env.container
        val m = c.wallpaper
        return Page(
            PageIds.ABOUT_WALLPAPER, text(R.string.settings_breadcrumb_about), text(R.string.wp_about_title), icon = "wallpaper",
            leftPane = LeftPane.Custom {
                Box(Modifier.fillMaxSize()) {
                    val shown by m.controller.wallpaper.collectAsState()
                    val w2 = shown
                    if (w2 != null && !w2.isInternal) {
                        AsyncImage(w2.uri, null, Modifier.offset(220.dp, 140.dp).size(300.dp, 169.dp))
                    }
                    val url = w2?.takeIf { !it.isInternal }?.let { it.actionUri ?: it.source ?: it.uri }
                    if (url != null) SettingsSlots.Qr.current(url, Modifier.offset(56.dp, 287.dp).size(140.dp))
                }
            },
            rows = { _ ->
                val cur = m.controller.wallpaper.value
                listOf(
                    Row.Action("wp_next", text(R.string.wp_next), { m.controller.next() }, icon = "skip_next"),
                    Row.Info("wp_source", text(R.string.wp_source), { _ -> raw(cur?.source ?: env.context.getString(R.string.wp_unknown)) }, icon = "link"),
                    Row.Info("wp_title", text(R.string.wp_title), { _ -> raw(cur?.title ?: env.context.getString(R.string.wp_unknown)) }, icon = "title"),
                    Row.Info(
                        "wp_author", text(R.string.wp_author),
                        { _ -> raw(listOfNotNull(cur?.author, cur?.licence).joinToString(" · ").ifEmpty { env.context.getString(R.string.wp_unknown) }) },
                        icon = "person",
                    ),
                    Row.Action(
                        "wp_set_user", text(R.string.wp_set_user),
                        {
                            c.scope.launch {
                                val ok = m.setAsUserWallpaper()
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    env.actions.toast(env.context.getString(if (ok) R.string.wallpaper_set_done else R.string.wallpaper_set_failed))
                                }
                            }
                        },
                        icon = "save",
                        enabled = { _ -> cur != null && !cur.isInternal },
                    ),
                    Row.Action(
                        "wp_view", text(R.string.wp_view),
                        { cur?.let { w2 -> m.viewIntent(w2) }?.let { env.actions.startActivity(it) } },
                        icon = "open_in_new",
                        enabled = { _ -> cur != null && m.viewIntent(cur) != null },
                    ),
                )
            },
        )
    }
}
