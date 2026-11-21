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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.Keys

/** The one [HomeController] per process, shared by the home screen and the settings preview. */
object HomeUi {
    @Volatile private var instance: HomeController? = null

    /** Supplies the data sources; replaced when the subsystems are attached. */
    @Volatile var sourcesFactory: (Context) -> HomeDataSources = { ctx -> DefaultHomeSources(ctx) }

    fun controller(context: Context): HomeController = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val c = app.container
            HomeController(app, c, sourcesFactory(app), c.scope).also { instance = it }
        }
    }
}

/** Sources available in every build: notifications from the system subsystem. */
private class DefaultHomeSources(context: Context) : HomeDataSources by EmptyHomeSources {
    private val system = context.container.system
    override val notifications = system.notifications
}

/**
 * The non-focusable copy of the home screen drawn in the settings pages' tilted preview. It shows
 * the same rows with the same settings, so a change on the right appears here in the same frame.
 */
@Composable
fun HomePreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { HomeUi.controller(context) }
    val container = context.container
    val rows by controller.rows.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val colors by container.theme.colors.collectAsStateWithLifecycle()
    val focus = remember { HomeFocus() }
    val style = remember(settings, colors) { CardStyle.from(settings, colors.palette, colors.accent) }
    focus.row = controller.defaultRowIndex(rows)
    val clock = remember(settings) {
        org.cygnusx1.qlauncher.HomeSlots.formatClock(context, settings[Keys.Clock.formatLine1], settings[Keys.Clock.formatLine2], java.util.Date())
    }
    val status = StatusBarInfo(
        buttons = buildList {
            if (settings[Keys.StatusBar.showConnectivity]) add(StatusButton.Connectivity)
            if (settings[Keys.StatusBar.showSettings]) add(StatusButton.Settings)
        },
        showClock = settings[Keys.Clock.show],
        clock = clock,
    )
    HomeScreen(rows, settings, style, focus, status, interactive = false, onEvent = {}, modifier = modifier, images = controller.sources.images)
}
