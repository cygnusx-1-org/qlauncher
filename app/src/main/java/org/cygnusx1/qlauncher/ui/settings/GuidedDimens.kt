/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import androidx.compose.ui.unit.dp

/**
 * Guided-page geometry, from the `uiautomator dump` bounds (pixels at 320 dpi divided by two).
 */
object GuidedDimens {
    /** Left pane `[0,0][1120,1080]`. */
    val LeftPaneWidth = 560.dp

    /** The right pane is darkened from x = 1184. */
    val ScrimStart = 592.dp

    /** Row list `x = 1232..1872`, or `1168..1872` on live-preview pages. */
    val ListStart = 616.dp
    val ListStartPreview = 584.dp
    val ListEndMargin = 24.dp

    /** Editor pages with a pattern on the left: `x = 1419..1872`. */
    val ListStartWide = 709.5.dp

    /** The focused row's centre: y = 421 (row `[1232,376][1872,466]`). */
    val KeylineCenter = 210.5.dp

    /** 106 px pitch between 90 px rows. */
    val RowGap = 8.dp
    val RowMinHeight = 45.dp

    /** Toggle rows: the switch hit area is 96 px tall plus padding. */
    val ToggleMinHeight = 61.dp
    val RowPadTop = 7.dp
    val RowPadBottom = 6.dp
    val TitleToDescription = 2.dp

    /** Icon circle `x = 1248..1312`. */
    val IconStart = 8.dp
    val IconCircle = 32.dp
    val IconGlyph = 18.dp

    /** Title left at x = 1336. */
    val TitleStart = 52.dp

    /** Title right edge on rows with a chevron (1792) and with a switch (1720). */
    val TitleEndChevron = 40.dp
    val TitleEndSwitch = 76.dp

    /** Chevron centred on x = 1828. */
    val ChevronCenterFromEnd = 22.dp
    val ChevronSize = 24.dp

    /** Switch 104x64 at x = 1736..1840. */
    val SwitchWidth = 52.dp
    val SwitchHeight = 32.dp
    val SwitchEnd = 16.dp

    /** Slider row 158 px: track from 132 px after the row start to 60 px before its end. */
    val SliderStart = 66.dp
    val SliderEnd = 30.dp
    val SliderHeight = 32.dp
    val SliderTrack = 16.dp
    val SliderThumbWidth = 4.dp
    val SliderThumbGap = 6.dp
    val SliderDot = 4.dp
    val SliderTitleGap = 8.dp
    val SliderPadBottom = 14.dp

    /** Dropdown options: 62 px rows at 78 px pitch, text 16 px in from the row start. */
    val OptionHeight = 31.dp
    val OptionGap = 8.dp
    val OptionTextStart = 8.dp

    /** Left pane. */
    val TileX = 99.dp
    val TileY = 153.dp
    val TileSize = 54.dp
    val TextX = 220.dp
    val BreadcrumbY = 135.dp
    val TitleY = 159.dp
    val DescriptionY = 182.5.dp
    val DescriptionWidth = 308.dp
    val QrX = 56.dp
    val QrY = 287.dp
    val QrSize = 140.dp
}
