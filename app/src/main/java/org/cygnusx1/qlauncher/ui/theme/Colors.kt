/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.ui.graphics.Color

/** Fixed colours. The accent and its container are dynamic, see [QColors]. */
object QPalette {
    /** Projectivy's accent when nothing else applies. */
    val DefaultAccent = Color(0xFFD28045)

    /** The palette seed before any wallpaper has been read. */
    val InitialSeed = Color(0xFF303030)

    val PanelBackground = Color(0xED171515)
    val PanelFocusedRow = Color(0x1FFFFFFF)
    val DefaultCardBackground = Color(0xFF333333)
    val CardTitle = Color.White
    val CardDescription = Color(0xFFAAAAAA)
    val ExperimentalBadge = Color(0xFF2166A3)
    val ParentalOverlay = Color(0xCC000000)

    /** Guided-step right pane darkening. */
    val GuidanceScrim = Color(0x66000000)

    /** The focused guided row pill. */
    val GuidedFocusPill = Color(0x1FFFFFFF)

    val TextPrimary = Color.White
    val TextTitleUnfocused = Color(0xE6FFFFFF)
    val TextDescription = Color(0x80FFFFFF)
    val TextDisabled = Color(0x40FFFFFF)
    val IconCircle = Color(0x1AFFFFFF)
    val SwitchOffTrack = Color(0x00000000)
    val SwitchOffOutline = Color(0xFF8E9099)
    val SwitchOffThumb = Color(0xFF8E9099)
}
