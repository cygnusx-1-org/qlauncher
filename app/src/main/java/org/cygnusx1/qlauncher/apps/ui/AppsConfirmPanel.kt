/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/**
 * A confirmation side panel in the root panel's geometry: a
 * 320 dp card at the right edge, 16 dp from the top and bottom, the title in the accent colour,
 * 47 dp rows with a full-width focus band. Back cancels.
 *
 * @param preview an optional picture (the icon pack's or shortcut's icon) shown above the message.
 */
@Composable
fun AppsConfirmPanel(
    title: String,
    message: String?,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    focusConfirm: Boolean = true,
    confirmIcon: String = "check",
    cancelIcon: String = "close",
    preview: (@Composable () -> Unit)? = null,
) {
    BackHandler(onBack = onCancel)
    val confirmFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(QPalette.GuidanceScrim),
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, bottom = 16.dp, end = 24.dp)
                .width(320.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(QPalette.PanelBackground),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(91.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 27.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    style = QTheme.type.panelTitle,
                    color = QTheme.colors.accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (preview != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { preview() }
            }
            if (message != null) {
                Text(
                    text = message,
                    style = QTheme.type.rowDescription,
                    color = QPalette.TextDescription,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
            }
            ConfirmRow(cancelLabel, cancelIcon, onCancel, Modifier.focusRequester(cancelFocus))
            ConfirmRow(confirmLabel, confirmIcon, onConfirm, Modifier.focusRequester(confirmFocus))
            Spacer(Modifier.weight(1f))
        }
    }
    LaunchedEffect(focusConfirm) {
        runCatching { (if (focusConfirm) confirmFocus else cancelFocus).requestFocus() }
    }
}

@Composable
private fun ConfirmRow(label: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(47.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = QPalette.PanelFocusedRow,
            pressedContainerColor = QPalette.PanelFocusedRow,
            contentColor = QPalette.TextTitleUnfocused,
            focusedContentColor = QPalette.TextPrimary,
            pressedContentColor = QPalette.TextPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp))
            MaterialIcon(icon, size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, style = QTheme.type.panelRow, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A square preview slot for an icon (64 dp). */
@Composable
fun AppsPreviewBox(content: @Composable () -> Unit) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(QPalette.DefaultCardBackground), contentAlignment = Alignment.Center) {
        content()
    }
}
