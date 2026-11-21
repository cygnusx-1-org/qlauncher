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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.cygnusx1.qlauncher.apps.actions.BuiltInActions
import org.cygnusx1.qlauncher.apps.image.AppImage
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.shared.ProgramSource

/** The home screen's data, from the apps, channels and system subsystems. */
class ModuleHomeSources(context: Context) : HomeDataSources {
    private val c = context.container
    private val m = c.apps

    override val apps: StateFlow<List<HomeApp>> = m.apps.apps
        .map { list -> list.map { HomeApp(it.key, it.packageName, it.label, it.isTv, it.isMobile, it.installTime) } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    override val shortcuts: StateFlow<List<HomeShortcut>> = m.shortcuts.pinned
        .map { list -> list.map { HomeShortcut(it.key, it.packageName, it.label, AppImage(it.key, AppImage.Kind.Icon)) } }
        .stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    override val inputs: StateFlow<List<HomeInput>> = combine(m.inputs.inputs, m.inputs.liveSources) { pass, tuners ->
        pass.map { HomeInput(it.key, it.label, "settings_input_hdmi") } +
            tuners.map { HomeInput(it.key, it.label, "live_tv", isLiveSource = true) }
    }.stateIn(c.scope, SharingStarted.Eagerly, emptyList())

    override val availability: StateFlow<ActionAvailability> = combine(
        c.settings.settings,
        m.channels.available,
        m.apps.apps.onStart { emit(emptyList()) },
    ) { _, _, _ ->
        val a = m.actions
        ActionAvailability(
            channels = a.isAvailable(BuiltInActions.EDIT_CHANNELS),
            amlogic = a.isAvailable(BuiltInActions.AMLOGIC_SETTINGS),
            smartHome = a.isAvailable(BuiltInActions.GOOGLE_SMART_HOME),
            switchProfile = a.isAvailable(BuiltInActions.SWITCH_PROFILE),
            aboutWallpaper = a.isAvailable(BuiltInActions.ABOUT_WALLPAPER),
        )
    }.stateIn(c.scope, SharingStarted.Eagerly, ActionAvailability())

    override val programs: ProgramSource = m.channels

    override val notifications: NotificationSource = c.system.notifications

    override val images = CardImages { key, banner, overrideUri -> AppImage(key, if (banner) AppImage.Kind.Banner else AppImage.Kind.Icon, overrideUri) }
}
