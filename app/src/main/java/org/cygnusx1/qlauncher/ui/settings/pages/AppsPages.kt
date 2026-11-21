/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings.pages

import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.apps.AutostartTarget
import org.cygnusx1.qlauncher.install.Catalogue
import org.cygnusx1.qlauncher.install.InstallMessages
import org.cygnusx1.qlauncher.install.InstallState
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.ui.settings.model.Page
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.raw
import org.cygnusx1.qlauncher.ui.settings.model.text

/** Pages fed by the apps subsystem: *Install 3rd party apps* and *About › This device*. */
object AppsPages {
    fun register() {
        SettingsPages.register(PageIds.INSTALL_APPS) { env, _ -> install(env) }
        SettingsPages.register(PageIds.ABOUT_DEVICE) { env, _ -> device(env) }
    }

    /** Points the rows whose options are apps, inputs and icon packs at the apps subsystem. */
    fun attach(context: android.content.Context) {
        val c = context.container
        IconPackRow.packs = { c.apps.iconPacks.packs.value.map { it.packageName to it.label } }
        AutostartRow.apps = {
            c.apps.autostartTargets()
                .filter { it.kind == AutostartTarget.Kind.App || it.kind == AutostartTarget.Kind.Input }
                .map { it.value to it.label }
        }
        AutostartRow.screensaverSupported = { c.system.actions.isScreensaverSupported() }
    }

    private fun install(env: PageEnv): Page {
        val m = env.container.apps
        val installer = m.installer
        return Page(
            PageIds.INSTALL_APPS, text(R.string.settings_breadcrumb_applications), text(R.string.install_title), text(R.string.install_desc), icon = "add_box",
            rows = {
                val state = installer.state.value
                val status = InstallMessages.describe(env.context.resources, state)
                buildList {
                    if (status != null && state !is InstallState.Idle) {
                        add(Row.Info("install_status", text(R.string.install_status), { raw(status) }, icon = "info"))
                    }
                    add(
                        Row.TextEntry(
                            Keys.Internal.installInput, text(R.string.install_custom), text(R.string.install_custom_desc), icon = "link",
                            onCommit = { value ->
                                env.repo.update(Keys.Internal.installInput, value)
                                if (value.isNotBlank()) installer.installFromInput(env.context, value)
                            },
                        ),
                    )
                    val installed = installer.installed.value
                    Catalogue.grouped(installer.catalogue.value).forEach { (category, entries) ->
                        add(
                            Row.Expander(
                                "install_" + category.id,
                                raw(env.context.getString(category.label) + " (" + entries.size + ")"),
                                entries.flatMap { e ->
                                    val tick = if (e.packageName != null && e.packageName in installed) "✓ " else ""
                                    val methods = e.methods
                                    if (methods.size <= 1) {
                                        listOf(Row.Action("app_" + e.name, raw(tick + e.name), { installer.install(env.context, e) }))
                                    } else {
                                        methods.map { method ->
                                            Row.Action(
                                                "app_" + e.name + "_" + method.name,
                                                raw(tick + env.context.getString(R.string.install_method_choice, e.name, env.context.getString(method.label))),
                                                { installer.install(env.context, e, method) },
                                            )
                                        }
                                    }
                                },
                                icon = when (category.id.lowercase()) {
                                    "wallpaper plugin" -> "wallpaper"
                                    "icon pack" -> "apps"
                                    "file manager" -> "folder"
                                    else -> "build"
                                },
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun device(env: PageEnv): Page {
        val loaded = kotlinx.coroutines.flow.MutableStateFlow<List<org.cygnusx1.qlauncher.device.DeviceInfoRow>>(emptyList())
        env.container.scope.launch {
            loaded.value = env.container.apps.deviceInfo.rows()
            PageRevision.bump()
        }
        return Page(
            PageIds.ABOUT_DEVICE, text(R.string.settings_breadcrumb_about), text(R.string.device_title), icon = "tv",
            rows = { loaded.value.map { r -> Row.Info(r.id, text(r.label), { raw(r.value) }) } },
        )
    }
}
