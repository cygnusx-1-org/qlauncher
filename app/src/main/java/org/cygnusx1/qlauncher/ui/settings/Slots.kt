/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.cygnusx1.qlauncher.ui.theme.QTheme

/**
 * Pieces of the settings pages that other subsystems draw. The activity provides real ones; the
 * defaults keep the pages usable on their own (and in previews).
 */
object SettingsSlots {
    /** The full-screen page background (the dynamic-colours gradient). */
    val Background = staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { m -> DefaultBackground(m) } }

    /** The tilted live preview of the home screen for appearance pages. */
    val LivePreview = staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { } }

    /** The wallpaper with the current picture adjustment (Picture adjustment page). */
    val WallpaperPreview = staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { } }

    /** A QR code for a text. */
    val Qr = staticCompositionLocalOf<@Composable (String, Modifier) -> Unit> { { _, _ -> } }
}

@Composable
private fun DefaultBackground(modifier: Modifier) {
    val accent = QTheme.colors.accent
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.28f), Color.Transparent),
                    radius = 1400f,
                ),
            ),
    )
}
