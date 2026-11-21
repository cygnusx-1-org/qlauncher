/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.cygnusx1.qlauncher.ui.home.BuiltInAction
import org.cygnusx1.qlauncher.ui.home.CardItem
import org.cygnusx1.qlauncher.ui.home.HomeRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The points where the home activity calls into subsystems that are wired in [Integration]. Each
 * has a working default so the launcher runs without them.
 */
object HomeSlots {
    @Volatile var wallpaper: @Composable (Modifier) -> Unit = { m -> Box(m.background(Color.Black)) }

    @Volatile var onCardFocused: (HomeRow, CardItem) -> Unit = { _, _ -> }

    @Volatile var nextWallpaper: () -> Unit = {}

    @Volatile var onIdleChanged: (Boolean) -> Unit = {}

    /** Whether the current wallpaper has a link (the status bar's wallpaper button). */
    @Volatile var hasWallpaperAction: () -> Boolean = { false }

    @Volatile var openWallpaperAction: (Activity) -> Unit = {}

    @Volatile var onHomeVisible: (Boolean) -> Unit = {}

    /** Launches an app item; false when not handled here. */
    @Volatile var launchApp: (Activity, String) -> Boolean = { _, _ -> false }

    /** Launches an input or a shortcut card. */
    @Volatile var launchOther: (Activity, CardItem) -> Boolean = { _, _ -> false }

    @Volatile var runDeviceAction: (Activity, BuiltInAction) -> Unit = { _, _ -> }

    /** The app's own launcher shortcuts: (label, launch). */
    @Volatile var loadAppShortcuts: suspend (Context, String) -> List<Pair<String, () -> Unit>> = { _, _ -> emptyList() }

    /** The clock text from the two format lines (empty line 1 = the system's short time). */
    @Volatile var formatClock: (Context, String, String, Date) -> AnnotatedString = { ctx, l1, l2, now ->
        val first = if (l1.isEmpty()) {
            SimpleDateFormat(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        } else {
            runCatching { SimpleDateFormat(l1, Locale.getDefault()).format(now) }.getOrDefault(l1)
        }
        val second = if (l2.isEmpty()) null else runCatching { SimpleDateFormat(l2, Locale.getDefault()).format(now) }.getOrDefault(l2)
        AnnotatedString(if (second != null) "$first\n$second" else first)
    }
}
