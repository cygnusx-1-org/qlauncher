/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.displayprofiles.CalibrationPattern
import org.cygnusx1.qlauncher.displayprofiles.CalibrationPatterns
import org.cygnusx1.qlauncher.displayprofiles.ProfileDraft
import org.cygnusx1.qlauncher.ui.settings.GuidedDimens
import org.cygnusx1.qlauncher.ui.settings.model.LeftPane
import org.cygnusx1.qlauncher.ui.settings.model.Option
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text

/** Ticks when a page's own state (outside the settings) changes, so its rows are rebuilt. */
object PageRevision {
    val tick = MutableStateFlow(0L)

    fun bump() {
        tick.value = tick.value + 1
    }
}

/** *Display Profiles*: landing page, list and editor. */
object DisplayProfilePages {
    const val NEW = "new"

    fun register() {
        SettingsPages.register(PageIds.DISPLAY_PROFILES) { env, _ -> landing(env) }
        SettingsPages.register(PageIds.DISPLAY_PROFILES_LIST) { env, _ -> list(env) }
        SettingsPages.register(PageIds.DISPLAY_PROFILE) { env, id -> editor(env, id.removePrefix(PageIds.DISPLAY_PROFILE)) }
    }

    private fun landing(env: PageEnv) = Page(
        PageIds.DISPLAY_PROFILES, text(R.string.settings_breadcrumb_display), text(R.string.settings_display_profiles), text(R.string.dp_desc),
        icon = "settings_brightness",
        rows = { listOf(Row.Nav("dp_list", text(R.string.dp_list), PageIds.DISPLAY_PROFILES_LIST, "list")) },
    )

    private fun list(env: PageEnv): Page {
        val store = env.container.backup.profiles
        return Page(
            PageIds.DISPLAY_PROFILES_LIST, text(R.string.settings_breadcrumb_display), text(R.string.dp_list), text(R.string.dp_list_desc),
            icon = "settings_brightness",
            rows = { _ ->
                listOf(Row.Nav("dp_add", text(R.string.dp_add), PageIds.DISPLAY_PROFILE + NEW, "add")) +
                    store.profiles.value.map { p -> Row.Nav("dp_" + p.id, raw(p.name), PageIds.DISPLAY_PROFILE + p.id, "tune") }
            },
        )
    }

    private fun editor(env: PageEnv, profileId: String): Page {
        val module = env.container.backup
        val store = module.profiles
        val existing = if (profileId == NEW) null else store.get(profileId)
        val draft = if (existing != null) ProfileDraft(existing, "") else ProfileDraft(null, store.nextDefaultName(env.context.getString(R.string.backup_profile_default_name)))
        var deleted = false
        fun edit(block: () -> Unit) {
            block()
            PageRevision.bump()
        }
        return Page(
            PageIds.DISPLAY_PROFILE + profileId, text(R.string.settings_breadcrumb_display), raw(draft.profile.value.name), icon = "tune",
            leftPane = LeftPane.Custom(wide = true) {
                val p by draft.profile.collectAsState()
                Box(Modifier.width(GuidedDimens.ListStartWide - 16.dp).fillMaxHeight()) {
                    CalibrationPattern(p.previewPattern, Modifier.width(GuidedDimens.ListStartWide - 16.dp).fillMaxHeight())
                }
                // The profile is stored when the editor is left with a change (a fix over
                // Projectivy, which creates it the moment Add new... is opened).
                DisposableEffect(Unit) {
                    onDispose { if (!deleted) env.container.scope.launch { draft.commit(store) } }
                }
            },
            rows = { _ ->
                val p = draft.profile.value
                buildList {
                    add(
                        Row.TextEntry(
                            null, text(R.string.dp_name), id = "dp_name", icon = "badge",
                            value = { _ -> draft.profile.value.name },
                            onCommit = { v -> edit { draft.rename(v) } },
                        ),
                    )
                    add(
                        Row.Dropdown(
                            "dp_preview", text(R.string.dp_preview),
                            options = { _ -> CalibrationPatterns.all.map { Option(text(it.nameRes), enabled = it.isAvailable) } },
                            selected = { _ -> draft.profile.value.previewPattern },
                            onSelect = { i -> edit { draft.setPreviewPattern(i) } },
                            icon = "grid_on",
                        ),
                    )
                    add(
                        Row.Action(
                            "dp_fullscreen", text(R.string.dp_fullscreen), { env.actions.showPattern(p.previewPattern) },
                            description = { _ -> text(R.string.dp_fullscreen_desc) }, icon = "fullscreen",
                        ),
                    )
                    if (module.pictureManager == null) {
                        add(Row.Info("dp_no_pm", text(R.string.backup_no_picture_manager), { _ -> text(R.string.backup_no_picture_manager_detail) }, icon = "warning"))
                    }
                    if (existing != null) {
                        add(
                            Row.Expander(
                                "dp_delete", text(R.string.dp_delete),
                                listOf(
                                    Row.Action("dp_delete_cancel", text(R.string.settings_cancel), {}),
                                    Row.Action("dp_delete_ok", text(R.string.settings_ok), {
                                        deleted = true
                                        env.container.scope.launch { store.delete(existing.id) }
                                        env.navigate(SettingsPages.NAV_BACK)
                                    }),
                                ),
                                icon = "delete",
                            ),
                        )
                    }
                }
            },
        )
    }
}
