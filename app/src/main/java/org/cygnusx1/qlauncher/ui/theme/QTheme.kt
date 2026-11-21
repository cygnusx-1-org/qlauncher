/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val LocalQColors = staticCompositionLocalOf { QColors.DEFAULT }
val LocalQTypography = staticCompositionLocalOf { QTypography(DmSans) }

object QTheme {
    val colors: QColors @Composable get() = LocalQColors.current
    val type: QTypography @Composable get() = LocalQTypography.current
}

/**
 * The app theme. [colors] changes as wallpapers rotate; accent changes cross-fade over 1 s.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QLauncherTheme(
    colors: QColors = QColors.DEFAULT,
    fontFamily: FontFamily = DmSans,
    fontScale: Float = 1f,
    bold: Boolean = false,
    italic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent by animateColorAsState(colors.accent, tween(1000), label = "accent")
    val container by animateColorAsState(colors.accentContainer, tween(1000), label = "accentContainer")
    val animated = colors.copy(accent = accent, accentContainer = container)
    val typography = remember(fontFamily, fontScale, bold, italic) { QTypography(fontFamily, fontScale, bold, italic) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            primaryContainer = container,
            surface = QPalette.PanelBackground,
            background = androidx.compose.ui.graphics.Color.Black,
        ),
    ) {
        CompositionLocalProvider(
            LocalQColors provides animated,
            LocalQTypography provides typography,
            content = content,
        )
    }
}
