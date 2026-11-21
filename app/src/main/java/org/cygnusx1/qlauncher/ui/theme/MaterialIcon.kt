/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * A Material "outlined" icon by its ligature name (for example `settings`, `palette`), drawn
 * from the bundled icon font. Font scaling is ignored so icons keep their size.
 */
@Composable
fun MaterialIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.White,
) {
    val fontSize = with(LocalDensity.current) { (size.toPx() / fontScale).toSp() }
    Text(
        text = name,
        modifier = modifier.size(size).wrapContentSize(Alignment.Center),
        color = tint,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontFamily = MaterialIconsOutlined,
            fontSize = fontSize,
            lineHeight = fontSize,
            textAlign = TextAlign.Center,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        ),
    )
}
