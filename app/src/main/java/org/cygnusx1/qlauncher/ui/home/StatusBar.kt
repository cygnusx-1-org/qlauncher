/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** What the status bar shows. */
@Immutable
data class StatusBarInfo(
    val buttons: List<StatusButton>,
    val showClock: Boolean,
    val clock: AnnotatedString,
    val connectivityIcon: String = "settings_ethernet",
    val notificationCount: Int = 0,
)

/**
 * The status bar: buttons packed leftwards from the clock, right edge at x = 1808, top at y = 54.
 * The clock's width only ever grows, so changing digits never moves the buttons.
 */
@Composable
fun BoxScope.StatusBar(
    info: StatusBarInfo,
    settings: Settings,
    focused: Boolean,
    focusedButton: StatusButton,
    visible: Boolean,
    alpha: Float,
    clockAlpha: Float,
    screenW: Float,
) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "statusShown")
    val scale = settings[Keys.StatusBar.iconSize]
    val palette = QTheme.colors.palette
    val iconColor = palette.resolve(settings[Keys.StatusBar.iconColor], Color.White)
    val containerStored = settings[Keys.StatusBar.iconBackground]
    val container = if (containerStored == ColorKey.UNSET) null else palette.resolve(containerStored, Color.Transparent)
    val clockColor = palette.resolve(settings[Keys.Clock.color], Color.White)
    val shadowStored = settings[Keys.Clock.shadow]
    val shadowColor = palette.resolve(shadowStored, Color.Black.copy(alpha = 0.6f))
    val buttonSize = rp(80f * scale)
    val rightEdge = screenW - 112f
    val density = LocalDensity.current
    var clockMaxWidth by remember { mutableIntStateOf(0) }

    Layout(
        modifier = Modifier
            .offset(y = rp(54f))
            .graphicsLayer { this.alpha = shown }
            .testTag("status_bar"),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
                info.buttons.forEach { b ->
                    val isFocused = focused && b == focusedButton
                    Box(
                        Modifier
                            .size(buttonSize)
                            .background(
                                when {
                                    isFocused -> container ?: Color.White.copy(alpha = 0.25f)
                                    container != null -> container
                                    else -> Color.Transparent
                                },
                                CircleShape,
                            )
                            .testTag("status_" + b.name.lowercase()),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = when (b) {
                            StatusButton.WallpaperInfo -> "wallpaper"
                            StatusButton.Connectivity -> info.connectivityIcon
                            StatusButton.Notifications -> "notifications"
                            StatusButton.Settings -> "settings"
                        }
                        MaterialIcon(icon, size = rp(56f * scale), tint = iconColor)
                        if (b == StatusButton.Notifications && info.notificationCount > 0) {
                            // The count in a small accent badge on the bell's top-right corner.
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = (6f * scale).dp, end = (6f * scale).dp)
                                    .size((18f * scale).dp)
                                    .background(QTheme.colors.accent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (info.notificationCount > 9) "+" else info.notificationCount.toString(),
                                    color = Color.White,
                                    fontSize = (11f * scale).sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
            if (info.showClock) {
                val size = settings[Keys.Clock.size].sp
                Text(
                    info.clock,
                    color = clockColor,
                    style = QTheme.type.clock(size).copy(
                        shadow = if (shadowColor.alpha > 0f) Shadow(shadowColor, blurRadius = with(density) { 5.dp.toPx() }) else null,
                    ),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .graphicsLayer { this.alpha = clockAlpha }
                        .widthIn(min = with(density) { clockMaxWidth.toDp() })
                        .onSizeChanged { if (it.width > clockMaxWidth) clockMaxWidth = it.width }
                        .testTag("clock"),
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val right = (rightEdge / 2f * density.density).toInt()
        layout(constraints.maxWidth, height) {
            var x = right
            for (pl in placeables.asReversed()) {
                x -= pl.width
                pl.place(x, (height - pl.height) / 2)
            }
        }
    }
}
