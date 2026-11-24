/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.iconpack.IconSetMatch
import org.cygnusx1.qlauncher.apps.iconpack.IconSetNames
import org.cygnusx1.qlauncher.apps.iconpack.IconSetRelease
import org.cygnusx1.qlauncher.apps.iconpack.IconSetSource
import org.cygnusx1.qlauncher.apps.iconpack.IconSetStatus
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.ui.components.PanelRow
import org.cygnusx1.qlauncher.ui.components.PanelState
import org.cygnusx1.qlauncher.ui.components.PanelTrailing
import org.cygnusx1.qlauncher.ui.components.SidePanel
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.UiText
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text

/**
 * *Cards › Icon set*: a GitHub repository, one of its newest releases (downloading it), the
 * style *Change icon* offers, and matching icons to apps by name.
 *
 * Every row is always there and only enables or disables, so nothing moves as a download starts,
 * fails or finishes.
 */
object IconSetPages {
    fun register() {
        SettingsPages.register(PageIds.ICON_SET) { env, _ -> page(env) }
        SettingsPages.register(PageIds.ICON_SET_ADD) { env, _ -> addPage(env) }
    }

    /** The sources the Repository dropdown offers, the chosen one included. */
    private fun sources(settings: org.cygnusx1.qlauncher.settings.Settings): List<IconSetSource> =
        IconSetNames.sources(settings[Keys.Internal.iconSetRepos], settings[Keys.Internal.iconSetRepo])

    private fun page(env: PageEnv): Page {
        val sets = env.container.apps.iconSets
        // Lists the releases when the page opens, unless this repository's are listed already.
        val input = env.repo.current[Keys.Internal.iconSetRepo]
        val listed = sets.releases.value
        if (listed == null || listed.first != IconSetNames.parseRepo(input) || listed.second.isEmpty()) sets.loadReleases(input)
        return Page(
            PageIds.ICON_SET, text(R.string.settings_breadcrumb), text(R.string.iconset_title), text(R.string.iconset_desc), icon = "collections",
            rows = { ctx ->
                val m = sets.manifest.value
                val st = sets.status.value
                val working = st is IconSetStatus.LoadingReleases || st is IconSetStatus.Downloading
                val repo = IconSetNames.parseRepo(ctx.settings[Keys.Internal.iconSetRepo])
                val sources = sources(ctx.settings)
                val source = sources.firstOrNull { it.repo.equals(repo, ignoreCase = true) }
                // GitHub names ignore case, and a source may be added again in another case.
                val releases = sets.releases.value?.takeIf { it.first.equals(repo, ignoreCase = true) }?.second.orEmpty()
                val installedHere = m != null && m.repo.equals(repo, ignoreCase = true) && m.stylesPath == source?.stylesPath
                listOf(
                    // The sources, then *Add…*, which opens a page asking for a repository and its path.
                    Row.Dropdown(
                        "iconset_repo", text(R.string.iconset_repo),
                        options = { _ -> sources.map { Option(raw(it.repo)) } + Option(text(R.string.iconset_repo_add)) },
                        selected = { _ -> sources.indexOf(source) },
                        onSelect = { i ->
                            val picked = sources.getOrNull(i)
                            if (picked == null) {
                                env.navigate(PageIds.ICON_SET_ADD)
                            } else {
                                env.repo.update(Keys.Internal.iconSetRepo, picked.repo)
                                sets.loadReleases(picked.repo)
                            }
                        },
                        icon = "link",
                        enabled = { _ -> !working },
                    ),
                    Row.Dropdown(
                        "iconset_release", text(R.string.iconset_release),
                        options = { _ -> releases.map { Option(raw(label(it))) } },
                        selected = { _ -> if (installedHere) releases.indexOfFirst { it.tag == m?.tag } else -1 },
                        onSelect = { i -> if (source != null) releases.getOrNull(i)?.let { sets.download(source, it) } },
                        description = { _ -> if (installedHere) raw(m?.tag.orEmpty()) else text(R.string.iconset_release_none) },
                        icon = "new_releases",
                        enabled = { _ -> releases.isNotEmpty() && !working },
                    ),
                    Row.Dropdown(
                        "iconset_style", text(R.string.iconset_style),
                        options = { _ -> m?.styles.orEmpty().map { Option(raw(IconSetNames.styleLabel(it))) } },
                        selected = { _ -> m?.styles?.indexOf(m.style) ?: -1 },
                        onSelect = { i -> m?.styles?.getOrNull(i)?.let(sets::selectStyle) },
                        description = { _ -> m?.style?.let { raw(IconSetNames.styleLabel(it)) } ?: text(R.string.iconset_style_choose) },
                        icon = "style",
                        enabled = { _ -> m != null && !working },
                    ),
                    Row.Action(
                        "iconset_match", text(R.string.iconset_match), { env.actions.matchIconSet() },
                        description = { _ -> text(R.string.iconset_match_desc) },
                        icon = "auto_fix_high",
                        enabled = { _ -> m?.style != null && !working },
                        chevron = true,
                    ),
                    // Two lines in every state: the longest status (the set's repository and
                    // release) wraps, and a shorter one must not move the rows under it.
                    Row.Info("iconset_status", text(R.string.iconset_status), { _ -> status(st, m?.let { "${it.repo} · ${it.tag}" }) }, icon = "info", valueLines = 2),
                    Row.Action(
                        "iconset_cancel", text(R.string.iconset_cancel), { sets.cancel() },
                        icon = "cancel",
                        enabled = { _ -> working },
                    ),
                    // Works with or without a downloaded set: the icons on cards are copies.
                    Row.Expander(
                        "iconset_reset", text(R.string.iconset_reset),
                        listOf(
                            Row.Action("iconset_reset_cancel", text(R.string.settings_cancel), {}),
                            Row.Action("iconset_reset_ok", text(R.string.settings_ok), {
                                env.container.scope.launch {
                                    val n = sets.resetIcons()
                                    withContext(Dispatchers.Main) {
                                        env.actions.toast(env.context.resources.getQuantityString(R.plurals.iconset_reset_done, n, n))
                                    }
                                }
                            }),
                        ),
                        icon = "restart_alt",
                    ),
                    Row.Expander(
                        "iconset_remove", text(R.string.iconset_remove),
                        listOf(
                            Row.Action("iconset_remove_cancel", text(R.string.settings_cancel), {}),
                            Row.Action("iconset_remove_ok", text(R.string.settings_ok), { sets.remove() }),
                        ),
                        icon = "delete",
                        enabled = { _ -> m != null && !working },
                    ),
                )
            },
        )
    }

