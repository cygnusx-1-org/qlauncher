/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.cygnusx1.qlauncher.R

/** DM Sans, the default font, as a variable font (optical size 14, as Projectivy sets it). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val DmSans: FontFamily = FontFamily(
    listOf(300, 400, 500, 700).flatMap { w ->
        listOf(
            Font(
                R.font.dm_sans,
                weight = FontWeight(w),
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", 14f)),
            ),
            Font(
                R.font.dm_sans_italic,
                weight = FontWeight(w),
                style = FontStyle.Italic,
                variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", 14f)),
            ),
        )
    },
)

/** Material Icons Outlined, drawn by ligature name (see [MaterialIcon]). */
val MaterialIconsOutlined: FontFamily = FontFamily(Font(R.font.material_icons_outlined))
