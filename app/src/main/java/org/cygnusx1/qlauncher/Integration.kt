/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher

import android.content.Intent
import androidx.compose.ui.text.AnnotatedString
import org.cygnusx1.qlauncher.settings.backup.WallpaperParamsBridge
import org.cygnusx1.qlauncher.ui.home.CardKind
import org.cygnusx1.qlauncher.ui.home.HomeUi
import org.cygnusx1.qlauncher.ui.home.ModuleHomeSources
import org.cygnusx1.qlauncher.ui.settings.pages.AboutPages
import org.cygnusx1.qlauncher.ui.settings.pages.AppsPages
import org.cygnusx1.qlauncher.ui.settings.pages.ClockHelp
import org.cygnusx1.qlauncher.ui.settings.pages.DisplayProfilePages
import org.cygnusx1.qlauncher.ui.settings.pages.FontRows
import org.cygnusx1.qlauncher.ui.settings.pages.WallpaperPages
import org.cygnusx1.qlauncher.util.ClockFormat
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.ui.WallpaperHost

/**
 * Connects the subsystems to the screens: registers the settings pages that need subsystem data
 * and fills the [HomeSlots] and settings hooks. Called once from [QLauncherApp.onCreate].
 */
object Integration {
    fun install(app: QLauncherApp) {
        val c = app.container

        // Subsystems that feed each other.
        val wp = c.wallpaper
        wp.programSource = c.apps.channels
        wp.notificationSource = c.system.notifications
        c.backup.registerWallpaperParams(object : WallpaperParamsBridge {
            override suspend fun exportParams(): Map<String, String> = wp.exportParams()
            override suspend fun importParams(params: Map<String, String>) {
                wp.importParams(params, clearOthers = true).join()
            }
        })

        // Settings pages fed by subsystems.
        AboutPages.register()
        AppsPages.register()
        AppsPages.attach(app)
        WallpaperPages.attach(app)
        DisplayProfilePages.register()
        FontRows.googleFonts = { c.backup.fonts.googleFamilies.value }
        ClockHelp.formatter = { pattern, date -> ClockFormat.forSystem(app).format(pattern, date) }

        // Home screen.
        HomeUi.sourcesFactory = { ctx -> ModuleHomeSources(ctx) }
        HomeSlots.wallpaper = { m -> WallpaperHost(m) }
        HomeSlots.launchApp = { activity, key -> c.apps.launch(activity, key) }
        HomeSlots.launchOther = { activity, card -> c.apps.launch(activity, card.key) }
        HomeSlots.runDeviceAction = { activity, action ->
            c.apps.actions.intent(action.id)?.let { intent ->
                runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        HomeSlots.loadAppShortcuts = { ctx, pkg ->
            c.apps.shortcuts.appShortcuts(pkg).map { s -> s.shortLabel to { c.apps.shortcuts.start(ctx, pkg, s.id); Unit } }
        }
        HomeSlots.onCardFocused = { _, card ->
            val event = if (card.kind == CardKind.Program) {
                val p = card.program
                WallpaperEvent.ProgramCardFocused(card.title, null, p?.posterArtUri ?: p?.thumbnailUri, p?.posterArtAspectRatio ?: -1)
            } else {
                WallpaperEvent.CardFocused(card.title, card.packageName, 0, 0)
            }
            wp.controller.onEvent(event)
        }
        HomeSlots.onIdleChanged = { idle -> wp.controller.onEvent(WallpaperEvent.LauncherIdleModeChanged(idle)) }
        HomeSlots.nextWallpaper = { wp.controller.next() }
        HomeSlots.hasWallpaperAction = { wp.controller.wallpaper.value?.actionUri != null }
        HomeSlots.openWallpaperAction = { activity ->
            wp.controller.wallpaper.value?.let { wp.actionIntent(it) }?.let { wp.launch(activity, it) }
        }
        HomeSlots.formatClock = { ctx, l1, l2, now ->
            val f = ClockFormat.forSystem(ctx)
            val first = f.line1(l1, now.time)
            val second = f.line2(l2, now.time)
            if (second == null) first else first + AnnotatedString("\n") + second
        }

        // Creating the backup module starts the automatic backups.
        c.backup
    }
}
