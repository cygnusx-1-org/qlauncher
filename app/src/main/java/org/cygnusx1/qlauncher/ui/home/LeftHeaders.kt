/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QTheme

/**
 * The left headers rail: closed it is a 56 px strip; open,
 * each category is an icon (and title), the focused one with its reorder and settings buttons to
 * its left. UP/DOWN move between categories and scroll the rows with them.
 */
@Composable
fun BoxScope.LeftHeaders(
    rows: List<HomeRow>,
    focus: HomeFocus,
    settings: Settings,
    open: Boolean,
    interactive: Boolean,
    focusedGridCenter: Float,
    alpha: Float,
) {
    val showIcon = settings[Keys.LeftHeaders.showIcon]
    val showTitle = settings[Keys.LeftHeaders.showTitle]
    val showReorder = settings[Keys.LeftHeaders.showReorder]
    val showSettings = settings[Keys.LeftHeaders.showSettings]
    val inHeaders = focus.region == Region.Headers
    val accent = QTheme.colors.accent
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(if (interactive) 250 else 0)) + slideInHorizontally(tween(if (interactive) 250 else 0)) { -it / 4 },
        exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 4 },
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
    ) {
        Box(
            Modifier
                .width(rp(if (showTitle) 420f else 224f))
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .testTag("left_headers"),
        ) {
            val pitch = 82f
            val focusedTop = focusedGridCenter - 33f - 28f
            rows.forEachIndexed { i, row ->
                val y = focusedTop + (i - focus.row) * pitch
                if (y < -100f || y > 1180f) return@forEachIndexed
                val isFocused = i == focus.row
                val dim by animateFloatAsState(if (isFocused) 1f else 0.6f, tween(200), label = "headerDim")
                Row(
                    Modifier
                        .offset(x = rp(20f), y = rp(y))
                        .graphicsLayer { this.alpha = dim }
                        .testTag("header_" + row.id),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val buttonsVisible = isFocused && inHeaders
                    val reorderShown = showReorder && buttonsVisible && !focus.reorderCategory
                    val settingsShown = showSettings && buttonsVisible && !focus.reorderCategory
                    HeaderButton("swap_vert", reorderShown || focus.reorderCategory && isFocused, selected = focus.headerAction == 2 || (focus.reorderCategory && isFocused), accent = accent)
                    Spacer(Modifier.width(rp(5f)))
                    HeaderButton("settings", settingsShown, selected = focus.headerAction == 1 && settingsShown, accent = accent)
                    Spacer(Modifier.width(rp(5f)))
                    Box(
                        Modifier
                            .size(rp(57f))
                            .background(if (isFocused && inHeaders && focus.headerAction == 0) Color.Black.copy(alpha = 0.45f) else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showIcon) CategoryIcon(row, rp(48f), if (isFocused) accent else Color.White)
                    }
                    if (showTitle) {
                        Spacer(Modifier.width(rp(16f)))
                        Text(
                            row.title,
                            color = Color.White,
                            style = QTheme.type.headerLabel.copy(fontFeatureSettings = "smcp"),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: String, visible: Boolean, selected: Boolean, accent: Color) {
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(200), label = "headerButton")
    val shift by animateFloatAsState(if (visible) 0f else 20f, tween(200), label = "headerButtonShift")
    Box(
        Modifier
            .size(rp(48f))
            .graphicsLayer {
                alpha = a
                translationX = shift
            }
            .background(if (selected) Color.Black.copy(alpha = 0.45f) else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MaterialIcon(icon, size = rp(36f), tint = if (selected) accent else Color.White.copy(alpha = 0.8f))
    }
}