    /** What *Add repository* has been given so far; kept until it is added. */
    private val draftRepo = kotlinx.coroutines.flow.MutableStateFlow("")
    private val draftPath = kotlinx.coroutines.flow.MutableStateFlow(IconSetNames.DEFAULT_SOURCE.stylesPath)

    /**
     * *Repository › Add…*: a GitHub repository (`owner/repo`, or its URL) and the folder in it
     * that holds one folder per style. *Add* checks both, keeps the source, chooses it and goes
     * back; adding a repository again replaces its path.
     */
    private fun addPage(env: PageEnv): Page = Page(
        PageIds.ICON_SET_ADD, text(R.string.iconset_title), text(R.string.iconset_add_title), text(R.string.iconset_add_desc), icon = "add_link",
        rows = { _ ->
            listOf(
                Row.TextEntry(
                    null, text(R.string.iconset_repo), text(R.string.iconset_repo_hint), id = "iconset_add_repo", icon = "link",
                    value = { _ -> draftRepo.value },
                    onCommit = { v ->
                        draftRepo.value = v
                        PageRevision.bump()
                    },
                ),
                Row.TextEntry(
                    null, text(R.string.iconset_add_path), text(R.string.iconset_add_path_hint), id = "iconset_add_path", icon = "folder",
                    value = { _ -> draftPath.value },
                    onCommit = { v ->
                        draftPath.value = v
                        PageRevision.bump()
                    },
                ),
                Row.Action(
                    "iconset_add", text(R.string.iconset_add), {
                        val repo = IconSetNames.parseRepo(draftRepo.value)
                        val path = IconSetNames.parsePath(draftPath.value)
                        when {
                            repo == null -> env.actions.toast(env.context.getString(R.string.iconset_fail_bad_repo))
                            path == null -> env.actions.toast(env.context.getString(R.string.iconset_fail_bad_path))
                            else -> {
                                val added = IconSetNames.decodeSources(env.repo.current[Keys.Internal.iconSetRepos])
                                    .filterNot { it.repo.equals(repo, ignoreCase = true) } + IconSetSource(repo, path)
                                env.repo.update(Keys.Internal.iconSetRepos, IconSetNames.encodeSources(added))
                                env.repo.update(Keys.Internal.iconSetRepo, repo)
                                env.container.apps.iconSets.loadReleases(repo)
                                draftRepo.value = ""
                                draftPath.value = IconSetNames.DEFAULT_SOURCE.stylesPath
                                env.navigate(SettingsPages.NAV_BACK)
                            }
                        }
                    },
                    icon = "add",
                ),
            )
        },
    )

    /** `v1.47 · 2026-11-22` */
    private fun label(r: IconSetRelease): String = listOfNotNull(r.tag, r.publishedAt?.take(10)).joinToString(" · ")

    private fun status(st: IconSetStatus, installed: String?): UiText = when (st) {
        IconSetStatus.Idle -> installed?.let(::raw) ?: text(R.string.iconset_release_none)
        IconSetStatus.LoadingReleases -> text(R.string.iconset_loading)
        is IconSetStatus.Downloading -> text(R.string.iconset_downloading, st.tag, (st.bytes shr 20).toInt())
        is IconSetStatus.Failed -> when (st.reason) {
            IconSetStatus.Reason.BadRepo -> text(R.string.iconset_fail_bad_repo)
            IconSetStatus.Reason.NotFound -> text(R.string.iconset_fail_not_found)
            IconSetStatus.Reason.NoReleases -> text(R.string.iconset_fail_no_releases)
            IconSetStatus.Reason.Network -> text(R.string.iconset_fail_network)
            IconSetStatus.Reason.Http -> text(R.string.iconset_fail_http, st.detail ?: "?")
            IconSetStatus.Reason.NoStyles -> text(R.string.iconset_fail_no_styles, st.detail?.ifEmpty { "/" } ?: "?")
            IconSetStatus.Reason.NoSpace -> text(R.string.iconset_fail_no_space)
            IconSetStatus.Reason.Storage -> text(R.string.iconset_fail_storage)
        }
    }
}

/**
 * *Match icons to apps*: each app whose name matches an icon, with that icon and a switch. Apps
 * that have a custom icon already start switched off. *Apply* puts the switched-on icons on.
 */
@Composable
fun IconMatchPanel(matches: List<IconSetMatch>, onApply: (List<IconSetMatch>) -> Unit, onBack: () -> Unit) {
    val on = remember(matches) { mutableStateMapOf<String, Boolean>().apply { matches.forEach { put(it.itemKey, !it.hasCustomIcon) } } }
    val state = remember(matches) { PanelState() }
    val chosen = matches.filter { on[it.itemKey] == true }
    val rows = buildList<PanelRow> {
        add(
            PanelRow.Item(
                "apply", stringResource(R.string.iconset_apply, chosen.size), icon = "done_all",
                enabled = chosen.isNotEmpty(),
                onClick = { onApply(chosen) },
            ),
        )
        matches.forEach { m ->
            add(
                PanelRow.Item(
                    m.itemKey, m.title,
                    subtitle = m.icon.name,
                    leading = { IconThumb(m.icon.file) },
                    trailing = PanelTrailing.Switch(on[m.itemKey] == true),
                    onClick = { on[m.itemKey] = on[m.itemKey] != true },
                ),
            )
        }
    }
    SidePanel(rows, state, onBack, title = stringResource(R.string.iconset_match_title), titleIcon = "auto_fix_high", tag = "panel_icon_matches")
}

/** A 16:9 icon on the panel's dark tile, the size of the All apps banners. */
@Composable
private fun IconThumb(model: Any) {
    Box(Modifier.size(64.dp, 36.dp).background(Color(0xFF333333), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
        AsyncImage(model, null, Modifier.size(64.dp, 36.dp), contentScale = ContentScale.Fit)
    }
}
